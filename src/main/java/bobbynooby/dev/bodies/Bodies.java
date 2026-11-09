package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflineKill;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Leaves a body behind when a player logs out, hands its state back when they log in, and kills them when it dies. */
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
        // Fires inside die(), after the death drops, which the body never has
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> onBodyDeath(entity));
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

    /**
     * A body's death is its owner's: their saved items go into a grave where the body fell, and the lockout starts.
     * With bodies switched off this still runs, since a body left from before still stands for its owner.
     */
    public static void onBodyDeath(LivingEntity body) {
        if (!Settings.active() || !body.entityTags().contains(TAG) || !(body.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        Optional<BodyRecord> found = BodyStore.get(server).byEntity(body.getUUID());
        // A pending record was taken over already, so its body is a stale copy. An owner who is online isn't away.
        if (found.isEmpty() || found.get().pendingRemoval() || server.getPlayerList().getPlayer(found.get().owner()) != null) {
            return;
        }
        BodyRecord record = found.get();
        try {
            killOwner(server, record, body);
        } catch (RuntimeException e) {
            // No retry: a failed death leaves the save as it was, so nothing is lost, and the owner's next login
            // takes over the body's last saved state instead. A retry would also start from the wiped tag.
            YouOnlyLiveTwice.LOGGER.error("The body of {} died, but they could not be killed", record.ownerName(), e);
        }
    }

    /**
     * Offline /yolt kill of a player whose body still stands. A loaded body is killed and its death does the rest;
     * one in an unloaded chunk dies at its last saved spot, and goes when its chunk next loads.
     * Throws if the owner could not be killed.
     */
    public static OfflineKill.KillResult killBody(MinecraftServer server, BodyRecord record) {
        // A body still dying from a death that failed to kill its owner counts as gone
        if (server.overworld().getEntityInAnyDimension(record.bodyEntity()) instanceof LivingEntity body
                && !body.isDeadOrDying() && body.level() instanceof ServerLevel level) {
            body.kill(level);
            if (BodyStore.get(server).byOwner(record.owner()).isEmpty()) {
                return OfflineKill.KillResult.KILLED_OFFLINE;
            }
            if (!body.isDeadOrDying()) {
                // Another mod stopped the death
                return OfflineKill.KillResult.NOT_KILLED;
            }
            throw new IllegalStateException("The body of " + record.ownerName() + " died, but they could not be killed");
        }
        BodySnapshot snapshot = record.snapshot();
        // No entity, so no skin to keep: the grave head is built from the name
        killOwner(server, record, snapshot.gear(), GlobalPos.of(snapshot.dimension(), BlockPos.containing(snapshot.pos())),
                GraveItems.profileOf(new NameAndId(record.owner(), record.ownerName())));
        return OfflineKill.KillResult.KILLED_OFFLINE;
    }

    /** True for anything we spawned as a body: it wears copies of its owner's saved gear, so its death drops nothing. */
    public static boolean dropsNothing(LivingEntity entity) {
        return Settings.active() && isTaggedBody(entity);
    }

    /** True when the entity carries our tag and a record still points at it. */
    public static boolean isBody(Entity entity) {
        return entity.entityTags().contains(TAG) && entity.level() instanceof ServerLevel level
                && BodyStore.get(level.getServer()).byEntity(entity.getUUID()).isPresent();
    }

    // An admin can put the tag on a real player too, whose items are real. A player-type body is never in the
    // player list, so it still counts.
    private static boolean isTaggedBody(Entity entity) {
        return entity.entityTags().contains(TAG) && !(entity instanceof ServerPlayer player
                && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player);
    }

    // Any dimension, in case an admin moved it. One in an unloaded chunk isn't found, but once the record
    // is replaced it no longer counts as a body.
    private static void discard(MinecraftServer server, BodyRecord old) {
        Entity entity = server.overworld().getEntityInAnyDimension(old.bodyEntity());
        if (entity != null) {
            entity.discard();
        }
    }

    // The body's gear as it is now, where it fell, and its skin for the grave head
    private static void killOwner(MinecraftServer server, BodyRecord record, LivingEntity body) {
        ResolvableProfile profile = body instanceof Avatar avatar
                ? avatar.getProfile()
                : GraveItems.profileOf(new NameAndId(record.owner(), record.ownerName()));
        killOwner(server, record, BodySnapshot.of(body).gear(), GlobalPos.of(body.level().dimension(), body.blockPosition()),
                profile);
    }

    // Through the one offline-death routine. The record only goes once that has worked.
    private static void killOwner(MinecraftServer server, BodyRecord record, Map<EquipmentSlot, ItemStack> bodyGear,
                                  GlobalPos where, ResolvableProfile profile) {
        NameAndId who = new NameAndId(record.owner(), record.ownerName());
        CompoundTag tag = OfflinePlayerData.load(server, who)
                .orElseThrow(() -> new IllegalStateException("No save file for " + who.name()));
        // Already dead, say after a crash before the record's removal was saved: a second death would restart the lockout
        if (tag.getFloatOr("Health", 1.0F) > 0.0F) {
            // What happened to the body's gear goes onto the save first, so the grave holds what the body really wore
            HolderLookup.Provider registries = server.registryAccess();
            Map<EquipmentSlot, ItemStack> saved = OfflinePlayerData.readGear(tag, registries);
            Map<EquipmentSlot, ItemStack> changed = new EnumMap<>(EquipmentSlot.class);
            GearSync.applyBodyGear(bodyGear, slot -> saved.getOrDefault(slot, ItemStack.EMPTY), changed::put);
            OfflinePlayerData.writeGear(tag, changed, registries);
            OfflineKill.applyOfflineDeath(server, who, tag, where, profile);
        }
        BodyStore.get(server).remove(record.owner());
    }

    private static void takeOver(ServerPlayer player, BodySnapshot snapshot, boolean copyEffects) {
        // The body may stand in another dimension than the player's save if it was moved
        ServerLevel level = player.level().getServer().getLevel(snapshot.dimension());
        if (level != null) {
            Vec3 pos = snapshot.pos();
            player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), snapshot.yRot(), player.getXRot(), true);
        }
        // After the teleport, so nothing it resets undoes these
        player.fallDistance = snapshot.fallDistance();
        player.setRemainingFireTicks(snapshot.fireTicks());
        player.setAirSupply(snapshot.air());
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
