package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/** Leaves a body behind when a player logs out, and hands its state back when they log in. */
public final class Bodies {
    /** Marks the entities we made, so a decorative mannequin is never taken for a body. */
    public static final String TAG = "yolt_body";

    private Bodies() {
    }

    public static void init() {
        // LEAVE runs on the server thread before vanilla saves the player; DISCONNECT runs on the network thread
        ServerPlayerEvents.LEAVE.register(player -> {
            // A failure here must not stop the logout save that follows
            try {
                onLogout(player);
            } catch (RuntimeException e) {
                YouOnlyLiveTwice.LOGGER.error("Could not leave a body for {}", player.nameAndId().name(), e);
            }
        });
        // JOIN runs on the server thread once the player is in the world
        ServerPlayerEvents.JOIN.register(player -> {
            // Thrown out of here, it would leave the player half joined
            try {
                onLogin(player);
            } catch (RuntimeException e) {
                YouOnlyLiveTwice.LOGGER.error("Could not hand the body back to {}", player.nameAndId().name(), e);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> onBodyUnload(entity));
        ServerEntityEvents.ENTITY_LOAD.register(Bodies::onBodyLoad);
    }

    /** The new body, or empty when this logout gets none. */
    public static Optional<Entity> onLogout(ServerPlayer player) {
        YoltConfig.BodiesSection cfg = Settings.get().bodies;
        // Dead players get none: the lockout's kick after a death comes through here too
        if (!Settings.active() || !cfg.enabled || player.isDeadOrDying() || player.isSpectator()
                || DeathLockout.refusal(player.getUUID()) != null) {
            return Optional.empty();
        }
        MinecraftServer server = player.level().getServer();
        BodyStore store = BodyStore.get(server);
        // Out again before the old body was taken back: it goes, so there is never a second one
        store.byOwner(player.getUUID()).ifPresent(old -> discard(server, old));

        Mannequin body = MannequinBodies.spawn(player, cfg);
        store.put(new BodyRecord(player.getUUID(), player.nameAndId().name(), body.getUUID(), YoltConfig.BodyType.MANNEQUIN,
                System.currentTimeMillis(), false, BodySnapshot.of(body)));
        return Optional.of(body);
    }

    /**
     * The player takes over their body as it is now, and the body goes. With bodies switched off this still runs,
     * so a body left from before is never stranded.
     */
    public static void onLogin(ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        BodyStore store = BodyStore.get(server);
        Optional<BodyRecord> found = store.byOwner(player.getUUID());
        // A pending record was taken over already and only waits for its body to load
        if (found.isEmpty() || found.get().pendingRemoval()) {
            return;
        }
        BodyRecord record = found.get();
        BodySnapshot snapshot;
        boolean bodyAlive;
        if (server.overworld().getEntityInAnyDimension(record.bodyEntity()) instanceof LivingEntity body) {
            snapshot = BodySnapshot.of(body);
            bodyAlive = !body.isDeadOrDying();
            // The record goes first, so the discard's unload no longer counts this as a body
            store.remove(record.owner());
            body.discard();
        } else {
            // Its chunk isn't loaded: the last saved state is used, and the body goes when it next loads
            snapshot = record.snapshot();
            bodyAlive = snapshot.health() > 0.0F;
            store.put(record.withPendingRemoval(true));
        }
        // Health 0 without a death would respawn the player and drop their inventory. A player who joins dead
        // stays dead: their gear may be in a grave already, and the body only holds copies of it.
        if (bodyAlive && !player.isDeadOrDying()) {
            takeOver(player, snapshot, Settings.get().bodies.copyEffects);
        }
    }

    /** Saves a body's state as its chunk unloads, for a login while it stays unloaded. */
    public static void onBodyUnload(Entity entity) {
        if (!Settings.active() || !(entity instanceof LivingEntity body) || !entity.entityTags().contains(TAG)
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

    /** Removes a body that loads after its owner took it over, or that no record points at any more. */
    public static void onBodyLoad(Entity entity, ServerLevel level) {
        if (!Settings.active() || !entity.entityTags().contains(TAG)) {
            return;
        }
        MinecraftServer server = level.getServer();
        // Changing the entity list during a load can throw, so this waits. execute() would run it straight away
        // on the server thread; schedule() always queues it. The check waits too: a new body loads before its record.
        server.schedule(server.wrapRunnable(() -> {
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
        }));
    }

    /** True when the entity carries our tag and a record still points at it. */
    public static boolean isBody(Entity entity) {
        return entity.entityTags().contains(TAG) && entity.level() instanceof ServerLevel level
                && BodyStore.get(level.getServer()).byEntity(entity.getUUID()).isPresent();
    }

    // Any dimension, in case an admin moved it. One in an unloaded chunk isn't found, but once the record
    // is replaced it no longer counts as a body.
    private static void discard(MinecraftServer server, BodyRecord old) {
        Entity entity = server.overworld().getEntityInAnyDimension(old.bodyEntity());
        if (entity != null) {
            entity.discard();
        }
    }

    private static void takeOver(ServerPlayer player, BodySnapshot snapshot, boolean copyEffects) {
        // The body may stand in another dimension than the player's save if it was moved
        ServerLevel level = player.level().getServer().getLevel(snapshot.dimension());
        if (level != null) {
            Vec3 pos = snapshot.pos();
            player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), snapshot.yRot(), player.getXRot(), true);
        }
        if (copyEffects) {
            player.removeAllEffects();
            for (MobEffectInstance effect : snapshot.effects()) {
                // A copy: a record kept for later must not change as the effect ticks
                player.addEffect(new MobEffectInstance(effect));
            }
        }
        // After the effects: health boost and absorption raise the caps these are clamped to
        player.setHealth(snapshot.health());
        player.setAbsorptionAmount(snapshot.absorption());
        GearSync.applyBodyGear(snapshot.gear(), player::getItemBySlot, player::setItemSlot);
    }
}
