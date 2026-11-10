package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Later;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;

import java.util.Optional;

/** Keeps a body's record in step with its chunk unloading and loading. */
public final class BodyChunks {
    private BodyChunks() {
    }

    /** Saves a body's state as its chunk unloads, for a login while it stays unloaded. */
    public static void onBodyUnload(Entity entity) {
        if (!Settings.active() || !(entity instanceof LivingEntity body) || !entity.entityTags().contains(Bodies.TAG)
                || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // The event fires for every removal. A chunk unload ends tracking before any removal reason is set, so
        // only killed or discarded bodies are skipped here, and dying ones: their state is nothing to hand over.
        Entity.RemovalReason reason = entity.getRemovalReason();
        if ((reason != null && reason.shouldDestroy()) || body.isDeadOrDying()) {
            return;
        }
        BodyStore store = BodyStore.get(level.getServer());
        store.byEntity(entity.getUUID()).ifPresent(record -> store.put(record.withSnapshot(BodySnapshot.of(body))));
    }

    /**
     * Removes a body that loads after its owner took it over, or that no record points at any more. A body that stays
     * gets back the health its gear allows.
     */
    static void onBodyLoad(Entity entity, ServerLevel level) {
        if (!Settings.active() || !entity.entityTags().contains(Bodies.TAG)) {
            return;
        }
        MinecraftServer server = level.getServer();
        // A new body loads before its record exists, and needs nothing here
        if (entity instanceof Mannequin body) {
            BodyStore.get(server).byEntity(entity.getUUID()).filter(record -> !record.pendingRemoval())
                    .ifPresent(record -> restoreHealth(body, record.snapshot()));
        }
        // Changing the entity list during a load can throw, so this waits. The check waits too: a new body loads
        // before its record.
        Later.afterTick(server, () -> {
            if (!Settings.active() || entity.isRemoved()) {
                return;
            }
            BodyStore store = BodyStore.get(server);
            Optional<BodyRecord> record = store.byEntity(entity.getUUID());
            if (record.isPresent() && !record.get().pendingRemoval()) {
                return;
            }
            record.ifPresent(r -> store.remove(r.owner()));
            entity.discard();
        });
    }

    // Loading sets the health before the gear's modifiers are back (LivingEntity.readAdditionalSaveData), which clamps
    // it to the base maximum. The record was saved with the chunk, so it holds what the body had. A body saved while
    // dying keeps dying: its record isn't updated then.
    private static void restoreHealth(LivingEntity body, BodySnapshot saved) {
        BodySpawn.applyGearModifiers(body);
        if (!body.isDeadOrDying() && saved.health() > body.getHealth()) {
            body.setHealth(saved.health());
        }
    }
}
