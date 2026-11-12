package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.InitializeBorderPacketAccessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The world border a client with an offset is shown. It only shows the walls near the player, so a far wall gives no
 * real coordinate away.
 */
final class BorderRule {
    private BorderRule() {}

    static void register() {
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER, BorderRule::border);
        // A change to one part would show the client the real border in its frame, so the whole border goes again
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER, (packet, context) -> border(live(context), context));
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE, (packet, context) -> border(live(context), context));
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE, (packet, context) -> border(live(context), context));
    }

    /**
     * The walls the client should have now, or null for the whole world, which goes out as it is: its centre and size
     * are the same in every frame, and shifted they would be the offset itself.
     */
    static BorderWalls.@Nullable Square squareFor(ClientboundInitializeBorderPacket packet, OffsetState state, ServerPlayer player,
                                                 Offset offset) {
        if (packet.getNewCenterX() == 0.0 && packet.getNewCenterZ() == 0.0 && packet.getNewSize() == WorldBorder.MAX_SIZE
                && packet.getLerpTime() == 0L) {
            return null;
        }
        int absoluteMax = packet.getNewAbsoluteMaxSize();
        double half = packet.getOldSize() / 2.0;
        Vec3 at = state.lastPosition != null ? state.lastPosition : player.position();
        double near = BorderWalls.near(player.level().getServer().getPlayerList().getViewDistance());
        // The real walls stop at the world's edge, as the client's do
        return BorderWalls.forClient(edge(packet.getNewCenterX() - half, absoluteMax), edge(packet.getNewCenterX() + half, absoluteMax),
                edge(packet.getNewCenterZ() - half, absoluteMax), edge(packet.getNewCenterZ() + half, absoluteMax), at.x, at.z, near,
                absoluteMax, offset);
    }

    private static double edge(double value, int absoluteMax) {
        return Mth.clamp(value, -absoluteMax, absoluteMax);
    }

    private static ClientboundInitializeBorderPacket live(Context context) {
        return new ClientboundInitializeBorderPacket(context.listener().player.level().getWorldBorder());
    }

    // No field constructor, so the copy goes through the codec; the warnings and the world's edge are kept. A plain border
    // keeps its sizes and its movement. Walls go out still, and the recheck sends them again as a moving border goes.
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
        if (square.plain()) {
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
}
