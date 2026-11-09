package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Guard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

/** Keeps bodies' records current across saves and stops, and brings player bodies back when the server starts. */
public final class BodyRestart {
    private BodyRestart() {
    }

    /**
     * Player bodies aren't saved with their chunks, so each comes back from its record when the server starts.
     * With bodies switched off this still runs, since a body left from before still stands for its owner.
     */
    public static void restorePlayerBodies(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        BodyStore store = BodyStore.get(server);
        long now = System.currentTimeMillis();
        for (BodyRecord record : store.all()) {
            if (record.type() != YoltConfig.BodyType.PLAYER
                    || Bodies.entityOf(server, record) != null) {
                continue;
            }
            // Taken over already, so there is no body to bring back
            if (record.pendingRemoval()) {
                store.remove(record.owner());
                continue;
            }
            // Its time is up already, say after downtime: the expiry removes it from its record, a few at a time
            if (BodyExpiry.isDue(record, Settings.get().bodies.duration, now)) {
                continue;
            }
            // One that fails keeps its record, so the owner's login still takes over its last saved state
            if (server.getLevel(record.snapshot().dimension()) == null) {
                YouOnlyLiveTwice.LOGGER.error("Could not bring back the body of {}: its dimension {} is gone",
                        record.ownerName(), record.snapshot().dimension().identifier());
                continue;
            }
            Guard.run(() -> {
                if (PlayerBody.fromRecord(server, record) == null) {
                    YouOnlyLiveTwice.LOGGER.error("Could not bring back the body of {}: the world refused the entity {}",
                            record.ownerName(), record.bodyEntity());
                }
            }, "Could not bring back the body of {}", record.ownerName());
        }
    }

    /**
     * Saves the state of every loaded body as the server stops and before each save. A player body isn't saved with its
     * chunk, so its record is all that comes back. A mannequin's record is what a login uses while its chunk stays
     * unloaded, and this keeps it current without depending on when the stop unloads that chunk.
     */
    public static void snapshotBodies(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        BodyStore store = BodyStore.get(server);
        for (BodyRecord record : store.all()) {
            // This runs at the start of the stop and of each save, which must go ahead anyway
            Guard.run(() -> {
                // A dying body belongs to the death path, and its state is nothing to hand over
                if (!record.pendingRemoval() && Bodies.entityOf(server, record) instanceof LivingEntity body
                        && !body.isDeadOrDying()) {
                    store.put(record.withSnapshot(BodySnapshot.of(body)));
                }
            }, "Could not save the state of the body of {}", record.ownerName());
        }
    }
}
