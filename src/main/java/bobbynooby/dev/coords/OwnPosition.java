package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.PlayerLookAtPacketAccessor;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** The player's own place: the login, respawns, teleports, the chunk ring's centre and the moves the client sends back. */
final class OwnPosition {
    private OwnPosition() {}

    static void register() {
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_LOGIN, OwnPosition::login);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_RESPAWN, OwnPosition::respawn);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_PLAYER_POSITION, OwnPosition::teleport);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_PLAYER_LOOK_AT, OwnPosition::lookAt);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_MOVE_VEHICLE, OwnPosition::vehicleTo);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_CENTER, OwnPosition::chunkCacheCenter);
        Incoming.translate(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS, OwnPosition::movePos);
        Incoming.translate(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT, OwnPosition::movePosRot);
        Incoming.translate(GamePacketTypes.SERVERBOUND_ACCEPT_TELEPORTATION, OwnPosition::acceptTeleport);
        Incoming.translate(GamePacketTypes.SERVERBOUND_MOVE_VEHICLE, OwnPosition::vehicleMoved);
    }

    /**
     * Notes where a teleport puts the client, in real coordinates, whatever the frame, one in a bundle too. The first one
     * after a respawn also names the accept from which the client's moves are read in the new frame.
     */
    static void keepTeleport(OffsetState state, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(part -> keepTeleport(state, part));
            return;
        }
        if (!(packet instanceof ClientboundPlayerPositionPacket teleport)) {
            return;
        }
        Vec3 to = teleport.change().position();
        Set<Relative> relatives = teleport.relatives();
        Vec3 before = state.lastPosition != null ? state.lastPosition : Vec3.ZERO;
        state.lastPosition = new Vec3(relatives.contains(Relative.X) ? before.x + to.x : to.x,
                relatives.contains(Relative.Y) ? before.y + to.y : to.y, relatives.contains(Relative.Z) ? before.z + to.z : to.z);
        state.frames.updateAndGet(frames -> frames.switchAt() == Frames.AT_NEXT_TELEPORT
                ? new Frames(frames.out(), frames.in(), teleport.id()) : frames);
    }

    // The first game packet of every login, re-configuration included, so a new frame starts here, at the exact
    // point in the stream: everything after it is in the new offset. The seed and the rest go out as they are.
    private static Packet<?> login(ClientboundLoginPacket packet, Context context) {
        ServerPlayer player = context.listener().player;
        Offset offset = CoordinateOffsets.frameOffset(player, player.position());
        OffsetState state = context.state();
        CommonPlayerSpawnInfo spawn = packet.commonPlayerSpawnInfo();
        state.frames.set(new Frames(offset, offset, Frames.NO_SWITCH));
        state.dimension = spawn.dimension();
        state.heldChunks.clear();
        state.lastPosition = player.position();
        CommonPlayerSpawnInfo shifted = withDeathShifted(spawn, offset);
        if (shifted == spawn) {
            return packet;
        }
        return new ClientboundLoginPacket(packet.playerId(), packet.hardcore(), packet.levels(), packet.maxPlayers(),
                packet.chunkRadius(), packet.simulationDistance(), packet.reducedDebugInfo(), packet.showDeathScreen(),
                packet.doLimitedCrafting(), shifted, packet.onlineMode(), packet.enforcesSecureChat());
    }

    // A respawn, the way back from the End and every dimension change start a new frame here, anchored where the player
    // arrives. A Respawn packet sent from anywhere else has no spot to go by, so its frame is a random one.
    private static Packet<?> respawn(ClientboundRespawnPacket packet, Context context) {
        OffsetState state = context.state();
        Vec3 anchor = state.nextAnchor;
        state.nextAnchor = null;
        // Admin view is read from the player the connection holds. During a respawn that is the one who died, as the new
        // one only gets the saved flag once the respawn is done.
        ServerPlayer player = context.listener().player;
        Offset offset = CoordinateOffsets.frameOffset(player, anchor);
        CommonPlayerSpawnInfo spawn = packet.commonPlayerSpawnInfo();
        if (!spawn.dimension().equals(state.dimension)) {
            // The client starts a new level, with none of the old one's chunks
            state.heldChunks.clear();
        } else if (!offset.equals(state.frames.get().out())) {
            // The client keeps its level and its chunks, which the new frame would put in the wrong place, and vanilla
            // forgets none of them for a player who died. Each forget takes its chunk out of the held set.
            ChunkRules.forgetHeld(state, context.listener());
        }
        state.dimension = spawn.dimension();
        state.lastPosition = anchor != null ? anchor : player.position();
        // What the client sends stays in the old frame until it accepts the teleport that comes next
        state.frames.updateAndGet(frames -> new Frames(offset, frames.in(), Frames.AT_NEXT_TELEPORT));
        CommonPlayerSpawnInfo shifted = withDeathShifted(spawn, offset);
        return shifted == spawn ? packet : new ClientboundRespawnPacket(shifted, packet.dataToKeep());
    }

    // Shifted even when it is in another dimension, so no real number goes out; the needle spins there anyway
    private static CommonPlayerSpawnInfo withDeathShifted(CommonPlayerSpawnInfo spawn, Offset offset) {
        if (offset.isZero() || spawn.lastDeathLocation().isEmpty()) {
            return spawn;
        }
        return new CommonPlayerSpawnInfo(spawn.dimensionType(), spawn.dimension(), spawn.seed(), spawn.gameType(),
                spawn.previousGameType(), spawn.isDebug(), spawn.isFlat(),
                spawn.lastDeathLocation().map(death -> offset.shift(death)), spawn.portalCooldown(), spawn.seaLevel());
    }

    // A relative axis is a step from where the client is, so it stays as it is. The movement is a velocity.
    private static Packet<?> teleport(ClientboundPlayerPositionPacket packet, Context context) {
        Set<Relative> relatives = packet.relatives();
        boolean relativeX = relatives.contains(Relative.X);
        boolean relativeZ = relatives.contains(Relative.Z);
        if (relativeX && relativeZ) {
            return packet;
        }
        PositionMoveRotation change = packet.change();
        Vec3 to = change.position();
        Offset offset = context.offset();
        Vec3 shifted = new Vec3(relativeX ? to.x : offset.shiftX(to.x), to.y, relativeZ ? to.z : offset.shiftZ(to.z));
        return new ClientboundPlayerPositionPacket(packet.id(),
                new PositionMoveRotation(shifted, change.deltaMovement(), change.yRot(), change.xRot()), relatives);
    }

    // No getters and no field constructor for the entity form, so the copy goes through the codec. Its x and z are also
    // where the client looks when the entity isn't there.
    private static Packet<?> lookAt(ClientboundPlayerLookAtPacket packet, Context context) {
        ClientboundPlayerLookAtPacket copy = CoordinateOffsets.copy(ClientboundPlayerLookAtPacket.STREAM_CODEC, packet);
        PlayerLookAtPacketAccessor fields = (PlayerLookAtPacketAccessor) copy;
        Offset offset = context.offset();
        fields.yoltSetX(offset.shiftX(fields.yoltX()));
        fields.yoltSetZ(offset.shiftZ(fields.yoltZ()));
        return copy;
    }

    private static Packet<?> vehicleTo(ClientboundMoveVehiclePacket packet, Context context) {
        PositionAndRotation to = packet.movingTo();
        return new ClientboundMoveVehiclePacket(PositionAndRotation.of(context.offset().shift(to.position()), to.yRot(), to.xRot()));
    }

    private static Packet<?> chunkCacheCenter(ClientboundSetChunkCacheCenterPacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundSetChunkCacheCenterPacket(offset.shiftChunkX(packet.getX()), offset.shiftChunkZ(packet.getZ()));
    }

    // Before the server's own clamp to the world's edge, which would otherwise cut a far client position short
    private static Packet<?> movePos(ServerboundMovePlayerPacket.Pos packet, Context context) {
        Offset offset = context.offset();
        return new ServerboundMovePlayerPacket.Pos(packet.getX(0.0) - offset.x(), packet.getY(0.0), packet.getZ(0.0) - offset.z(),
                packet.isOnGround(), packet.horizontalCollision());
    }

    private static Packet<?> movePosRot(ServerboundMovePlayerPacket.PosRot packet, Context context) {
        Offset offset = context.offset();
        return new ServerboundMovePlayerPacket.PosRot(packet.getX(0.0) - offset.x(), packet.getY(0.0), packet.getZ(0.0) - offset.z(),
                packet.getYRot(0.0F), packet.getXRot(0.0F), packet.isOnGround(), packet.horizontalCollision());
    }

    // Untranslated, the accepted spot would be a long way off, and the server would call it moving too quickly
    private static Packet<?> acceptTeleport(ServerboundAcceptTeleportationPacket packet, Context context) {
        Offset offset = context.offset();
        return new ServerboundAcceptTeleportationPacket(packet.id(), packet.x() - offset.x(), packet.y(), packet.z() - offset.z(),
                packet.yRot(), packet.xRot());
    }

    private static Packet<?> vehicleMoved(ServerboundMoveVehiclePacket packet, Context context) {
        PositionAndRotation to = packet.movingTo();
        return new ServerboundMoveVehiclePacket(PositionAndRotation.of(context.offset().negate().shift(to.position()), to.yRot(),
                to.xRot()), packet.onGround());
    }
}
