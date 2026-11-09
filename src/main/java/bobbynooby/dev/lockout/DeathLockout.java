package bobbynooby.dev.lockout;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Later;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public final class DeathLockout {
    /** What a revive did. */
    public enum ReviveResult {
        REVIVED,
        // Past its days, or revived before; nothing was changed
        ALREADY_ENDED,
        NO_DEATH,
        // The store is closed or failing
        FAILED
    }

    private static volatile DeathStore store;

    private DeathLockout() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            if (!Settings.active()) {
                return;
            }
            store = openStore(started.getWorldPath(LevelResource.ROOT).resolve("database/" + Yolt.MOD_ID + ".db"));
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
                if (enabled() && refusal(player.getUUID(), player.nameAndId().name()) != null) {
                    kickLater(player);
                }
            }, "Could not check death lockout for {} as they joined", player.nameAndId().name());
        });
    }

    /** Null if it can't be opened: the server still starts, like with a failing query, but deaths aren't recorded. */
    static @Nullable DeathStore openStore(Path file) {
        try {
            DeathStore opened = DeathStore.open(file);
            Yolt.LOGGER.info("Opened death store");
            return opened;
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not open the death store, deaths will not be recorded", e);
            return null;
        }
    }

    static void closeStore(DeathStore open) {
        if (open == null) {
            return;
        }
        try {
            open.close();
            Yolt.LOGGER.info("Closed death store");
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not close the death store", e);
        }
    }

    /** Null when the player may join. */
    public static @Nullable Component refusal(UUID uuid) {
        return refusal(uuid, uuid.toString());
    }

    /** Like the above, with the name the logs use for the player. */
    public static @Nullable Component refusal(UUID uuid, String name) {
        DeathStore open = store;
        if (open == null || !Settings.get().deathLockout.enabled) {
            return null;
        }
        Optional<DeathLog> log;
        try {
            log = open.latest(uuid);
        } catch (RuntimeException e) {
            // Fail open: a broken database must not lock everyone out
            Yolt.LOGGER.error("Could not check death lockout for {}, letting them in", name, e);
            return null;
        }
        if (log.isEmpty()) {
            return null;
        }
        long remaining = Cooldown.remainingMillis(log.get(), System.currentTimeMillis());
        if (remaining <= 0) {
            return null;
        }
        return Component.literal(Cooldown.refusal(log.get().deathMessage(), remaining));
    }

    /**
     * Logs the death with the message the player's death screen got, then kicks after the current tick so the kick
     * isn't inside the death call.
     */
    public static void recordDeath(ServerPlayer player) {
        if (!enabled()) {
            return;
        }
        recordDeath(player.getUUID(), player.nameAndId().name(), DeathMessages.of(player));
        kickLater(player);
    }

    // Kicks with the login refusal's text. Checked again when it runs: a revive in the same tick, or a cooldown of 0
    // days, leaves nothing to keep the player out for.
    private static void kickLater(ServerPlayer player) {
        UUID uuid = player.getUUID();
        MinecraftServer server = player.level().getServer();
        Later.afterTick(server, () -> {
            // By uuid: they may have left already, or respawned as a new player object
            ServerPlayer current = server.getPlayerList().getPlayer(uuid);
            Component reason = current == null ? null : refusal(uuid, current.nameAndId().name());
            if (reason != null) {
                current.connection.disconnect(reason);
            }
        });
    }

    /** A death with no message; the player is told only that they died. */
    public static void recordDeath(UUID uuid, String name) {
        recordDeath(uuid, name, null);
    }

    public static void recordDeath(UUID uuid, String name, @Nullable String deathMessage) {
        if (!enabled()) {
            return;
        }
        DeathStore open = store;
        if (open == null) {
            Yolt.LOGGER.error("Death store is closed, not recording death of {}", name);
            return;
        }
        try {
            open.recordDeath(uuid, name, System.currentTimeMillis(), Settings.get().deathLockout.cooldownDays, deathMessage);
            Yolt.LOGGER.info("Death log added for {}", name);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not record death of {}", name, e);
        }
    }

    /**
     * The player's newest death, read before a kill so the kill's own row can be told apart afterwards. Null when
     * there is none, or the store can't be read; the check after the kill reports a store that can't.
     */
    public static @Nullable DeathLog newestDeath(UUID uuid) {
        DeathStore open = store;
        if (open == null) {
            return null;
        }
        try {
            return open.latest(uuid).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether a death that locks the player out was stored since {@code before} was read: the newest row differs from
     * it and isn't revived. True with the lockout off, when nothing is stored. A kill records its death inside the
     * death event, where no result can reach the caller, so the caller asks afterwards.
     */
    public static boolean deathRecordedAfter(UUID uuid, String name, @Nullable DeathLog before) {
        if (!enabled()) {
            return true;
        }
        DeathStore open = store;
        if (open == null) {
            return false;
        }
        try {
            Optional<DeathLog> now = open.latest(uuid);
            return now.isPresent() && !now.get().revived() && !now.get().equals(before);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not read back the death of {}", name, e);
            return false;
        }
    }

    // Checked here instead of by each caller, so a new caller can't forget the switch
    private static boolean enabled() {
        return Settings.on(c -> c.deathLockout.enabled);
    }

    /**
     * Ends the newest death's lockout, keeping the death and who revived it: a player's name, or "console". A lockout
     * that is already over is left as it is.
     */
    public static ReviveResult revive(UUID uuid, String by) {
        return revive(uuid, uuid.toString(), by);
    }

    /** Like the above, with the name the logs use for the player. */
    public static ReviveResult revive(UUID uuid, String name, String by) {
        DeathStore open = store;
        if (open == null) {
            Yolt.LOGGER.error("Death store is closed, could not revive {}", name);
            return ReviveResult.FAILED;
        }
        try {
            Optional<DeathLog> log = open.latest(uuid);
            if (log.isEmpty()) {
                return ReviveResult.NO_DEATH;
            }
            long now = System.currentTimeMillis();
            if (Cooldown.remainingMillis(log.get(), now) <= 0) {
                return ReviveResult.ALREADY_ENDED;
            }
            return open.revive(uuid, now, by) ? ReviveResult.REVIVED : ReviveResult.NO_DEATH;
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not revive {}", name, e);
            return ReviveResult.FAILED;
        }
    }

    /**
     * The player who last died under this name, ignoring case, with the name as they had it. Empty while the store is
     * closed or failing.
     */
    public static Optional<NameAndId> latestByName(String name) {
        DeathStore open = store;
        if (open == null) {
            return Optional.empty();
        }
        try {
            return open.latestByName(name).map(log -> new NameAndId(log.uuid(), log.name()));
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not look up {} in the death log", name, e);
            return Optional.empty();
        }
    }

    /** Swaps the open store and returns the old one; lets tests simulate a broken database. */
    public static DeathStore swapStoreForTesting(DeathStore replacement) {
        DeathStore old = store;
        store = replacement;
        return old;
    }
}
