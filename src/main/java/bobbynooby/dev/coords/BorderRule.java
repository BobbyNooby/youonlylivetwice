package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.InitializeBorderPacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The world border a client with an offset is shown, and the world spawn its compass points at. The border only shows
 * the walls near the player, so a far wall gives no real coordinate away.
 */
final class BorderRule {
    private BorderRule() {}

    static void register() {
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER, BorderRule::border);
        // A change to one part would show the client the real border in its frame, so the whole border goes again
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER, (packet, context) -> border(live(context), context));
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE, (packet, context) -> border(live(context), context));
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE, (packet, context) -> border(live(context), context));
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION, BorderRule::spawn);
    }

    /**
     * The walls the client should have now, or null for the whole world, which goes out as it is: its centre and size
     * are the same in every frame, and shifted they would be the offset itself. A world cut short by max-world-size has
     * walls at its edges, so it goes through the walls rule like any border.
     */
    static BorderWalls.@Nullable Square squareFor(ClientboundInitializeBorderPacket packet, OffsetState state, ServerPlayer player,
                                                 Offset offset) {
        if (packet.getNewCenterX() == 0.0 && packet.getNewCenterZ() == 0.0 && packet.getNewSize() == WorldBorder.MAX_SIZE
                && packet.getLerpTime() == 0L && packet.getNewAbsoluteMaxSize() == MinecraftServer.ABSOLUTE_MAX_WORLD_SIZE) {
            return null;
        }
        int absoluteMax = packet.getNewAbsoluteMaxSize();
        double half = packet.getOldSize() / 2.0;
        Vec3 at = state.lastPosition != null ? state.lastPosition : player.position();
        double near = BorderWalls.near(player.level().getServer().getPlayerList().getViewDistance());
        // The real walls stop at the server's edge. The client's edge is the world's own in every copy, so it never cuts
        // into the client's frame.
        return BorderWalls.forClient(edge(packet.getNewCenterX() - half, absoluteMax), edge(packet.getNewCenterX() + half, absoluteMax),
                edge(packet.getNewCenterZ() - half, absoluteMax), edge(packet.getNewCenterZ() + half, absoluteMax), at.x, at.z, near,
                MinecraftServer.ABSOLUTE_MAX_WORLD_SIZE, offset);
    }

    // Whether the server's edge cuts the border anywhere while it moves, which a plainly shifted copy wouldn't show
    private static boolean cutByTheEdge(ClientboundInitializeBorderPacket packet) {
        double half = Math.max(packet.getOldSize(), packet.getNewSize()) / 2.0;
        int absoluteMax = packet.getNewAbsoluteMaxSize();
        return Math.abs(packet.getNewCenterX()) + half > absoluteMax || Math.abs(packet.getNewCenterZ()) + half > absoluteMax;
    }

    private static double edge(double value, int absoluteMax) {
        return Mth.clamp(value, -absoluteMax, absoluteMax);
    }

    private static ClientboundInitializeBorderPacket live(Context context) {
        return new ClientboundInitializeBorderPacket(context.listener().player.level().getWorldBorder());
    }

    // No field constructor, so the copy goes through the codec; the warnings are kept. A plain border keeps its sizes and
    // its movement, unless the server's edge cuts it. Walls go out still, and the recheck sends them again as a moving
    // border goes.
    private static Packet<?> border(ClientboundInitializeBorderPacket packet, Context context) {
        OffsetState state = context.state();
        Offset offset = context.offset();
        BorderWalls.Square square = squareFor(packet, state, context.listener().player, offset);
        state.lastBorder = square;
        if (square == null) {
            return packet;
        }
        ClientboundInitializeBorderPacket copy = CoordinateOffsets.copy(ClientboundInitializeBorderPacket.STREAM_CODEC, packet);
        InitializeBorderPacketAccessor fields = (InitializeBorderPacketAccessor) copy;
        fields.yoltSetNewAbsoluteMaxSize(MinecraftServer.ABSOLUTE_MAX_WORLD_SIZE);
        if (square.plain() && !cutByTheEdge(packet)) {
            fields.yoltSetNewCenterX(offset.shiftX(packet.getNewCenterX()));
            fields.yoltSetNewCenterZ(offset.shiftZ(packet.getNewCenterZ()));
        } else {
            fields.yoltSetNewCenterX(square.centerX());
            fields.yoltSetNewCenterZ(square.centerZ());
            fields.yoltSetOldSize(square.size());
            fields.yoltSetNewSize(square.size());
            fields.yoltSetLerpTime(0L);
        }
        return copy;
    }

    // A north compass's target is a set distance north of the world's edge, so its Z is the same for everyone and
    // shifted it would be the offset itself; its X is the player's own. Any other spawn is a real spot.
    private static Packet<?> spawn(ClientboundSetDefaultSpawnPositionPacket packet, Context context) {
        LevelData.RespawnData spawn = packet.respawnData();
        BlockPos pos = spawn.pos();
        Offset offset = context.offset();
        BlockPos shifted = CoordinateOffsets.isNorthTarget(pos) ? new BlockPos(pos.getX() + offset.x(), pos.getY(), pos.getZ())
                : offset.shift(pos);
        return new ClientboundSetDefaultSpawnPositionPacket(LevelData.RespawnData.of(spawn.dimension(), shifted, spawn.yaw(),
                spawn.pitch()));
    }
}
