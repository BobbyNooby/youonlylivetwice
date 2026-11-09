package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Removes the bodies that have stood for bodies.duration, the way their owner's login would have taken them over. */
public final class BodyExpiry {
    private static final long MINUTE_MILLIS = 60_000L;

    // Bodies whose expiry failed, so each failure is logged once instead of on every pass
    private static final Set<UUID> FAILED = new HashSet<>();

    private BodyExpiry() {
    }

    public static void init() {
        // Once a second is plenty for a time counted in minutes
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                tick(server);
            }
        });
    }

    /**
     * Writes each expired body's state into its owner's save, then removes it. With bodies switched off this still
     * runs, like the login does.
     */
    public static void tick(MinecraftServer server) {
        BodyDuration duration = Settings.get().bodies.duration;
        if (!Settings.active() || duration.isOffline()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (BodyRecord record : BodyStore.get(server).all()) {
            // A pending record was taken over already
            if (record.pendingRemoval() || now - record.loggedOutAt() < duration.minutes() * MINUTE_MILLIS) {
                continue;
            }
            // The death path owns a dying body. An owner who is online isn't away, and saves over their file anyway.
            Entity entity = server.overworld().getEntityInAnyDimension(record.bodyEntity());
            if ((entity instanceof LivingEntity body && body.isDeadOrDying())
                    || server.getPlayerList().getPlayer(record.owner()) != null) {
                continue;
            }
            try {
                expire(server, record, entity);
                FAILED.remove(record.bodyEntity());
            } catch (RuntimeException e) {
                // The body and record stay, and the next pass starts again from a freshly loaded save
                if (FAILED.add(record.bodyEntity())) {
                    YouOnlyLiveTwice.LOGGER.error("Could not remove the expired body of {}; it stays until this works",
                            record.ownerName(), e);
                }
            }
        }
    }

    // The save is written before anything goes, so a failure leaves the body standing and nothing lost
    private static void expire(MinecraftServer server, BodyRecord record, Entity entity) {
        NameAndId who = new NameAndId(record.owner(), record.ownerName());
        CompoundTag tag = OfflinePlayerData.load(server, who)
                .orElseThrow(() -> new IllegalStateException("No save file for " + who.name()));
        // A loaded body as it is now; one in an unloaded chunk as it was last saved
        BodySnapshot snapshot = entity instanceof LivingEntity body ? BodySnapshot.of(body) : record.snapshot();
        // An owner already dead (say killed offline after a crash lost this record's removal) keeps the save they
        // have. Health 0 without a death would respawn them and drop their inventory.
        if (tag.getFloatOr("Health", 1.0F) > 0.0F && snapshot.health() > 0.0F) {
            OfflinePlayerData.applySnapshot(tag, snapshot, Settings.get().bodies.copyEffects, server.registryAccess());
            OfflinePlayerData.save(server, record.owner(), tag);
        }
        // The record goes first, so the discard's unload no longer counts this as a body. One that isn't loaded
        // goes when it next loads, since no record points at it.
        BodyStore.get(server).remove(record.owner());
        if (entity != null) {
            entity.discard();
        }
    }
}
