package bobbynooby.dev.coords;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunks, their light and their biomes, which are addressed by chunk. Their contents are chunk-local, and an offset is
 * whole chunks, so only the address changes and the payload is shared: nothing changes it once it is built.
 */
final class ChunkRules {
    private ChunkRules() {}

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
}
