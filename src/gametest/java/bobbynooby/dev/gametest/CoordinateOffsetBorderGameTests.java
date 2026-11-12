package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.BorderWalls;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;

import java.util.List;

// The border a client with an offset is shown: only the walls near the player are real, and a whole-world border goes
// as it is. Each test sets the level's border and puts it back in the same tick, and reads its players' packets, so
// the class has a batch of its own.
public class CoordinateOffsetBorderGameTests {
    private static final String BORDER = "you-only-live-twice-gametest:coordinate_offset_border";
    private static final String TIMER = "you-only-live-twice-gametest:coordinate_offset_border_timer";
    // Where OffsetTestKit joins a player, in the test
    private static final BlockPos SPOT = new BlockPos(1, 1, 1);
    // The world's edge, and the size of the square past it that hides a far wall
    private static final int EDGE = 29_999_984;
    private static final double WHOLE = 59_999_968.0;

    @GameTest(environment = BORDER)
    public void wholeWorldBorderGoesOutAsItIs(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cbwhole", GameType.CREATIVE);
        try {
            ClientboundInitializeBorderPacket packet = new ClientboundInitializeBorderPacket(new WorldBorder());
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);
            helper.assertTrue(OffsetTestKit.last(helper, player, mark, ClientboundInitializeBorderPacket.class) == packet,
                    "a copy of the whole-world border went out");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The east wall 100 blocks off is the real one shifted; the far west wall goes past the world's edge, and so do the
    // north and south walls, 2,000 off
    @GameTest(environment = BORDER)
    public void nearWallIsTheRealWallShifted(GameTestHelper helper) {
        withBorder(helper, eastWallFrom(helper, 100), 4_000, () -> {
            ServerPlayer player = OffsetTestKit.join(helper, "cbnear", GameType.CREATIVE);
            try {
                Offset offset = CoordinateOffsets.offset(player);
                ClientboundInitializeBorderPacket sent = OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class);
                WorldBorder real = helper.getLevel().getWorldBorder();
                helper.assertValueEqual(maxX(sent), real.getMaxX() + offset.x(), "east wall");
                helper.assertTrue(sent.getNewCenterX() - sent.getNewSize() / 2.0 <= -EDGE, "the west wall is inside the world's edge: "
                        + (sent.getNewCenterX() - sent.getNewSize() / 2.0));
                helper.assertValueEqual(sent.getNewCenterZ(), 0.0, "centre z");
                helper.assertValueEqual(List.of(sent.getOldSize(), sent.getNewSize()), List.of(WHOLE, WHOLE), "sizes");
                helper.assertValueEqual(sent.getLerpTime(), 0L, "lerp time");
            } finally {
                TestPlayers.leave(player);
            }
        });
        helper.succeed();
    }

    // Both walls of an axis near: hiding them would show anyway, so the border goes out plainly shifted
    @GameTest(environment = BORDER)
    public void oppositeWallsGiveThePlainBorder(GameTestHelper helper) {
        BlockPos spot = helper.absolutePos(SPOT);
        withBorder(helper, spot, 64, () -> {
            ServerPlayer player = OffsetTestKit.join(helper, "cbplain", GameType.CREATIVE);
            try {
                Offset offset = CoordinateOffsets.offset(player);
                ClientboundInitializeBorderPacket sent = OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class);
                helper.assertValueEqual(List.of(sent.getNewCenterX(), sent.getNewCenterZ()),
                        List.of((double) spot.getX() + offset.x(), (double) spot.getZ() + offset.z()), "centre");
                helper.assertValueEqual(List.of(sent.getOldSize(), sent.getNewSize()), List.of(64.0, 64.0), "sizes");
            } finally {
                TestPlayers.leave(player);
            }
        });
        helper.succeed();
    }

    // A vanilla change goes out as the whole border through the walls rule, so the client never gets a real centre or size
    @GameTest(environment = BORDER)
    public void borderChangeKeepsTheNearWall(GameTestHelper helper) {
        withBorder(helper, eastWallFrom(helper, 100), 4_000, () -> {
            ServerPlayer player = OffsetTestKit.join(helper, "cbchange", GameType.CREATIVE);
            try {
                Offset offset = CoordinateOffsets.offset(player);
                WorldBorder real = helper.getLevel().getWorldBorder();
                double before = maxX(OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class));
                int mark = OffsetTestKit.mark(player);
                real.setSize(4_000 - 40);

                List<Packet<?>> after = TestPlayers.sentPackets(player).subList(mark, TestPlayers.sentPackets(player).size());
                Packet<?> next = after.stream().filter(packet -> packet instanceof ClientboundInitializeBorderPacket
                        || packet instanceof ClientboundSetBorderSizePacket || packet instanceof ClientboundSetBorderCenterPacket
                        || packet instanceof ClientboundSetBorderLerpSizePacket).findFirst().orElse(null);
                helper.assertTrue(next instanceof ClientboundInitializeBorderPacket, "the next border packet was " + next);
                helper.assertValueEqual(maxX((ClientboundInitializeBorderPacket) next), before - 20.0, "east wall after the change");
                helper.assertValueEqual(maxX((ClientboundInitializeBorderPacket) next), real.getMaxX() + offset.x(),
                        "east wall against the real one");
                helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundSetBorderSizePacket.class).isEmpty(),
                        "a size change reached the client");
            } finally {
                TestPlayers.leave(player);
            }
        });
        helper.succeed();
    }

    // Walking near a wall sends nothing by itself; the recheck notices and sends the walls once
    @GameTest(environment = BORDER)
    public void recheckSendsTheNearWall(GameTestHelper helper) {
        int viewDistance = helper.getLevel().getServer().getPlayerList().getViewDistance();
        helper.assertTrue(BorderWalls.near(viewDistance) < 1_000, "a wall 1,000 off counts as near");
        withBorder(helper, eastWallFrom(helper, 1_000), 4_000, () -> {
            ServerPlayer player = OffsetTestKit.join(helper, "cbrecheck", GameType.CREATIVE);
            try {
                Offset offset = CoordinateOffsets.offset(player);
                WorldBorder real = helper.getLevel().getWorldBorder();
                ClientboundInitializeBorderPacket joined = OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class);
                helper.assertValueEqual(List.of(joined.getNewCenterX(), joined.getNewCenterZ(), joined.getNewSize()),
                        List.of(0.0, 0.0, WHOLE), "the join's border");
                player.snapTo(real.getMaxX() - 50.0, player.getY(), player.getZ());
                int mark = OffsetTestKit.mark(player);
                CoordinateOffsets.recheckBorders(helper.getLevel().getServer());
                List<ClientboundInitializeBorderPacket> sent = OffsetTestKit.sent(player, mark, ClientboundInitializeBorderPacket.class);
                helper.assertValueEqual(sent.size(), 1, "borders the recheck sent");
                helper.assertValueEqual(maxX(sent.getFirst()), real.getMaxX() + offset.x(), "east wall from the recheck");
                int again = OffsetTestKit.mark(player);
                CoordinateOffsets.recheckBorders(helper.getLevel().getServer());
                helper.assertTrue(OffsetTestKit.sent(player, again, ClientboundInitializeBorderPacket.class).isEmpty(),
                        "a second recheck sent the same walls again");
            } finally {
                TestPlayers.leave(player);
            }
        });
        helper.succeed();
    }

    @GameTest(environment = TIMER, maxTicks = 30)
    public void recheckRunsEverySecond(GameTestHelper helper) {
        int before = CoordinateOffsets.borderRechecks();
        helper.runAfterDelay(21, () -> {
            helper.assertTrue(CoordinateOffsets.borderRechecks() > before, "no recheck ran in 21 ticks");
            helper.succeed();
        });
    }

    // A border with its east wall this far east of the join spot, centred on the spot's Z
    private static BlockPos eastWallFrom(GameTestHelper helper, int distance) {
        BlockPos spot = helper.absolutePos(SPOT);
        return new BlockPos(spot.getX() + distance - 2_000, spot.getY(), spot.getZ());
    }

    // The level's border with this centre and size while the body runs, put back after it in the same tick
    private static void withBorder(GameTestHelper helper, BlockPos centre, double size, Runnable body) {
        WorldBorder border = helper.getLevel().getWorldBorder();
        double centreX = border.getCenterX();
        double centreZ = border.getCenterZ();
        double sizeBefore = border.getSize();
        try {
            border.setCenter(centre.getX(), centre.getZ());
            border.setSize(size);
            body.run();
        } finally {
            border.setCenter(centreX, centreZ);
            border.setSize(sizeBefore);
        }
    }

    // Where the client puts the east wall, which it keeps inside the world's edge
    private static double maxX(ClientboundInitializeBorderPacket packet) {
        return Math.min(packet.getNewCenterX() + packet.getNewSize() / 2.0, packet.getNewAbsoluteMaxSize());
    }
}
