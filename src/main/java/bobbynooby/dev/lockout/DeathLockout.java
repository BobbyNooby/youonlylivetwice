package bobbynooby.dev.lockout;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Later;
import bobbynooby.dev.core.StandIn;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public final class DeathLockout {
    private static volatile DeathStore store;

    private DeathLockout() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            if (!Settings.active()) {
                return;
            }
            store = openStore(started.getWorldPath(LevelResource.ROOT).resolve("database/you-only-live-twice.db"));
        });

        // Not SERVER_STOPPING: players are still logged out after that and later code reads the store then
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            DeathStore open = store;
            store = null;
            closeStore(open);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // A player body's death records its owner's, through the offline death
            if (entity instanceof ServerPlayer player && !StandIn.is(player)) {
                recordDeath(player);
            }
        });

        // A death can land after the login check, before the save is read (a body killed while its owner's spawn
        // area loads). They join dead, and would respawn and play through the lockout.
        // Runs before the body handover, so a throw here must not escape and skip it.
        ServerPlayerEvents.JOIN.register(player -> {
            Guard.run(() -> {
                Component refusal = enabled() ? refusal(player.getUUID()) : null;
                if (refusal != null) {
                    kickLater(player, refusal);
                }
            }, "Could not check death lockout for {} as they joined", player.nameAndId().name());
        });
    }

    /** Null if it can't be opened: the server still starts, like with a failing query, but deaths aren't recorded. */
    static DeathStore openStore(Path file) {
        try {
            DeathStore opened = DeathStore.open(file);
            YouOnlyLiveTwice.LOGGER.info("Opened death store");
            return opened;
        } catch (RuntimeException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not open the death store, deaths will not be recorded", e);
            return null;
        }
    }

    static void closeStore(DeathStore open) {
        if (open == null) {
            return;
        }
        try {
            open.close();
            YouOnlyLiveTwice.LOGGER.info("Closed death store");
        } catch (RuntimeException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not close the death store", e);
        }
    }

    /** Null when the player may join. */
    public static Component refusal(UUID uuid) {
        DeathStore open = store;
        if (open == null || !Settings.get().deathLockout.enabled) {
            return null;
        }
        Optional<DeathLog> log;
        try {
            log = open.latest(uuid);
        } catch (RuntimeException e) {
            // Fail open: a broken database must not lock everyone out
            YouOnlyLiveTwice.LOGGER.error("Could not check death lockout for {}, letting them in", uuid, e);
            return null;
        }
        if (log.isEmpty()) {
            return null;
        }
        long remaining = Cooldown.remainingMillis(log.get(), System.currentTimeMillis());
        if (remaining <= 0) {
            return null;
        }
        return Component.literal(Cooldown.message(remaining));
    }

    /** Logs the death, then kicks after the current tick so the kick isn't inside the death call. */
    public static void recordDeath(ServerPlayer player) {
        if (!enabled()) {
            return;
        }
        recordDeath(player.getUUID(), player.nameAndId().name());
        kickLater(player, Component.literal("You have died."));
    }

    private static void kickLater(ServerPlayer player, Component reason) {
        UUID uuid = player.getUUID();
        MinecraftServer server = player.level().getServer();
        Later.afterTick(server, () -> {
            // By uuid: they may have left already, or respawned as a new player object
            ServerPlayer current = server.getPlayerList().getPlayer(uuid);
            if (current != null) {
                current.connection.disconnect(reason);
            }
        });
    }

    public static void recordDeath(UUID uuid, String name) {
        if (!enabled()) {
            return;
        }
        DeathStore open = store;
        if (open == null) {
            YouOnlyLiveTwice.LOGGER.warn("Death store is closed, not recording death of {}", name);
            return;
        }
        try {
            open.recordDeath(uuid, name, System.currentTimeMillis(), Settings.get().deathLockout.cooldownDays);
            YouOnlyLiveTwice.LOGGER.info("Death log added for {}", name);
        } catch (RuntimeException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not record death of {}", name, e);
        }
    }

    // Checked here instead of by each caller, so a new caller can't forget the switch
    private static boolean enabled() {
        return Settings.on(c -> c.deathLockout.enabled);
    }

    public static boolean revive(UUID uuid) {
        DeathStore open = store;
        if (open == null) {
            return false;
        }
        try {
            return open.revive(uuid);
        } catch (RuntimeException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not revive {}", uuid, e);
            return false;
        }
    }

    /** Swaps the open store and returns the old one; lets tests simulate a broken database. */
    public static DeathStore swapStoreForTesting(DeathStore replacement) {
        DeathStore old = store;
        store = replacement;
        return old;
    }
}
