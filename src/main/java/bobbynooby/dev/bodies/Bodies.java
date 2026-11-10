package bobbynooby.dev.bodies;

import bobbynooby.dev.core.Guard;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import org.jspecify.annotations.Nullable;

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
        ServerEntityEvents.ENTITY_LOAD.register(BodyChunks::onBodyLoad);
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
}
