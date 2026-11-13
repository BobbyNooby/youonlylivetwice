package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.ChunkBlockEntityInfoInvoker;
import bobbynooby.dev.mixin.LevelChunkPacketDataInvoker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chunks, their light and their biomes, which are addressed by chunk. Their contents are chunk-local, and an offset is
 * whole chunks, so only the address changes and the payload is shared: nothing changes it once it is built. The one
 * exception is a block entity's tag that holds a position.
 */
final class ChunkRules {
    // A chunk packet's block entity is a private record. Mixin can read one through an interface, but can only build a
    // type it can name, so a changed one is made with the record's own constructor. Null if it can't be found, which
    // drops only the chunks that need it.
    private static final @Nullable MethodHandle NEW_BLOCK_ENTITY = blockEntityConstructor();

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
                withBlockEntitiesShifted(packet.chunkData(), context.frame()), packet.lightData());
    }

    // A block entity is placed by its spot inside the chunk, which stays, so only a tag that holds a position changes.
    // With none changed the very data goes on.
    private static ClientboundLevelChunkPacketData withBlockEntitiesShifted(ClientboundLevelChunkPacketData data, Frame frame) {
        LevelChunkPacketDataInvoker fields = (LevelChunkPacketDataInvoker) data;
        List<Object> entries = fields.yoltBlockEntities();
        List<Object> shifted = null;
        for (int i = 0; i < entries.size(); i++) {
            ChunkBlockEntityInfoInvoker entry = (ChunkBlockEntityInfoInvoker) entries.get(i);
            CompoundTag tag = entry.yoltTag().orElse(null);
            if (tag == null) {
                continue;
            }
            CompoundTag shiftedTag = BlockRules.updateTag(entry.yoltType(), tag, frame);
            if (shiftedTag != tag) {
                if (shifted == null) {
                    shifted = new ArrayList<>(entries);
                }
                shifted.set(i, blockEntity(entry, shiftedTag));
            }
        }
        return shifted == null ? data : LevelChunkPacketDataInvoker.yoltNew(data.getHeightmaps(), fields.yoltBuffer(), shifted);
    }

    private static Object blockEntity(ChunkBlockEntityInfoInvoker entry, CompoundTag tag) {
        if (NEW_BLOCK_ENTITY == null) {
            throw new IllegalStateException("No constructor was found for a chunk's block entity");
        }
        try {
            return NEW_BLOCK_ENTITY.invoke(entry.yoltPackedXZ(), entry.yoltY(), entry.yoltType(), Optional.of(tag));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not build a chunk's block entity", e);
        }
    }

    // Looked up once, at mod start, so the reason it can't be found is logged once
    private static @Nullable MethodHandle blockEntityConstructor() {
        try {
            Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).findConstructor(type,
                    MethodType.methodType(void.class, byte.class, short.class, BlockEntityType.class, Optional.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Yolt.LOGGER.error("Could not find how to build a chunk's block entity, so a chunk with a position in a block "
                    + "entity's tag won't be sent to players with a coordinate offset", e);
            return null;
        }
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
