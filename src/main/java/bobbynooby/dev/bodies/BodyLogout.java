package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;

/** Leaves a body behind when a player logs out. */
public final class BodyLogout {
    private BodyLogout() {
    }

    /** The new body, or empty when this logout gets none. */
    public static Optional<Entity> onLogout(ServerPlayer player) {
        YoltConfig.BodiesSection cfg = Settings.get().bodies;
        // Dead players get none: the lockout's kick after a death comes through here too
        if (!Settings.on(c -> c.bodies.enabled) || player.isDeadOrDying() || player.isSpectator()
                || DeathLockout.refusal(player.getUUID(), player.nameAndId().name()) != null) {
            return Optional.empty();
        }
        MinecraftServer server = player.level().getServer();
        BodyStore store = BodyStore.get(server);
        // Out again before the old body was taken back: it goes, so there is never a second one
        store.byOwner(player.getUUID()).ifPresent(old -> discard(server, old));

        LivingEntity body = cfg.type == YoltConfig.BodyType.PLAYER
                ? PlayerBody.spawn(player, cfg)
                : MannequinBody.spawn(player, cfg);
        // A player body comes back from its record after a restart, so the record keeps its skin
        PropertyMap skin = body instanceof PlayerBody playerBody
                ? playerBody.getGameProfile().properties()
                : PropertyMap.EMPTY;
        store.put(new BodyRecord(player.getUUID(), player.nameAndId().name(), body.getUUID(), cfg.type,
                System.currentTimeMillis(), false, BodySnapshot.of(body), skin));
        return Optional.of(body);
    }

    // Any dimension, in case an admin moved it. One in an unloaded chunk isn't found, but once the record
    // is replaced it no longer counts as a body.
    private static void discard(MinecraftServer server, BodyRecord old) {
        Entity entity = Bodies.entityOf(server, old);
        if (entity != null) {
            entity.discard();
        }
    }
}
