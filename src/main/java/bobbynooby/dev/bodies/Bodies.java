package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.GravePlacement;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.graves.LastGround;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The wiring point for bodies: registers the hooks that leave, hand back and kill for a body. */
public final class Bodies {
    /** Marks the entities we made, so a decorative mannequin is never taken for a body. */
    public static final String TAG = "yolt_body";

    // Every loaded entity that is or may be a body, so noting where bodies stand doesn't look up the entity of every
    // record, most of which lie in unloaded chunks. An entity is here from its load event to its unload event, which is
    // exactly while its level finds it by its UUID. Identity, since an entity's equals is its network id, which a
    // respawn hands on. Server thread only.
    private static final Set<LivingEntity> LOADED = Collections.newSetFromMap(new IdentityHashMap<>());

    private Bodies() {}

    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register(Bodies::onLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> LOADED.remove(entity));
        // Static, so it would outlive the server in a JVM that starts another one
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
        // LEAVE runs on the server thread before vanilla saves the player, so the save holds what this changes
        ServerPlayerEvents.LEAVE.register(Bodies::leaveBody);
        // JOIN runs on the server thread once the player is in the world
        ServerPlayerEvents.JOIN.register(player -> {
            // Thrown out of here, it would leave the player half joined
            Guard.run(() -> BodyLogin.onLogin(player), "Could not hand the body back to {}", player.nameAndId().name());
        });
        // These run for each entity of a chunk in turn, so a throw would break off the rest. The stop also unloads
        // chunks before it saves the world, and a throw there would skip the save.
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Guard.run(() -> BodyChunks.onBodyUnload(entity), "Could not save the state of the body {} as it unloaded",
                    entity.getUUID());
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            Guard.run(() -> BodyChunks.onBodyLoad(entity, level), "Could not check the body {} as it loaded",
                    entity.getUUID());
        });
        BodyMobs.init();
        // Fires inside die() for every death, after the death drops, which the body never has. A throw would break
        // off that death.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Guard.run(() -> BodyDeath.onBodyDeath(entity), "Could not handle the death of {}", BodyDeath.logName(entity));
        });
        // Where each loaded body stands, so its owner's grave goes back there if the body is pushed into the void.
        // With bodies switched off too, like the death above.
        ServerTickEvents.END_SERVER_TICK.register(Bodies::noteGround);
        ServerLifecycleEvents.SERVER_STARTED.register(BodyRestart::restorePlayerBodies);
        // Ahead of the snapshot, so the new bodies are in it
        ServerLifecycleEvents.SERVER_STOPPING.register(Bodies::leaveBodiesAtStop);
        ServerLifecycleEvents.SERVER_STOPPING.register(BodyRestart::snapshotBodies);
        // Every save too, autosaves included, so after a crash a player body comes back as last saved, not as it logged out
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> BodyRestart.snapshotBodies(server));
        BodyExpiry.init();
    }

    /** What a logout leaves: the body, with the player's last ground kept for it. */
    public static void leaveBody(ServerPlayer player) {
        // A failure here must not stop the logout save that follows
        Guard.run(() -> BodyLogout.onLogout(player), "Could not leave a body for {}", player.nameAndId().name());
        Guard.run(() -> keepGroundForBody(player), "Could not keep the last ground of {} for their body",
                player.nameAndId().name());
    }

    // The stop's kick most likely never gets as far as LEAVE, so everyone still online gets their body here. A LEAVE
    // that does come after replaces it through the logout's own old-record path.
    private static void leaveBodiesAtStop(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            leaveBody(player);
        }
    }

    // A body left behind keeps the player's last ground until it stands somewhere itself, so a logout in mid-fall still
    // has one. With no body it goes.
    private static void keepGroundForBody(ServerPlayer player) {
        BodyStore store = BodyStore.get(player.level().getServer());
        if (store.byOwner(player.getUUID()).filter(record -> !record.pendingRemoval()).isEmpty()) {
            LastGround.forget(player.getUUID());
        }
    }

    // A body carries the tag from its spawn on. One whose tag an admin took off is still found by its record, as
    // entityOf finds it.
    private static void onLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (entity.entityTags().contains(TAG)) {
            LOADED.add(living);
            return;
        }
        // Runs for each entity of a chunk in turn, so a throw would break off the rest
        Guard.run(() -> {
            if (Settings.active() && BodyStore.get(level.getServer()).byEntity(entity.getUUID()).isPresent()) {
                LOADED.add(living);
            }
        }, "Could not check whether {} is a body as it loaded", entity.getUUID());
    }

    // Only the loaded bodies: one in an unloaded chunk stands nowhere it could note
    private static void noteGround(MinecraftServer server) {
        // Only a grave reads it
        if (!Settings.on(c -> c.graves.enabled) || LOADED.isEmpty()) {
            return;
        }
        BodyStore store = BodyStore.get(server);
        for (LivingEntity body : LOADED) {
            for (BodyRecord record : store.allByEntity(body.getUUID())) {
                // A pending record's body was taken over already
                if (!record.pendingRemoval()) {
                    LastGround.update(record.owner(), body);
                }
            }
        }
    }

    /** The record's body in any dimension, or null when it isn't loaded. */
    static @Nullable Entity entityOf(MinecraftServer server, BodyRecord record) {
        return server.overworld().getEntityInAnyDimension(record.bodyEntity());
    }

    /**
     * For a body that goes without its owner taking it over: what fills a slot that was empty at its logout is real, as
     * only a dispenser fills one, so it drops for good where the body stands. Each item is logged, so one that can't be
     * dropped is at least in the log. With died, the body may lie in the lava or over the void that killed it, so the
     * items go where a grave's would drop instead: the nearest spot where they last.
     */
    static void dropAddedGear(MinecraftServer server, BodyRecord record, BodySnapshot now, boolean died) {
        Map<EquipmentSlot, ItemStack> added = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : GearSync.SLOTS) {
            ItemStack item = now.gear().getOrDefault(slot, ItemStack.EMPTY);
            if (record.emptyAtLogout().contains(slot) && !item.isEmpty()) {
                added.put(slot, item.copy());
            }
        }
        try {
            List<ItemStack> items = List.copyOf(added.values());
            if (died) {
                dropWhereTheyLast(server, record.owner(), now, items);
            } else {
                dropForGood(server, now.dimension(), now.pos(), items);
            }
            String where = died ? "where a grave's items would, at the nearest spot where they last" : "where the body stood";
            added.forEach((slot, item) -> Yolt.LOGGER.warn("{}'s body got an item in slot {} after they logged out, "
                    + "and the body goes without them taking it over, so the item drops {}: {}",
                    record.ownerName(), slot.getName(), where, Stacks.saved(item, server.registryAccess())));
        } catch (RuntimeException e) {
            added.forEach((slot, item) -> Yolt.LOGGER.warn("{}'s body got an item in slot {} after they logged out, "
                    + "and the body goes without them taking it over, but the item could not be dropped: {}",
                    record.ownerName(), slot.getName(), Stacks.saved(item, server.registryAccess()), e));
        }
    }

    // At the spot a grave's drops take for a death where the body lies, through the graves' own drop
    private static void dropWhereTheyLast(MinecraftServer server, UUID owner, BodySnapshot body, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        ServerLevel found = server.getLevel(body.dimension());
        ServerLevel level = found != null ? found : server.overworld();
        BlockPos spot = GravePlacement.dropSpot(level, BlockPos.containing(body.pos()),
                LastGround.in(owner, level.dimension()).orElse(null), GraveStore.get(server));
        Graves.dropForGood(level, spot, items);
    }

    /**
     * Drops a body's items that have nowhere else to go where it stands, never to despawn, as a grave removal's drops
     * are. Every one or none: if one can't be added, the others are taken back and this throws. Returns the undo.
     */
    static Runnable dropForGood(MinecraftServer server, ResourceKey<Level> dimension, Vec3 pos, List<ItemStack> items) {
        List<Entity> added = new ArrayList<>();
        Runnable undo = () -> added.forEach(Entity::discard);
        if (items.isEmpty()) {
            return undo;
        }
        ServerLevel found = server.getLevel(dimension);
        ServerLevel level = found != null ? found : server.overworld();
        // Entities added to a chunk that isn't loaded sit in limbo until it is
        level.getChunkAt(BlockPos.containing(pos));
        try {
            for (ItemStack item : items) {
                // Without vanilla's random push, which could slide it off the body's block into lava or the void
                ItemEntity entity = new ItemEntity(level, pos.x, pos.y, pos.z, item, 0.0, 0.0, 0.0);
                entity.setUnlimitedLifetime();
                // Held where the body stood unless a block is right under the item itself: past a ledge, beside a
                // fence post or down a ladder it could fall into lava or the void. Blocks only, since an entity under
                // it can move away.
                entity.setNoGravity(level.noBlockCollision(entity, entity.getBoundingBox().move(0.0, -1.0E-6, 0.0)));
                // Listed before the add, so the undo still takes it back if a load listener throws once it is in
                added.add(entity);
                if (!level.addFreshEntity(entity)) {
                    throw new IllegalStateException("Could not drop " + item + " at " + pos);
                }
            }
        } catch (RuntimeException e) {
            undo.run();
            throw e;
        }
        return undo;
    }
}
