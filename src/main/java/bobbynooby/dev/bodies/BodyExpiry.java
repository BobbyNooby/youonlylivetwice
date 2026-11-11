package bobbynooby.dev.bodies;

import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.LastGround;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Removes the bodies that have stood for bodies.duration, the way their owner's login would have taken them over.
 * Server thread only.
 */
public final class BodyExpiry {
    private static final long MINUTE_MILLIS = 60_000L;
    // Loading an unreadable save makes vanilla log a warning and keep a copy of the file every time, so a body
    // whose expiry failed waits before the next try: this long at first, twice as long after each failure since, and
    // never more than a day
    private static final long FIRST_RETRY_MILLIS = 5 * MINUTE_MILLIS;
    private static final long MAX_RETRY_MILLIS = 24 * 60 * MINUTE_MILLIS;
    /**
     * How many bodies one pass tries to remove. Each loads and writes a save on the server thread, and after downtime
     * everyone who was online at the stop shares one logout time, so they all come due in the same tick.
     */
    public static final int MAX_PER_PASS = 4;

    // Body entity id to its next try, for bodies whose expiry failed. Each failure logs our error with the wait.
    private static final Map<UUID, Retry> RETRIES = new HashMap<>();

    private record Retry(long at, long waitMillis) {}

    private BodyExpiry() {}

    public static void init() {
        // Once a second is plenty for a time counted in minutes
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SharedConstants.TICKS_PER_SECOND == 0) {
                tick(server);
            }
        });
        // Static, so it would outlive the server in a JVM that starts another one
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RETRIES.clear());
    }

    /**
     * Writes each expired body's state into its owner's save, then removes it. With bodies switched off this still
     * runs, like the login does.
     */
    public static void tick(MinecraftServer server) {
        tick(server, System.currentTimeMillis());
    }

    /** The same as at the wall-clock time now, in milliseconds, so a test can step past a retry's wait. */
    public static void tick(MinecraftServer server, long now) {
        if (!Settings.active()) {
            return;
        }
        Collection<BodyRecord> records = BodyStore.get(server).all();
        // A body taken over, killed or replaced by a new logout since it failed is done with
        Set<UUID> standing = records.stream().filter(record -> !record.pendingRemoval())
                .map(BodyRecord::bodyEntity).collect(Collectors.toSet());
        RETRIES.keySet().retainAll(standing);
        BodyDuration duration = Settings.get().bodies.duration;
        if (duration.isOffline()) {
            return;
        }
        int tried = 0;
        for (BodyRecord record : records) {
            // The rest wait for the next pass
            if (tried >= MAX_PER_PASS) {
                break;
            }
            // A pending record was taken over already, and a body that failed waits for its next try
            Retry retry = RETRIES.get(record.bodyEntity());
            if (record.pendingRemoval() || !isDue(record, duration, now) || (retry != null && now < retry.at())) {
                continue;
            }
            // The death path owns a dying body. An owner who is online isn't away, and saves over their file anyway.
            Entity entity = Bodies.entityOf(server, record);
            if ((entity instanceof LivingEntity body && body.isDeadOrDying())
                    || server.getPlayerList().getPlayer(record.owner()) != null) {
                continue;
            }
            // A failed try counts too: it loads the save all the same
            tried++;
            try {
                expire(server, record, entity);
                RETRIES.remove(record.bodyEntity());
            } catch (RuntimeException e) {
                // The body and record stay, and the next try starts again from a freshly loaded save
                long wait = retry == null ? FIRST_RETRY_MILLIS : Math.min(retry.waitMillis() * 2, MAX_RETRY_MILLIS);
                RETRIES.put(record.bodyEntity(), new Retry(now + wait, wait));
                String line = "Could not remove the expired body of {}; it stays, and this is tried again in {} "
                        + "minutes";
                if (retry == null) {
                    Yolt.LOGGER.error(line, record.ownerName(), wait / MINUTE_MILLIS, e);
                } else {
                    // Most likely the same failure again, so its trace is only logged the first time
                    Yolt.LOGGER.error(line, record.ownerName(), wait / MINUTE_MILLIS);
                }
            }
        }
    }

    /** True when a body has stood for the duration. Never with the duration "offline". */
    static boolean isDue(BodyRecord record, BodyDuration duration, long now) {
        return !duration.isOffline() && now - record.loggedOutAt() >= duration.minutes() * MINUTE_MILLIS;
    }

    // The save is written before anything goes, so a failure leaves the body standing and nothing lost
    private static void expire(MinecraftServer server, BodyRecord record, Entity entity) {
        CompoundTag tag = OfflinePlayerData.loadOrThrow(server, record.who());
        // A loaded body as it is now; one in an unloaded chunk as it was last saved
        BodySnapshot snapshot = entity instanceof LivingEntity body ? BodySnapshot.of(body) : record.snapshot();
        // An owner already dead (say killed offline after a crash lost this record's removal) keeps the save they
        // have. Health 0 without a death would respawn them and drop their inventory.
        if (!OfflinePlayerData.isDead(tag) && snapshot.health() > 0.0F) {
            List<ItemStack> unplaced = BodySaves.applySnapshot(tag, snapshot, Settings.get().bodies.copyEffects,
                    server.registryAccess(), record.ownerName());
            // A body item the save had no room for drops where the body stands. It is taken back if the save fails,
            // so the retry can't drop it twice.
            Runnable undoDrop = Bodies.dropForGood(server, snapshot.dimension(), snapshot.pos(), unplaced);
            try {
                OfflinePlayerData.save(server, record.owner(), tag);
            } catch (RuntimeException e) {
                undoDrop.run();
                throw e;
            }
        } else {
            // Nothing goes into the save, so what a dispenser put on the body would go with it
            Bodies.dropAddedGear(server, record, snapshot);
        }
        // The record goes first, so the discard's unload no longer counts this as a body. One that isn't loaded
        // goes when it next loads, since no record points at it.
        BodyStore.get(server).remove(record.owner());
        LastGround.forget(record.owner());
        if (entity != null) {
            entity.discard();
        }
    }
}
