package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.mixin.SectionBlocksUpdatePacketAccessor;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddTransientBlockPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.debug.DebugSubscription;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.block.state.properties.TestBlockMode;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Blocks, block entities and the packets about one block. Each test joins a player whose packets it reads, so the class
// has a batch of its own. Creative, so a leave makes no body.
public class CoordinateOffsetBlockGameTests {
    private static final String BLOCKS = "you-only-live-twice-gametest:coordinate_offset_blocks";
    // A spot far out for hand-built packets, and a gateway's exit and a compass's target
    private static final BlockPos FAR = new BlockPos(1_234, 70, -5_678);
    private static final BlockPos EXIT = new BlockPos(-3_000, 75, 4_100);
    private static final BlockPos TARGET = new BlockPos(9_001, 64, 77);

    @GameTest(environment = BLOCKS)
    public void blockPacketsShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbblock", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos client = offset.shift(FAR);
            BlockState stone = Blocks.STONE.defaultBlockState();
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundBlockUpdatePacket(FAR, stone));
            player.connection.send(new ClientboundBlockEventPacket(FAR, Blocks.NOTE_BLOCK, 1, 2));
            player.connection.send(new ClientboundBlockDestructionPacket(7, FAR, 5));
            player.connection.send(new ClientboundAddTransientBlockPacket(FAR, Blocks.SAND.defaultBlockState()));
            player.connection.send(new ClientboundLevelEventPacket(LevelEvent.SOUND_WITHER_BOSS_SPAWN, FAR, 0, true));
            player.connection.send(new ClientboundLevelEventPacket(LevelEvent.PARTICLES_DESTROY_BLOCK, FAR, 3, false));
            player.connection.send(new ClientboundOpenSignEditorPacket(FAR, SignTextSlot.BACK));

            ClientboundBlockUpdatePacket update = OffsetTestKit.last(helper, player, mark, ClientboundBlockUpdatePacket.class);
            helper.assertValueEqual(update.getPos(), client, "block update spot");
            helper.assertValueEqual(update.getBlockState(), stone, "block update state");
            ClientboundBlockEventPacket event = OffsetTestKit.last(helper, player, mark, ClientboundBlockEventPacket.class);
            helper.assertValueEqual(event.getPos(), client, "block event spot");
            helper.assertValueEqual(event.getBlock(), Blocks.NOTE_BLOCK, "block event block");
            helper.assertValueEqual(event.getB0(), 1, "block event first value");
            helper.assertValueEqual(event.getB1(), 2, "block event second value");
            ClientboundBlockDestructionPacket destruction =
                    OffsetTestKit.last(helper, player, mark, ClientboundBlockDestructionPacket.class);
            helper.assertValueEqual(destruction.getPos(), client, "break crack spot");
            helper.assertValueEqual(destruction.getId(), 7, "break crack id");
            helper.assertValueEqual(destruction.getProgress(), 5, "break crack progress");
            ClientboundAddTransientBlockPacket transientBlock =
                    OffsetTestKit.last(helper, player, mark, ClientboundAddTransientBlockPacket.class);
            helper.assertValueEqual(transientBlock.getPos(), client, "landing block spot");
            helper.assertValueEqual(transientBlock.getBlockState(), Blocks.SAND.defaultBlockState(), "landing block state");
            List<ClientboundLevelEventPacket> levelEvents = OffsetTestKit.sent(player, mark, ClientboundLevelEventPacket.class);
            helper.assertValueEqual(levelEvents.size(), 2, "level events");
            helper.assertValueEqual(levelEvents.get(0).getPos(), client, "global level event spot");
            helper.assertValueEqual(levelEvents.get(0).getType(), LevelEvent.SOUND_WITHER_BOSS_SPAWN, "global level event type");
            helper.assertTrue(levelEvents.get(0).isGlobalEvent(), "the global level event is no longer global");
            helper.assertValueEqual(levelEvents.get(1).getPos(), client, "level event spot");
            helper.assertValueEqual(levelEvents.get(1).getData(), 3, "level event data");
            helper.assertFalse(levelEvents.get(1).isGlobalEvent(), "the level event became global");
            ClientboundOpenSignEditorPacket editor =
                    OffsetTestKit.last(helper, player, mark, ClientboundOpenSignEditorPacket.class);
            helper.assertValueEqual(editor.pos(), client, "sign editor spot");
            helper.assertValueEqual(editor.slot(), SignTextSlot.BACK, "sign editor side");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The changed blocks are kept by their spot inside the section, which an offset of whole chunks doesn't move
    @GameTest(environment = BLOCKS)
    public void sectionUpdateShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbsection", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            BlockPos first = new BlockPos(2, 1, 2);
            BlockPos firstAt = helper.absolutePos(first);
            // A neighbour in the same section
            BlockPos second = SectionPos.sectionRelative(firstAt.getX()) < 15 ? first.east() : first.west();
            helper.setBlock(first, Blocks.GOLD_BLOCK);
            helper.setBlock(second, Blocks.DIAMOND_BLOCK);
            SectionPos section = SectionPos.of(firstAt);
            ShortSet changes = new ShortArraySet();
            changes.add(SectionPos.sectionRelativePos(firstAt));
            changes.add(SectionPos.sectionRelativePos(helper.absolutePos(second)));
            LevelChunk chunk = level.getChunkAt(firstAt);
            LevelChunkSection levelSection = chunk.getSection(chunk.getSectionIndex(firstAt.getY()));
            ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(section, changes, levelSection);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);

            ClientboundSectionBlocksUpdatePacket sent =
                    OffsetTestKit.last(helper, player, mark, ClientboundSectionBlocksUpdatePacket.class);
            helper.assertValueEqual(((SectionBlocksUpdatePacketAccessor) sent).yoltSectionPos(), offset.shift(section),
                    "section sent");
            Map<BlockPos, BlockState> expected = new HashMap<>();
            packet.runUpdates((pos, state) -> expected.put(offset.shift(pos.immutable()), state));
            Map<BlockPos, BlockState> got = new HashMap<>();
            sent.runUpdates((pos, state) -> got.put(pos.immutable(), state));
            helper.assertValueEqual(got, expected, "changed blocks");
            helper.assertValueEqual(got.get(offset.shift(firstAt)), Blocks.GOLD_BLOCK.defaultBlockState(),
                    "the first changed block");
            helper.assertValueEqual(((SectionBlocksUpdatePacketAccessor) packet).yoltSectionPos(), section,
                    "the packet's own section after the send");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = BLOCKS)
    public void blockEntityTagsShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbtags", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            TheEndGatewayBlockEntity gateway = placeGateway(helper, new BlockPos(4, 1, 4));
            BlockPos campfirePos = new BlockPos(4, 1, 1);
            helper.setBlock(campfirePos, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            CampfireBlockEntity campfire = (CampfireBlockEntity) level.getBlockEntity(helper.absolutePos(campfirePos));
            ItemStack compass = new ItemStack(Items.COMPASS);
            compass.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), false));
            campfire.getItems().set(0, compass);
            ClientboundBlockEntityDataPacket gatewayPacket = ClientboundBlockEntityDataPacket.create(gateway);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(gatewayPacket);
            player.connection.send(ClientboundBlockEntityDataPacket.create(campfire));

            List<ClientboundBlockEntityDataPacket> sent = OffsetTestKit.sent(player, mark, ClientboundBlockEntityDataPacket.class);
            helper.assertValueEqual(sent.size(), 2, "block entity updates");
            helper.assertValueEqual(sent.get(0).getPos(), offset.shift(gateway.getBlockPos()), "gateway spot");
            helper.assertValueEqual(sent.get(0).getType(), BlockEntityTypes.END_GATEWAY, "gateway type");
            helper.assertValueEqual(exitPortal(sent.get(0).getTag()), offset.shift(EXIT), "gateway exit");
            helper.assertValueEqual(sent.get(1).getPos(), offset.shift(campfire.getBlockPos()), "campfire spot");
            helper.assertValueEqual(compassTarget(sent.get(1).getTag()), offset.shift(TARGET),
                    "target of the compass on the campfire");

            helper.assertValueEqual(exitPortal(gatewayPacket.getTag()), EXIT, "gateway exit in the packet after the send");
            helper.assertValueEqual(exitPortal(gateway.getUpdateTag(level.registryAccess())), EXIT, "the gateway's own exit");
            helper.assertValueEqual(campfire.getItems().get(0).get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), "the campfire's own compass");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A compass saved in a block entity's tag keeps its target's dimension beside the spot: seen from the nether, an
    // overworld target goes by the overworld's away amount and a nether one by the frame
    @GameTest(environment = BLOCKS)
    public void awayTargetInsideNbtFollowsItsDimension(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        ServerPlayer player = OffsetTestKit.join(helper, "cbawaytag", GameType.CREATIVE);
        try {
            BlockPos campfirePos = new BlockPos(3, 1, 1);
            helper.setBlock(campfirePos, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            CampfireBlockEntity campfire = (CampfireBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(campfirePos));
            campfire.getItems().set(0, compassIn(Level.OVERWORLD, TARGET));
            campfire.getItems().set(1, compassIn(Level.NETHER, EXIT));
            OffsetTestKit.withCoordinates(() -> player.teleportTo(nether, 37.5, 64.0, -50.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            Offset offset = CoordinateOffsets.offset(player);
            Offset away = CoordinateOffsets.awayOffset(player, Level.OVERWORLD);
            helper.assertFalse(away.equals(offset), "the overworld's away amount is the frame's offset " + offset);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(ClientboundBlockEntityDataPacket.create(campfire));

            CompoundTag tag = OffsetTestKit.last(helper, player, mark, ClientboundBlockEntityDataPacket.class).getTag();
            helper.assertValueEqual(compassTarget(tag, 0), away.shift(TARGET), "the overworld compass's target");
            helper.assertValueEqual(compassTarget(tag, 1), offset.shift(EXIT), "the nether compass's target");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // An explorer map's markers are in the saved item the shelf's tag holds, so the client would get the target real
    @GameTest(environment = BLOCKS)
    public void explorerMapOnAShelfShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbshelf", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos shelfPos = new BlockPos(1, 1, 4);
            helper.setBlock(shelfPos, Blocks.OAK_SHELF);
            ShelfBlockEntity shelf = (ShelfBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(shelfPos));
            ItemStack map = new ItemStack(Items.FILLED_MAP);
            MapDecorations decorations = MapDecorations.EMPTY.withDecoration("+",
                    new MapDecorations.Entry(MapDecorationTypes.RED_X, TARGET.getX(), TARGET.getZ(), 180.0F));
            map.set(DataComponents.MAP_DECORATIONS, decorations);
            shelf.getItems().set(0, map);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(ClientboundBlockEntityDataPacket.create(shelf));

            ClientboundBlockEntityDataPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundBlockEntityDataPacket.class);
            CompoundTag marker = sent.getTag().getListOrEmpty("Items").getCompoundOrEmpty(0).getCompoundOrEmpty("components")
                    .getCompoundOrEmpty("minecraft:map_decorations").getCompoundOrEmpty("+");
            BlockPos client = offset.shift(TARGET);
            helper.assertValueEqual(marker.getDoubleOr("x", 0.0), (double) client.getX(), "x of the marker on the shelf");
            helper.assertValueEqual(marker.getDoubleOr("z", 0.0), (double) client.getZ(), "z of the marker on the shelf");
            helper.assertValueEqual(shelf.getItems().get(0).get(DataComponents.MAP_DECORATIONS), decorations,
                    "the shelf's own map");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A chunk far from every test, where nothing is placed, has no tag that holds a position
    @GameTest(environment = BLOCKS)
    public void chunkBlockEntityTagsShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbchunk", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            TheEndGatewayBlockEntity gateway = placeGateway(helper, new BlockPos(5, 1, 5));
            LevelChunk chunk = level.getChunkAt(gateway.getBlockPos());
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);
            ClientboundLevelChunkWithLightPacket sent =
                    OffsetTestKit.last(helper, player, mark, ClientboundLevelChunkWithLightPacket.class);
            CompoundTag sentTag = gatewayTags(sent).get(offset.shift(gateway.getBlockPos()));
            helper.assertTrue(sentTag != null, "no gateway at its client spot in the chunk sent");
            helper.assertValueEqual(exitPortal(sentTag), offset.shift(EXIT), "gateway exit in the chunk");
            helper.assertValueEqual(exitPortal(gatewayTags(packet).get(gateway.getBlockPos())), EXIT,
                    "gateway exit in the packet after the send");

            ClientboundLevelChunkWithLightPacket plain =
                    new ClientboundLevelChunkWithLightPacket(level.getChunk(0, 0), level.getLightEngine(), null, null);
            player.connection.send(plain);
            ClientboundLevelChunkWithLightPacket plainSent =
                    OffsetTestKit.last(helper, player, mark, ClientboundLevelChunkWithLightPacket.class);
            helper.assertTrue(plainSent.chunkData() == plain.chunkData(), "a chunk with no position in it got new data");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A campfire with plain food and a gateway with no exit are of the types whose tags are read, yet hold no position, so
    // the very tags go on, in their own packet and in the chunk
    @GameTest(environment = BLOCKS)
    public void positionFreeTagsKeptAsTheyAre(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbplain", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ServerLevel level = helper.getLevel();
            BlockPos campfirePos = new BlockPos(3, 1, 4);
            helper.setBlock(campfirePos, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            CampfireBlockEntity campfire = (CampfireBlockEntity) level.getBlockEntity(helper.absolutePos(campfirePos));
            campfire.getItems().set(0, new ItemStack(Items.BEEF));
            BlockPos gatewayPos = new BlockPos(5, 1, 3);
            helper.setBlock(gatewayPos, Blocks.END_GATEWAY);
            TheEndGatewayBlockEntity gateway = (TheEndGatewayBlockEntity) level.getBlockEntity(helper.absolutePos(gatewayPos));
            ClientboundBlockEntityDataPacket campfirePacket = ClientboundBlockEntityDataPacket.create(campfire);
            ClientboundBlockEntityDataPacket gatewayPacket = ClientboundBlockEntityDataPacket.create(gateway);
            ClientboundLevelChunkWithLightPacket chunkPacket =
                    new ClientboundLevelChunkWithLightPacket(level.getChunkAt(campfire.getBlockPos()), level.getLightEngine(), null, null);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(campfirePacket);
            player.connection.send(gatewayPacket);
            player.connection.send(chunkPacket);

            List<ClientboundBlockEntityDataPacket> sent = OffsetTestKit.sent(player, mark, ClientboundBlockEntityDataPacket.class);
            helper.assertValueEqual(sent.size(), 2, "block entity updates");
            helper.assertValueEqual(sent.get(0).getPos(), offset.shift(campfire.getBlockPos()), "campfire spot");
            helper.assertTrue(sent.get(0).getTag() == campfirePacket.getTag(), "the campfire's tag was copied");
            helper.assertTrue(sent.get(1).getTag() == gatewayPacket.getTag(), "the gateway's tag was copied");
            // Tests in the batch can share the chunk, so only the campfire's own entry is checked
            ClientboundLevelChunkWithLightPacket chunkSent =
                    OffsetTestKit.last(helper, player, mark, ClientboundLevelChunkWithLightPacket.class);
            CompoundTag before = blockEntityTag(chunkPacket, campfire.getBlockPos());
            helper.assertTrue(before != null, "no campfire in the chunk");
            helper.assertTrue(blockEntityTag(chunkSent, offset.shift(campfire.getBlockPos())) == before,
                    "the campfire's tag in the chunk was copied");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The client sends the dug block with a change of face too, and the server checks its reach to that block first.
    // An action that is about no block sends the zero position, which means nothing and comes back as it is.
    @GameTest(environment = BLOCKS)
    public void destroyDirectionChangeReadAtTheRealBlock(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbface", GameType.CREATIVE);
        try {
            Connection connection = OffsetTestKit.connection(player);
            BlockPos client = CoordinateOffsets.offset(player).shift(FAR);
            ServerboundPlayerActionPacket change = (ServerboundPlayerActionPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.CHANGE_DESTROY_DIRECTION, client,
                            Direction.NORTH, 5));
            helper.assertValueEqual(change.getAction(), ServerboundPlayerActionPacket.Action.CHANGE_DESTROY_DIRECTION, "action");
            helper.assertValueEqual(change.getPos(), FAR, "block of the change of face");
            helper.assertValueEqual(change.getDirection(), Direction.NORTH, "face");
            helper.assertValueEqual(change.getSequence(), 5, "sequence");
            ServerboundPlayerActionPacket drop = new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ITEM,
                    BlockPos.ZERO, Direction.DOWN);
            helper.assertTrue(CoordinateOffsets.incoming(connection, drop) == drop, "a drop was changed");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // An op's edit of a structure block lands on the real block, with its corner and size, which are relative, as sent.
    // The other op blocks' packets name their block the same way.
    @GameTest(environment = BLOCKS)
    public void opBlockEditsLandOnTheRealBlock(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbopblock", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos structurePos = new BlockPos(3, 1, 2);
            helper.setBlock(structurePos, Blocks.STRUCTURE_BLOCK);
            StructureBlockEntity structure = (StructureBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(structurePos));
            BlockPos real = structure.getBlockPos();
            BlockPos client = offset.shift(real);
            TestPlayers.receive(player, new ServerboundSetStructureBlockPacket(client, StructureBlockEntity.UpdateType.UPDATE_DATA,
                    StructureMode.SAVE, "offset_probe", new BlockPos(1, 2, 3), new Vec3i(4, 5, 6), Mirror.NONE, Rotation.NONE, "",
                    false, false, false, true, 1.0F, 0L));
            helper.assertValueEqual(structure.getStructureName(), "minecraft:offset_probe", "the structure block's name");
            helper.assertValueEqual(structure.getStructurePos(), new BlockPos(1, 2, 3), "the structure block's corner");
            helper.assertValueEqual(structure.getStructureSize(), new Vec3i(4, 5, 6), "the structure block's size");

            Connection connection = OffsetTestKit.connection(player);
            ServerboundSetCommandBlockPacket command = (ServerboundSetCommandBlockPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundSetCommandBlockPacket(client, "say 1 2 3", CommandBlockEntity.Mode.AUTO, true, false, true));
            helper.assertValueEqual(command.getPos(), real, "command block spot");
            helper.assertValueEqual(command.getCommand(), "say 1 2 3", "command");
            helper.assertValueEqual(command.getMode(), CommandBlockEntity.Mode.AUTO, "command block mode");
            ServerboundJigsawGeneratePacket generate = (ServerboundJigsawGeneratePacket) CoordinateOffsets.incoming(connection,
                    new ServerboundJigsawGeneratePacket(client, 3, true));
            helper.assertValueEqual(generate.getPos(), real, "jigsaw spot");
            helper.assertValueEqual(generate.levels(), 3, "jigsaw levels");
            ServerboundSetTestBlockPacket test = (ServerboundSetTestBlockPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundSetTestBlockPacket(client, TestBlockMode.LOG, "at 1 2 3"));
            helper.assertValueEqual(test, new ServerboundSetTestBlockPacket(real, TestBlockMode.LOG, "at 1 2 3"), "test block edit");
            ServerboundSetJigsawBlockPacket jigsaw = (ServerboundSetJigsawBlockPacket) CoordinateOffsets.incoming(connection,
                    new ServerboundSetJigsawBlockPacket(client, Identifier.withDefaultNamespace("probe"),
                            Identifier.withDefaultNamespace("target"), Identifier.withDefaultNamespace("pool"), "minecraft:stone",
                            JigsawBlockEntity.JointType.ALIGNED, 2, 5));
            helper.assertValueEqual(jigsaw.getPos(), real, "jigsaw block spot");
            helper.assertValueEqual(jigsaw.getPool(), Identifier.withDefaultNamespace("pool"), "jigsaw pool");
            helper.assertValueEqual(jigsaw.getPlacementPriority(), 5, "jigsaw placement priority");
            TestInstanceBlockEntity.Data data = new TestInstanceBlockEntity.Data(Optional.empty(), new Vec3i(4, 5, 6), Rotation.NONE,
                    false, TestInstanceBlockEntity.Status.CLEARED, Optional.empty());
            ServerboundTestInstanceBlockActionPacket.Action query = ServerboundTestInstanceBlockActionPacket.Action.QUERY;
            ServerboundTestInstanceBlockActionPacket action = (ServerboundTestInstanceBlockActionPacket) CoordinateOffsets.incoming(
                    connection, new ServerboundTestInstanceBlockActionPacket(client, query, data));
            helper.assertValueEqual(action, new ServerboundTestInstanceBlockActionPacket(real, query, data), "test instance action");
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The editor opens at the client's spot, and the text the client sends there lands on the real sign
    @GameTest(environment = BLOCKS)
    public void signEditRoundTrip(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbsign", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos signPos = new BlockPos(2, 1, 1);
            helper.setBlock(signPos, Blocks.OAK_SIGN);
            SignBlockEntity sign = (SignBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(signPos));
            sign.setAllowedPlayerEditor(player.getUUID());
            int mark = OffsetTestKit.mark(player);
            player.openTextEdit(sign, SignTextSlot.FRONT);
            ClientboundOpenSignEditorPacket editor =
                    OffsetTestKit.last(helper, player, mark, ClientboundOpenSignEditorPacket.class);
            helper.assertValueEqual(editor.pos(), offset.shift(sign.getBlockPos()), "sign editor spot");

            List<String> lines = List.of("one", "two", "three", "four");
            TestPlayers.receive(player, new ServerboundSignUpdatePacket(editor.pos(), lines, SignTextSlot.FRONT));
            helper.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).stream().map(Component::getString).toList(),
                    lines, "the sign's text");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The server reads the click and the dig in real coordinates, so the block goes where the client put it
    @GameTest(environment = BLOCKS)
    public void useItemOnAndDigAtTheClientPosition(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbuse", GameType.CREATIVE);
        try {
            OffsetTestKit.settle(helper, player);
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos base = new BlockPos(2, 1, 2);
            helper.setBlock(base, Blocks.GOLD_BLOCK);
            BlockPos client = offset.shift(helper.absolutePos(base));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
            BlockHitResult hit = new BlockHitResult(new Vec3(client.getX() + 0.5, client.getY() + 1.0, client.getZ() + 0.5),
                    Direction.UP, client, false);
            TestPlayers.receive(player, new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 1));
            helper.assertBlockPresent(Blocks.STONE, base.above());

            TestPlayers.receive(player, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    client.above(), Direction.UP, 2));
            helper.assertBlockPresent(Blocks.AIR, base.above());
            helper.assertBlockPresent(Blocks.GOLD_BLOCK, base);
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = BLOCKS)
    public void pickBlockAtTheClientPosition(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbpick", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos diamond = new BlockPos(2, 1, 1);
            helper.setBlock(diamond, Blocks.DIAMOND_BLOCK);
            TestPlayers.receive(player, new ServerboundPickItemFromBlockPacket(offset.shift(helper.absolutePos(diamond)), false));
            helper.assertTrue(player.getInventory().getSelectedItem().is(Items.DIAMOND_BLOCK),
                    "the selected slot holds " + player.getInventory().getSelectedItem());
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Both queries are answered, with nothing in the answers
    @GameTest(environment = BLOCKS)
    public void tagQueriesAnsweredEmpty(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbquery", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            Offset offset = CoordinateOffsets.offset(player);
            TheEndGatewayBlockEntity gateway = placeGateway(helper, new BlockPos(5, 1, 2));
            Entity pig = helper.spawn(EntityTypes.PIG, new BlockPos(2, 1, 4));
            int mark = OffsetTestKit.mark(player);
            TestPlayers.receive(player, new ServerboundBlockEntityTagQueryPacket(7, offset.shift(gateway.getBlockPos())));
            TestPlayers.receive(player, new ServerboundEntityTagQueryPacket(8, pig.getId()));
            List<ClientboundTagQueryPacket> answers = OffsetTestKit.sent(player, mark, ClientboundTagQueryPacket.class);
            helper.assertValueEqual(answers.stream().map(ClientboundTagQueryPacket::getTransactionId).toList(), List.of(7, 8),
                    "queries answered");
            for (ClientboundTagQueryPacket answer : answers) {
                helper.assertValueEqual(answer.getTag(), new CompoundTag(), "answer to query " + answer.getTransactionId());
            }
        } finally {
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Their values are full of positions, so only the tick times, which hold none, are left; an op with no offset keeps all
    @GameTest(environment = BLOCKS)
    public void noDebugSubscriptionsWithAnOffset(GameTestHelper helper) {
        ServerPlayer shifted = OffsetTestKit.join(helper, "cbdebug", GameType.CREATIVE);
        ServerPlayer plain = null;
        try {
            plain = TestPlayers.join(helper, "cbdebugplain", GameType.CREATIVE, new BlockPos(1, 1, 1));
            TestPlayers.op(shifted);
            TestPlayers.op(plain);
            Set<DebugSubscription<?>> wanted = Set.of(DebugSubscriptions.BEES, DebugSubscriptions.DEDICATED_SERVER_TICK_TIME);
            shifted.requestDebugSubscriptions(wanted);
            plain.requestDebugSubscriptions(wanted);
            helper.assertValueEqual(shifted.debugSubscriptions(),
                    Set.<DebugSubscription<?>>of(DebugSubscriptions.DEDICATED_SERVER_TICK_TIME), "debug subscriptions with an offset");
            shifted.requestDebugSubscriptions(Set.of(DebugSubscriptions.POIS));
            helper.assertValueEqual(shifted.debugSubscriptions(), Set.<DebugSubscription<?>>of(),
                    "debug subscriptions with an offset and no tick times asked for");
            helper.assertValueEqual(plain.debugSubscriptions(), wanted, "debug subscriptions of an op joined with the switch off");
        } finally {
            TestPlayers.deop(shifted);
            TestPlayers.leave(shifted);
            if (plain != null) {
                TestPlayers.deop(plain);
                TestPlayers.leave(plain);
            }
        }
        helper.succeed();
    }

    private static TheEndGatewayBlockEntity placeGateway(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.END_GATEWAY);
        BlockPos at = helper.absolutePos(relative);
        TheEndGatewayBlockEntity gateway = (TheEndGatewayBlockEntity) helper.getLevel().getBlockEntity(at);
        gateway.setExitPosition(EXIT, false);
        return gateway;
    }

    private static Map<BlockPos, CompoundTag> gatewayTags(ClientboundLevelChunkWithLightPacket packet) {
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), (pos, type, tag) -> {
            if (type == BlockEntityTypes.END_GATEWAY && tag != null) {
                tags.put(pos.immutable(), tag);
            }
        });
        return tags;
    }

    private static @Nullable CompoundTag blockEntityTag(ClientboundLevelChunkWithLightPacket packet, BlockPos at) {
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), (pos, type, tag) -> {
            if (tag != null) {
                tags.put(pos.immutable(), tag);
            }
        });
        return tags.get(at);
    }

    private static ItemStack compassIn(ResourceKey<Level> dimension, BlockPos target) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(dimension, target)), false));
        return compass;
    }

    private static BlockPos exitPortal(CompoundTag tag) {
        int[] pos = tag.getIntArray("exit_portal").orElse(new int[3]);
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    private static BlockPos compassTarget(CompoundTag tag) {
        return compassTarget(tag, 0);
    }

    // The target of the compass at this place in the tag's item list
    private static BlockPos compassTarget(CompoundTag tag, int index) {
        int[] pos = tag.getListOrEmpty("Items").getCompoundOrEmpty(index).getCompoundOrEmpty("components")
                .getCompoundOrEmpty("minecraft:lodestone_tracker").getCompoundOrEmpty("target").getIntArray("pos")
                .orElse(new int[3]);
        return new BlockPos(pos[0], pos[1], pos[2]);
    }
}
