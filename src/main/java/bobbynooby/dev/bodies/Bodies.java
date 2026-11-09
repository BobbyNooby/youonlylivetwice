package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Later;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.mixin.MobAccessor;
import bobbynooby.dev.mixin.NearestAttackableTargetGoalAccessor;
import bobbynooby.dev.offline.OfflineKill;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

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
            Guard.run(() -> onLogout(player), "Could not leave a body for {}", player.nameAndId().name());
        });
        // JOIN runs on the server thread once the player is in the world
        ServerPlayerEvents.JOIN.register(player -> {
            // Thrown out of here, it would leave the player half joined
            Guard.run(() -> onLogin(player), "Could not hand the body back to {}", player.nameAndId().name());
        });
        // These run for each entity of a chunk in turn, so a throw would break off the rest. The stop also unloads
        // chunks before it saves the world, and a throw there would skip the save.
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Guard.run(() -> onBodyUnload(entity), "Could not save the state of the body {} as it unloaded",
                    entity.getUUID());
        });
        ServerEntityEvents.ENTITY_LOAD.register(Bodies::onBodyLoad);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob) {
                Guard.run(() -> addBodyGoal(mob), "Could not let {} hunt bodies", mob.getUUID());
            }
        });
        // Fires inside die() for every death, after the death drops, which the body never has. A throw would break
        // off that death.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Guard.run(() -> onBodyDeath(entity), "Could not handle the death of {}", entity.getUUID());
        });
        ServerLifecycleEvents.SERVER_STARTED.register(Bodies::restorePlayerBodies);
        ServerLifecycleEvents.SERVER_STOPPING.register(Bodies::snapshotBodies);
        // Every save too, autosaves included, so after a crash a player body comes back as last saved, not as it logged out
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> snapshotBodies(server));
        BodyExpiry.init();
    }

    /** The new body, or empty when this logout gets none. */
    public static Optional<Entity> onLogout(ServerPlayer player) {
        YoltConfig.BodiesSection cfg = Settings.get().bodies;
        // Dead players get none: the lockout's kick after a death comes through here too
        if (!Settings.on(c -> c.bodies.enabled) || player.isDeadOrDying() || player.isSpectator()
                || DeathLockout.refusal(player.getUUID()) != null) {
            return Optional.empty();
        }
        MinecraftServer server = player.level().getServer();
        BodyStore store = BodyStore.get(server);
        // Out again before the old body was taken back: it goes, so there is never a second one
        store.byOwner(player.getUUID()).ifPresent(old -> discard(server, old));

        LivingEntity body = cfg.type == YoltConfig.BodyType.PLAYER
                ? PlayerBody.spawn(player, cfg)
                : MannequinBodies.spawn(player, cfg);
        // A player body comes back from its record after a restart, so the record keeps its skin
        PropertyMap skin = body instanceof PlayerBody playerBody
                ? playerBody.getGameProfile().properties()
                : PropertyMap.EMPTY;
        store.put(new BodyRecord(player.getUUID(), player.nameAndId().name(), body.getUUID(), cfg.type,
                System.currentTimeMillis(), false, BodySnapshot.of(body), skin));
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
        if (entityOf(server, record) instanceof LivingEntity body) {
            snapshot = BodySnapshot.of(body);
            bodyAlive = !body.isDeadOrDying();
            // The record goes first, so the discard's unload no longer counts this as a body
            store.remove(record.owner());
            body.discard();
        } else if (record.type() == YoltConfig.BodyType.PLAYER) {
            // A player body isn't saved with its chunk, so it can't load later: the record is all there is
            snapshot = record.snapshot();
            bodyAlive = snapshot.health() > 0.0F;
            store.remove(record.owner());
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
                    || entityOf(server, record) != null) {
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
                if (!record.pendingRemoval() && entityOf(server, record) instanceof LivingEntity body
                        && !body.isDeadOrDying()) {
                    store.put(record.withSnapshot(BodySnapshot.of(body)));
                }
            }, "Could not save the state of the body of {}", record.ownerName());
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

    /**
     * Lets a hostile mob hunt bodies, at the priority of its own player goal. The goal only touches the mob's own
     * target list, not the entity list, so this is safe during a load. A mob loads again each time its chunk starts
     * ticking again, so it never gets a second goal.
     */
    public static void addBodyGoal(Mob mob) {
        if (!Settings.active() || !BodyTargetGoal.HUNTERS.contains(mob.getType())) {
            return;
        }
        GoalSelector targets = ((MobAccessor) mob).yoltTargetSelector();
        // Phantoms and guardians have no such goal, and pick players with a goal of their own at 1
        int priority = 1;
        TargetingConditions playerConditions = null;
        for (WrappedGoal wrapped : targets.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof BodyTargetGoal) {
                return;
            }
            if (playerConditions == null && wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> goal
                    && ((NearestAttackableTargetGoalAccessor) goal).yoltTargetType() == Player.class) {
                priority = wrapped.getPriority();
                playerConditions = ((NearestAttackableTargetGoalAccessor) goal).yoltTargetConditions();
            }
        }
        targets.addGoal(priority, new BodyTargetGoal(mob, playerConditions));
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
        // No retry: a failed death leaves the save as it was, so nothing is lost, and the owner's next login
        // takes over the body's last saved state instead. A retry would also start from the wiped tag.
        Guard.run(() -> killOwner(server, record, body), "The body of {} died, but they could not be killed",
                record.ownerName());
    }

    /**
     * Offline /yolt kill of a player whose body still stands. A loaded body is killed and its death does the rest;
     * one in an unloaded chunk dies at its last saved spot, and goes when its chunk next loads.
     * Throws if the owner could not be killed.
     */
    public static OfflineKill.KillResult killBody(MinecraftServer server, BodyRecord record) {
        // A body still dying from a death that failed to kill its owner counts as gone
        if (entityOf(server, record) instanceof LivingEntity body
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
                GraveItems.profileOf(record.who()));
        return OfflineKill.KillResult.KILLED_OFFLINE;
    }

    /** True for anything we spawned as a body: it wears copies of its owner's saved gear, so its death drops nothing. */
    public static boolean dropsNothing(LivingEntity entity) {
        return Settings.active() && isTaggedBody(entity);
    }

    /** True when hits should wear down a body's armour, as they do a player's. */
    public static boolean wearsArmour(LivingEntity entity) {
        return Settings.on(c -> c.bodies.armourWears) && isBody(entity);
    }

    /**
     * Bodies stay out of portals: in another dimension a body is hard to keep track of, and nothing handles that yet.
     * A vehicle carrying one stays out too, since vanilla takes the passengers along without asking them.
     */
    public static boolean blocksPortal(Entity entity) {
        if (!Settings.active()) {
            return false;
        }
        if (isTaggedBody(entity)) {
            return true;
        }
        // Every passenger, not only the first: a boat holds two, and a passenger can carry passengers of its own
        for (Entity passenger : entity.getIndirectPassengers()) {
            if (isTaggedBody(passenger)) {
                return true;
            }
        }
        return false;
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

    /** The record's body in any dimension, or null when it isn't loaded. */
    public static @Nullable Entity entityOf(MinecraftServer server, BodyRecord record) {
        return server.overworld().getEntityInAnyDimension(record.bodyEntity());
    }

    // Any dimension, in case an admin moved it. One in an unloaded chunk isn't found, but once the record
    // is replaced it no longer counts as a body.
    private static void discard(MinecraftServer server, BodyRecord old) {
        Entity entity = entityOf(server, old);
        if (entity != null) {
            entity.discard();
        }
    }

    // The body's gear as it is now, where it fell, and its skin for the grave head. A player body's own profile
    // has the body's id, and a revive head needs the owner's.
    private static void killOwner(MinecraftServer server, BodyRecord record, LivingEntity body) {
        ResolvableProfile profile = body instanceof PlayerBody playerBody ? playerBody.ownerProfile()
                : body instanceof Avatar avatar ? avatar.getProfile()
                : GraveItems.profileOf(record.who());
        killOwner(server, record, BodySnapshot.of(body).gear(), GlobalPos.of(body.level().dimension(), body.blockPosition()),
                profile);
    }

    // Through the one offline-death routine. The record only goes once that has worked.
    private static void killOwner(MinecraftServer server, BodyRecord record, Map<EquipmentSlot, ItemStack> bodyGear,
                                  GlobalPos where, ResolvableProfile profile) {
        NameAndId who = record.who();
        CompoundTag tag = OfflinePlayerData.loadOrThrow(server, who);
        // Already dead, say after a crash before the record's removal was saved: a second death would restart the lockout
        if (!OfflinePlayerData.isDead(tag)) {
            // What happened to the body's gear goes onto the save first, so the grave holds what the body really wore
            OfflinePlayerData.applyBodyGear(tag, bodyGear, server.registryAccess());
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
        snapshot.applyTo(player, copyEffects);
        GearSync.applyBodyGear(snapshot.gear(), player::getItemBySlot, player::setItemSlot);
    }
}
