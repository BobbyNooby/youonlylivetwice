package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.LastGround;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/** Hands a body's state back to its owner when they log in. */
public final class BodyLogin {
    private BodyLogin() {
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
        // The body goes, and its last ground with it: the player notes their own from here
        LastGround.forget(record.owner());
        BodySnapshot snapshot;
        boolean bodyAlive;
        if (Bodies.entityOf(server, record) instanceof LivingEntity body) {
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
