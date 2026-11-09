package bobbynooby.dev.bodies;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

import java.util.Optional;

/** Leaves a body behind when a player logs out. */
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
}
