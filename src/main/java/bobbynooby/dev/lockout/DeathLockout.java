package bobbynooby.dev.lockout;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

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
            store = DeathStore.open(started.getWorldPath(LevelResource.ROOT).resolve("database/you-only-live-twice.db"));
            YouOnlyLiveTwice.LOGGER.info("Opened death store");
        });

        // Not SERVER_STOPPING: players are still logged out after that and later code reads the store then
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            DeathStore open = store;
            store = null;
            if (open != null) {
                open.close();
                YouOnlyLiveTwice.LOGGER.info("Closed death store");
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && Settings.active() && Settings.get().deathLockout.enabled) {
                recordDeath(player);
            }
        });
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
        UUID uuid = player.getUUID();
        recordDeath(uuid, player.nameAndId().name());
        MinecraftServer server = player.level().getServer();
        // execute() would run it straight away on the server thread; schedule() always queues it
        server.schedule(server.wrapRunnable(() -> {
            // By uuid: they may have left already, or respawned as a new player object
            ServerPlayer current = server.getPlayerList().getPlayer(uuid);
            if (current != null) {
                current.connection.disconnect(Component.literal("You have died."));
            }
        }));
    }

    public static void recordDeath(UUID uuid, String name) {
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
