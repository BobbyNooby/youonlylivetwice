package bobbynooby.dev.bodies;

import bobbynooby.dev.core.Guard;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The wiring point for bodies: registers the hooks that leave, hand back and kill for a body. */
public final class Bodies {
    /** Marks the entities we made, so a decorative mannequin is never taken for a body. */
    public static final String TAG = "yolt_body";

    private Bodies() {
    }

    public static void init() {
        // LEAVE runs on the server thread before vanilla saves the player; DISCONNECT can run on the network thread
        ServerPlayerEvents.LEAVE.register(player -> {
            // A failure here must not stop the logout save that follows
            Guard.run(() -> BodyLogout.onLogout(player), "Could not leave a body for {}", player.nameAndId().name());
        });
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
        ServerLifecycleEvents.SERVER_STARTED.register(BodyRestart::restorePlayerBodies);
        ServerLifecycleEvents.SERVER_STOPPING.register(BodyRestart::snapshotBodies);
        // Every save too, autosaves included, so after a crash a player body comes back as last saved, not as it logged out
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> BodyRestart.snapshotBodies(server));
        BodyExpiry.init();
    }

    /** The record's body in any dimension, or null when it isn't loaded. */
    static @Nullable Entity entityOf(MinecraftServer server, BodyRecord record) {
        return server.overworld().getEntityInAnyDimension(record.bodyEntity());
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
