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
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    // Their values are full of positions; an op with no offset keeps them
    @GameTest(environment = BLOCKS)
    public void noDebugSubscriptionsWithAnOffset(GameTestHelper helper) {
        ServerPlayer shifted = OffsetTestKit.join(helper, "cbdebug", GameType.CREATIVE);
        ServerPlayer plain = null;
        try {
            plain = TestPlayers.join(helper, "cbdebugplain", GameType.CREATIVE, new BlockPos(1, 1, 1));
            TestPlayers.op(shifted);
            TestPlayers.op(plain);
            Set<DebugSubscription<?>> wanted = Set.of(DebugSubscriptions.BEES);
            shifted.requestDebugSubscriptions(wanted);
            plain.requestDebugSubscriptions(wanted);
            helper.assertValueEqual(shifted.debugSubscriptions(), Set.<DebugSubscription<?>>of(),
                    "debug subscriptions with an offset");
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

    private static BlockPos exitPortal(CompoundTag tag) {
        int[] pos = tag.getIntArray("exit_portal").orElse(new int[3]);
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    private static BlockPos compassTarget(CompoundTag tag) {
        int[] pos = tag.getListOrEmpty("Items").getCompoundOrEmpty(0).getCompoundOrEmpty("components")
                .getCompoundOrEmpty("minecraft:lodestone_tracker").getCompoundOrEmpty("target").getIntArray("pos")
                .orElse(new int[3]);
        return new BlockPos(pos[0], pos[1], pos[2]);
    }
}
