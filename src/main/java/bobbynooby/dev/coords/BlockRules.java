package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.BlockEntityDataPacketInvoker;
import bobbynooby.dev.mixin.SectionBlocksUpdatePacketAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddTransientBlockPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Blocks, block entities and the packets about one block, and which block entities' update tags can hold a position. */
public final class BlockRules {
    // Filled at mod start, before any server thread runs, and only read after
    private static final Map<BlockEntityType<?>, Coverage> BLOCK_ENTITIES = new HashMap<>();

    private BlockRules() {}

    static void register() {
        classifyBlockEntities();
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE, BlockRules::blockUpdate);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE, BlockRules::sectionUpdate);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_BLOCK_ENTITY_DATA, BlockRules::blockEntityData);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_BLOCK_EVENT, BlockRules::blockEvent);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_BLOCK_DESTRUCTION, BlockRules::blockDestruction);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_ADD_TRANSIENT_BLOCK, BlockRules::transientBlock);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_LEVEL_EVENT, BlockRules::levelEvent);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_OPEN_SIGN_EDITOR, BlockRules::signEditor);
    }

    /** Every block entity type: TRANSLATED when its update tag goes through the NBT rule, as it can hold a position. */
    public static Map<BlockEntityType<?>, Coverage> blockEntityCoverage() {
        return Collections.unmodifiableMap(BLOCK_ENTITIES);
    }

    /** A block entity's update tag as the client is sent it: the same tag when nothing in it moves. */
    static CompoundTag updateTag(BlockEntityType<?> type, CompoundTag tag, Offset offset) {
        // A type nobody classified, such as another mod's, goes through the rule as well
        return BLOCK_ENTITIES.getOrDefault(type, Coverage.TRANSLATED) == Coverage.TRANSLATED ? NbtRule.shift(tag, offset) : tag;
    }

    // From each type's update tag in 26.3. Items are in it as NBT, where a lodestone compass's target is a pos.
    private static void classifyBlockEntities() {
        translated(
                // exit_portal
                BlockEntityTypes.END_GATEWAY,
                // each error's pos
                BlockEntityTypes.TEST_INSTANCE_BLOCK,
                // the items they show
                BlockEntityTypes.CAMPFIRE,
                BlockEntityTypes.SHELF,
                BlockEntityTypes.DECORATED_POT,
                BlockEntityTypes.BRUSHABLE_BLOCK,
                BlockEntityTypes.VAULT,
                // the components left over from the item it was placed from
                BlockEntityTypes.BANNER,
                // its lock, a predicate that can match an item's components
                BlockEntityTypes.BEACON,
                // the entity they spawn next, which an op can give any data
                BlockEntityTypes.MOB_SPAWNER,
                BlockEntityTypes.TRIAL_SPAWNER);
        noPositions(
                BlockEntityTypes.BARREL,
                BlockEntityTypes.BEEHIVE,
                BlockEntityTypes.BELL,
                BlockEntityTypes.BLAST_FURNACE,
                BlockEntityTypes.BREWING_STAND,
                BlockEntityTypes.CALIBRATED_SCULK_SENSOR,
                BlockEntityTypes.CHEST,
                BlockEntityTypes.CHISELED_BOOKSHELF,
                BlockEntityTypes.COMMAND_BLOCK,
                BlockEntityTypes.COMPARATOR,
                // the UUID of what it attacks
                BlockEntityTypes.CONDUIT,
                BlockEntityTypes.COPPER_GOLEM_STATUE,
                BlockEntityTypes.CRAFTER,
                // the UUID of its creaking
                BlockEntityTypes.CREAKING_HEART,
                BlockEntityTypes.DAYLIGHT_DETECTOR,
                BlockEntityTypes.DISPENSER,
                BlockEntityTypes.DROPPER,
                BlockEntityTypes.ENCHANTING_TABLE,
                BlockEntityTypes.END_PORTAL,
                BlockEntityTypes.ENDER_CHEST,
                BlockEntityTypes.FURNACE,
                // text, which is left as it is, as on a sign
                BlockEntityTypes.HANGING_SIGN,
                BlockEntityTypes.HOPPER,
                BlockEntityTypes.JIGSAW,
                BlockEntityTypes.JUKEBOX,
                BlockEntityTypes.LECTERN,
                // the moving block's state
                BlockEntityTypes.PISTON,
                BlockEntityTypes.POTENT_SULFUR,
                BlockEntityTypes.SCULK_CATALYST,
                BlockEntityTypes.SCULK_SENSOR,
                BlockEntityTypes.SCULK_SHRIEKER,
                BlockEntityTypes.SHULKER_BOX,
                BlockEntityTypes.SIGN,
                BlockEntityTypes.SKULL,
                BlockEntityTypes.SMOKER,
                // its corner and size are relative to the block
                BlockEntityTypes.STRUCTURE_BLOCK,
                BlockEntityTypes.TEST_BLOCK,
                BlockEntityTypes.TRAPPED_CHEST);
    }

    private static void translated(BlockEntityType<?>... types) {
        for (BlockEntityType<?> type : types) {
            BLOCK_ENTITIES.put(type, Coverage.TRANSLATED);
        }
    }

    private static void noPositions(BlockEntityType<?>... types) {
        for (BlockEntityType<?> type : types) {
            BLOCK_ENTITIES.put(type, Coverage.NO_POSITIONS);
        }
    }

    private static Packet<?> blockUpdate(ClientboundBlockUpdatePacket packet, Context context) {
        return new ClientboundBlockUpdatePacket(context.offset().shift(packet.getPos()), packet.getBlockState());
    }

    // No field constructor, so the copy goes through the codec. The changed blocks are kept by their spot in the section.
    private static Packet<?> sectionUpdate(ClientboundSectionBlocksUpdatePacket packet, Context context) {
        ClientboundSectionBlocksUpdatePacket copy = CoordinateOffsets.copy(ClientboundSectionBlocksUpdatePacket.STREAM_CODEC, packet);
        SectionBlocksUpdatePacketAccessor fields = (SectionBlocksUpdatePacketAccessor) copy;
        fields.yoltSetSectionPos(context.offset().shift(fields.yoltSectionPos()));
        return copy;
    }

    private static Packet<?> blockEntityData(ClientboundBlockEntityDataPacket packet, Context context) {
        Offset offset = context.offset();
        return BlockEntityDataPacketInvoker.yoltNew(offset.shift(packet.getPos()), packet.getType(),
                updateTag(packet.getType(), packet.getTag(), offset));
    }

    private static Packet<?> blockEvent(ClientboundBlockEventPacket packet, Context context) {
        return new ClientboundBlockEventPacket(context.offset().shift(packet.getPos()), packet.getBlock(), packet.getB0(), packet.getB1());
    }

    private static Packet<?> blockDestruction(ClientboundBlockDestructionPacket packet, Context context) {
        return new ClientboundBlockDestructionPacket(packet.getId(), context.offset().shift(packet.getPos()), packet.getProgress());
    }

    private static Packet<?> transientBlock(ClientboundAddTransientBlockPacket packet, Context context) {
        return new ClientboundAddTransientBlockPacket(context.offset().shift(packet.getPos()), packet.getBlockState());
    }

    // The global ones too, such as a wither's spawn: the client plays them from the direction of the spot
    private static Packet<?> levelEvent(ClientboundLevelEventPacket packet, Context context) {
        return new ClientboundLevelEventPacket(packet.getType(), context.offset().shift(packet.getPos()), packet.getData(),
                packet.isGlobalEvent());
    }

    private static Packet<?> signEditor(ClientboundOpenSignEditorPacket packet, Context context) {
        return new ClientboundOpenSignEditorPacket(context.offset().shift(packet.pos()), packet.slot());
    }
}
