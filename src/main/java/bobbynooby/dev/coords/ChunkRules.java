package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Chunks, their light and their biomes, which are addressed by chunk. Their contents are chunk-local, and an offset is
 * whole chunks, so only the address changes and the payload is shared: nothing changes it once it is built.
 */
final class ChunkRules {
    private ChunkRules() {}

    static void register() {
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT, ChunkRules::chunk);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_LIGHT_UPDATE, ChunkRules::light);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_CHUNKS_BIOMES, ChunkRules::biomes);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK, ChunkRules::forget);
    }

    /** Notes the chunks a packet gives the client or takes away, in real coordinates, whatever the frame. */
    static void keepHeld(OffsetState state, Packet<?> packet) {
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk -> state.heldChunks.add(ChunkPos.pack(chunk.x(), chunk.z()));
            case ClientboundForgetLevelChunkPacket forget -> state.heldChunks.remove(forget.pos().pack());
            case ClientboundBundlePacket bundle -> bundle.subPackets().forEach(part -> keepHeld(state, part));
            default -> {
            }
        }
    }

    private static Packet<?> chunk(ClientboundLevelChunkWithLightPacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundLevelChunkWithLightPacket(offset.shiftChunkX(packet.x()), offset.shiftChunkZ(packet.z()),
                packet.chunkData(), packet.lightData());
    }

    private static Packet<?> light(ClientboundLightUpdatePacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundLightUpdatePacket(offset.shiftChunkX(packet.x()), offset.shiftChunkZ(packet.z()), packet.lightData());
    }

    private static Packet<?> biomes(ClientboundChunksBiomesPacket packet, Context context) {
        Offset offset = context.offset();
        return new ClientboundChunksBiomesPacket(packet.chunkBiomeData().stream()
                .map(data -> new ClientboundChunksBiomesPacket.ChunkBiomeData(offset.shift(data.pos()), data.buffer()))
                .toList());
    }

    /** Forgets every chunk the client holds. Each goes back through the connection, so it is sent in the current frame. */
    static void forgetHeld(OffsetState state, ServerGamePacketListenerImpl listener) {
        for (long chunk : List.copyOf(state.heldChunks)) {
            listener.send(new ClientboundForgetLevelChunkPacket(ChunkPos.unpack(chunk)));
        }
    }

    // Only for a chunk the client holds. A dimension change forgets the old level's chunks after the new frame starts,
    // which would show where they lie from the player's new spot: from the End, whose platform gives its frame away,
    // that is the stronghold.
    private static @Nullable Packet<?> forget(ClientboundForgetLevelChunkPacket packet, Context context) {
        if (!context.state().heldChunks.contains(packet.pos().pack())) {
            return null;
        }
        return new ClientboundForgetLevelChunkPacket(context.offset().shift(packet.pos()));
    }
}
