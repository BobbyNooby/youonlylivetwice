package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.BlockEntityDataPacketInvoker;
import bobbynooby.dev.mixin.SectionBlocksUpdatePacketAccessor;
import net.minecraft.core.BlockPos;
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
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Blocks, block entities and the packets about one block, both ways, and which block entities' update tags can hold a
 * position.
 */
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
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_TAG_QUERY, BlockRules::tagAnswer);
        Incoming.translate(GamePacketTypes.SERVERBOUND_USE_ITEM_ON, BlockRules::useItemOn);
        Incoming.translate(GamePacketTypes.SERVERBOUND_PLAYER_ACTION, BlockRules::playerAction);
        Incoming.translate(GamePacketTypes.SERVERBOUND_SIGN_UPDATE, BlockRules::signUpdate);
        Incoming.translate(GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_BLOCK, BlockRules::pickBlock);
        Incoming.translate(GamePacketTypes.SERVERBOUND_BLOCK_ENTITY_TAG_QUERY, BlockRules::tagQuery);
        Incoming.translate(GamePacketTypes.SERVERBOUND_SET_STRUCTURE_BLOCK, BlockRules::structureBlock);
        Incoming.translate(GamePacketTypes.SERVERBOUND_SET_JIGSAW_BLOCK, BlockRules::jigsawBlock);
        Incoming.translate(GamePacketTypes.SERVERBOUND_JIGSAW_GENERATE, BlockRules::jigsawGenerate);
        Incoming.translate(GamePacketTypes.SERVERBOUND_SET_COMMAND_BLOCK, BlockRules::commandBlock);
        Incoming.translate(GamePacketTypes.SERVERBOUND_SET_TEST_BLOCK, BlockRules::testBlock);
        Incoming.translate(GamePacketTypes.SERVERBOUND_TEST_INSTANCE_BLOCK_ACTION, BlockRules::testInstanceAction);
    }

    /** Every block entity type: TRANSLATED when its update tag goes through the NBT rule, as it can hold a position. */
    public static Map<BlockEntityType<?>, Coverage> blockEntityCoverage() {
        return Collections.unmodifiableMap(BLOCK_ENTITIES);
    }

    /** A block entity's update tag as the client is sent it: the same tag when nothing in it moves. */
    static CompoundTag updateTag(BlockEntityType<?> type, CompoundTag tag, Frame frame) {
        // A type nobody classified, such as another mod's, goes through the rule as well
        return BLOCK_ENTITIES.getOrDefault(type, Coverage.TRANSLATED) == Coverage.TRANSLATED ? NbtRule.shift(tag, frame) : tag;
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
        ClientboundSectionBlocksUpdatePacket copy =
                CoordinateOffsets.copy(ClientboundSectionBlocksUpdatePacket.STREAM_CODEC, packet);
        SectionBlocksUpdatePacketAccessor fields = (SectionBlocksUpdatePacketAccessor) copy;
        fields.yoltSetSectionPos(context.offset().shift(fields.yoltSectionPos()));
        return copy;
    }

    private static Packet<?> blockEntityData(ClientboundBlockEntityDataPacket packet, Context context) {
        Offset offset = context.offset();
        return BlockEntityDataPacketInvoker.yoltNew(offset.shift(packet.getPos()), packet.getType(),
                updateTag(packet.getType(), packet.getTag(), context.frame()));
    }

    private static Packet<?> blockEvent(ClientboundBlockEventPacket packet, Context context) {
        return new ClientboundBlockEventPacket(context.offset().shift(packet.getPos()), packet.getBlock(), packet.getB0(),
                packet.getB1());
    }

    private static Packet<?> blockDestruction(ClientboundBlockDestructionPacket packet, Context context) {
        return new ClientboundBlockDestructionPacket(packet.getId(), context.offset().shift(packet.getPos()), packet.getProgress());
    }

    private static Packet<?> transientBlock(ClientboundAddTransientBlockPacket packet, Context context) {
        return new ClientboundAddTransientBlockPacket(context.offset().shift(packet.getPos()), packet.getBlockState());
    }

    // The global ones too, such as a wither's spawn: the client plays them from the direction of the spot. The client
    // plays the portal sound where it stands, whatever the spot. Vanilla sends it at the zero position, which shifted
    // would be the offset itself, and any other sender's spot would be real, so it always goes at zero.
    private static Packet<?> levelEvent(ClientboundLevelEventPacket packet, Context context) {
        if (packet.getType() == LevelEvent.SOUND_PORTAL_TRAVEL) {
            return packet.getPos().equals(BlockPos.ZERO) ? packet
                    : new ClientboundLevelEventPacket(packet.getType(), BlockPos.ZERO, packet.getData(), packet.isGlobalEvent());
        }
        return new ClientboundLevelEventPacket(packet.getType(), context.offset().shift(packet.getPos()), packet.getData(),
                packet.isGlobalEvent());
    }

    private static Packet<?> signEditor(ClientboundOpenSignEditorPacket packet, Context context) {
        return new ClientboundOpenSignEditorPacket(context.offset().shift(packet.pos()), packet.slot());
    }

    // An entity's or a block entity's whole save is full of positions, and only ops can ask, who have admin view
    private static Packet<?> tagAnswer(ClientboundTagQueryPacket packet, Context context) {
        return new ClientboundTagQueryPacket(packet.getTransactionId(), new CompoundTag());
    }

    // What the client sends comes back by the incoming offset taken off
    private static BlockPos real(BlockPos client, Context context) {
        return context.offset().negate().shift(client);
    }

    // The click spot moves with the block, so the server still finds it inside the block. Nothing else in the hit changes.
    private static Packet<?> useItemOn(ServerboundUseItemOnPacket packet, Context context) {
        BlockHitResult hit = packet.hitResult();
        BlockHitResult realHit = new BlockHitResult(context.offset().negate().shift(hit.getLocation()), hit.getDirection(),
                real(hit.getBlockPos(), context), hit.isInside(), hit.isWorldBorderHit());
        return new ServerboundUseItemOnPacket(packet.hand(), realHit, packet.sequence());
    }

    // Digging names the block; the other actions send the zero position, which means nothing
    private static Packet<?> playerAction(ServerboundPlayerActionPacket packet, Context context) {
        return switch (packet.getAction()) {
            case START_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK ->
                    new ServerboundPlayerActionPacket(packet.getAction(), real(packet.getPos(), context), packet.getDirection(),
                            packet.getSequence());
            default -> packet;
        };
    }

    // The text is left as it is, coordinates written on a sign included
    private static Packet<?> signUpdate(ServerboundSignUpdatePacket packet, Context context) {
        return new ServerboundSignUpdatePacket(real(packet.pos(), context), packet.lines(), packet.slot());
    }

    private static Packet<?> pickBlock(ServerboundPickItemFromBlockPacket packet, Context context) {
        return new ServerboundPickItemFromBlockPacket(real(packet.pos(), context), packet.includeData());
    }

    private static Packet<?> tagQuery(ServerboundBlockEntityTagQueryPacket packet, Context context) {
        return new ServerboundBlockEntityTagQueryPacket(packet.getTransactionId(), real(packet.getPos(), context));
    }

    // Its offset and size are relative to the block
    private static Packet<?> structureBlock(ServerboundSetStructureBlockPacket packet, Context context) {
        return new ServerboundSetStructureBlockPacket(real(packet.getPos(), context), packet.getUpdateType(), packet.getMode(),
                packet.getName(), packet.getOffset(), packet.getSize(), packet.getMirror(), packet.getRotation(), packet.getData(),
                packet.isIgnoreEntities(), packet.isStrict(), packet.isShowAir(), packet.isShowBoundingBox(), packet.getIntegrity(),
                packet.getSeed());
    }

    private static Packet<?> jigsawBlock(ServerboundSetJigsawBlockPacket packet, Context context) {
        return new ServerboundSetJigsawBlockPacket(real(packet.getPos(), context), packet.getName(), packet.getTarget(),
                packet.getPool(), packet.getFinalState(), packet.getJoint(), packet.getSelectionPriority(),
                packet.getPlacementPriority());
    }

    private static Packet<?> jigsawGenerate(ServerboundJigsawGeneratePacket packet, Context context) {
        return new ServerboundJigsawGeneratePacket(real(packet.getPos(), context), packet.levels(), packet.keepJigsaws());
    }

    // Coordinates typed in the command run later, from the block, so they stay as they are
    private static Packet<?> commandBlock(ServerboundSetCommandBlockPacket packet, Context context) {
        return new ServerboundSetCommandBlockPacket(real(packet.getPos(), context), packet.getCommand(), packet.getMode(),
                packet.isTrackOutput(), packet.isConditional(), packet.isAutomatic());
    }

    private static Packet<?> testBlock(ServerboundSetTestBlockPacket packet, Context context) {
        return new ServerboundSetTestBlockPacket(real(packet.position(), context), packet.mode(), packet.message());
    }

    private static Packet<?> testInstanceAction(ServerboundTestInstanceBlockActionPacket packet, Context context) {
        return new ServerboundTestInstanceBlockActionPacket(real(packet.pos(), context), packet.action(), packet.data());
    }
}
