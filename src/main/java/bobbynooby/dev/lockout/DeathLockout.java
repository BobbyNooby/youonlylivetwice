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
    private static volatile MinecraftServer server;

    private DeathLockout() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            if (!Settings.active()) {
                return;
            }
            server = started;
            store = DeathStore.open(started.getWorldPath(LevelResource.ROOT).resolve("database/you-only-live-twice.db"));
            YouOnlyLiveTwice.LOGGER.info("Opened death store");
        });

        // Not SERVER_STOPPING: players are still logged out after that and later code reads the store then
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            DeathStore open = store;
            store = null;
            server = null;
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
        Optional<DeathLog> log = open.latest(uuid);
        if (log.isEmpty()) {
            return null;
        }
        long remaining = Cooldown.remainingMillis(log.get(), System.currentTimeMillis());
        if (remaining <= 0) {
            return null;
        }
        return Component.literal(Cooldown.message(remaining));
    }

    /** Logs the death, then kicks on the next tick so the kick isn't inside the death call. */
    public static void recordDeath(ServerPlayer player) {
        recordDeath(player.getUUID(), player.nameAndId().name());
        MinecraftServer current = server;
        if (current != null) {
            current.execute(() -> player.connection.disconnect(Component.literal("You have died.")));
        }
    }

    public static void recordDeath(UUID uuid, String name) {
        DeathStore open = store;
        if (open == null) {
            YouOnlyLiveTwice.LOGGER.warn("Death store is closed, not recording death of {}", name);
            return;
        }
        open.recordDeath(uuid, name, System.currentTimeMillis(), Settings.get().deathLockout.cooldownDays);
        YouOnlyLiveTwice.LOGGER.info("Death log added for {}", name);
    }

    public static boolean revive(UUID uuid) {
        DeathStore open = store;
        return open != null && open.revive(uuid);
    }
}
