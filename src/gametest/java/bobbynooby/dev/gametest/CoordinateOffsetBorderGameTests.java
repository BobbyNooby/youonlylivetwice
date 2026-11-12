package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.BorderWalls;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.visibility.NorthCompass;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// The border a client with an offset is shown: only the walls near the player are real, and a whole-world border goes
// as it is. Each test sets the level's border and puts it back in the same tick, and reads its players' packets, so
// the class has a batch of its own.
public class CoordinateOffsetBorderGameTests {
    private static final String BORDER = "you-only-live-twice-gametest:coordinate_offset_border";
    private static final String TIMER = "you-only-live-twice-gametest:coordinate_offset_border_timer";
    // Holds a border and a player across ticks
    private static final String TICK = "you-only-live-twice-gametest:coordinate_offset_border_tick";
    // Where OffsetTestKit joins a player, in the test
    private static final BlockPos SPOT = new BlockPos(1, 1, 1);
    // The world's edge, and the size of the square past it that hides a far wall
    private static final int EDGE = 29_999_984;
    private static final double WHOLE = 59_999_968.0;
    // A north compass's target, a million blocks inside the edge
    private static final int NORTH = 28_999_984;
    // A max-world-size well inside the world's own edge
    private static final int SMALL_WORLD = 10_000;

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

    // With max-world-size set, the world stops short of its own edge, and that stop is a wall like the border's: near, the
    // client gets the real one shifted, and its own edge stays the world's, so no wall shows where there is none
    @GameTest(environment = BORDER)
    public void maxWorldSizeEdgeIsAWall(GameTestHelper helper) {
        WorldBorder border = helper.getLevel().getWorldBorder();
        int edgeBefore = border.getAbsoluteMaxSize();
        ServerPlayer player = null;
        try {
            border.setAbsoluteMaxSize(SMALL_WORLD);
            player = OffsetTestKit.joinNearTheCentre(helper, "cbedge", GameType.CREATIVE, new BlockPos(SMALL_WORLD - 50, 100, 0));
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            ClientboundInitializeBorderPacket sent = OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class);
            helper.assertValueEqual(sent.getNewAbsoluteMaxSize(), EDGE, "the client's world edge");
            helper.assertValueEqual(maxX(sent), (double) SMALL_WORLD + offset.x(), "east wall");
            helper.assertTrue(sent.getNewCenterX() - sent.getNewSize() / 2.0 <= -EDGE, "the west wall is inside the world's edge: "
                    + (sent.getNewCenterX() - sent.getNewSize() / 2.0));
        } finally {
            border.setAbsoluteMaxSize(edgeBefore);
            if (player != null) {
                TestPlayers.leave(player);
            }
        }
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

    // A teleport can land next to a wall, so the walls are checked again at the end of its tick, not up to a second later.
    // Walking there is left to the once-a-second recheck.
    @GameTest(environment = BORDER)
    public void teleportNextToAWallRechecksAtTheTicksEnd(GameTestHelper helper) {
        int viewDistance = helper.getLevel().getServer().getPlayerList().getViewDistance();
        helper.assertTrue(BorderWalls.near(viewDistance) < 1_000, "a wall 1,000 off counts as near");
        withBorder(helper, eastWallFrom(helper, 1_000), 4_000, () -> {
            ServerPlayer player = OffsetTestKit.join(helper, "cbteleport", GameType.CREATIVE);
            try {
                MinecraftServer server = helper.getLevel().getServer();
                Offset offset = CoordinateOffsets.offset(player);
                WorldBorder real = helper.getLevel().getWorldBorder();
                // The join's own teleport, after which its border went out
                int mark = OffsetTestKit.mark(player);
                CoordinateOffsets.recheckTeleported(server);
                helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundInitializeBorderPacket.class).isEmpty(),
                        "the join's walls were sent again");

                player.snapTo(real.getMaxX() - 50.0, player.getY(), player.getZ());
                CoordinateOffsets.recheckTeleported(server);
                helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundInitializeBorderPacket.class).isEmpty(),
                        "walls were sent for a move that was no teleport");

                player.teleportTo(helper.getLevel(), real.getMaxX() - 50.0, player.getY(), player.getZ(), Set.of(), 0.0F, 0.0F, true);
                CoordinateOffsets.recheckTeleported(server);
                List<ClientboundInitializeBorderPacket> sent = OffsetTestKit.sent(player, mark, ClientboundInitializeBorderPacket.class);
                helper.assertValueEqual(sent.size(), 1, "borders sent after the teleport");
                helper.assertValueEqual(maxX(sent.getFirst()), real.getMaxX() + offset.x(), "east wall after the teleport");
            } finally {
                TestPlayers.leave(player);
            }
        });
        helper.succeed();
    }

    // The same through the tick handler: a teleport on a tick that isn't a whole second gets its walls by the next tick,
    // which the once-a-second recheck alone wouldn't send
    @GameTest(environment = TICK, maxTicks = 20)
    public void tickHandlerSendsTheTeleportsWalls(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(BorderWalls.near(server.getPlayerList().getViewDistance()) < 1_000, "a wall 1,000 off counts as near");
        ServerPlayer player = OffsetTestKit.join(helper, "cbtick", GameType.CREATIVE);
        WorldBorder real = helper.getLevel().getWorldBorder();
        double centreX = real.getCenterX();
        double centreZ = real.getCenterZ();
        double sizeBefore = real.getSize();
        boolean[] cleaned = {false};
        Runnable cleanUp = () -> {
            if (!cleaned[0]) {
                cleaned[0] = true;
                real.setCenter(centreX, centreZ);
                real.setSize(sizeBefore);
                TestPlayers.leave(player);
            }
        };
        helper.runBeforeTestEnd(cleanUp);
        // The handler runs as the tick ends, at the tick count the test sees during it; a whole second runs every check
        int delay = (server.getTickCount() + 1) % SharedConstants.TICKS_PER_SECOND == 0 ? 2 : 1;
        int[] mark = {0};
        helper.runAfterDelay(delay, () -> {
            try {
                BlockPos centre = eastWallFrom(helper, 1_000);
                real.setCenter(centre.getX(), centre.getZ());
                real.setSize(4_000);
                mark[0] = OffsetTestKit.mark(player);
                player.teleportTo(helper.getLevel(), real.getMaxX() - 50.0, player.getY(), player.getZ(), Set.of(), 0.0F, 0.0F, true);
            } catch (RuntimeException | Error e) {
                cleanUp.run();
                throw e;
            }
        });
        helper.runAfterDelay(delay + 1, () -> {
            try {
                Offset offset = CoordinateOffsets.offset(player);
                List<ClientboundInitializeBorderPacket> sent = OffsetTestKit.sent(player, mark[0], ClientboundInitializeBorderPacket.class);
                helper.assertValueEqual(sent.size(), 1, "borders sent by the end of the teleport's tick");
                helper.assertValueEqual(maxX(sent.getFirst()), real.getMaxX() + offset.x(), "east wall after the teleport");
            } finally {
                cleanUp.run();
            }
            helper.succeed();
        });
    }

    // An op types the centre in their own numbers, as every typed spot is, though /yolt center keeps a typed 100 at 100
    // instead of centring it. The End's border stays at 0, 0.
    @GameTest(environment = BORDER)
    public void typedCenterReadInTheOpsFrame(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection config = Settings.get().worldBorder;
        boolean enabledBefore = config.enabled;
        Map<ServerLevel, double[]> centresBefore = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            centresBefore.put(level, new double[] {level.getWorldBorder().getCenterX(), level.getWorldBorder().getCenterZ()});
        }
        ServerPlayer player = OffsetTestKit.join(helper, "cbcenter", GameType.CREATIVE);
        try {
            TestPlayers.op(player);
            // The command leaves the border alone while the feature is off
            config.enabled = true;
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            int clientX = player.getBlockX() + offset.x();
            int clientZ = player.getBlockZ() + offset.z();
            OffsetTestKit.type(player, "yolt center " + clientX + " " + clientZ + " confirm");
            WorldBorder border = helper.getLevel().getWorldBorder();
            helper.assertValueEqual(List.of(border.getCenterX(), border.getCenterZ()),
                    List.of((double) player.getBlockX(), (double) player.getBlockZ()), "the border's real centre");
            WorldBorder end = server.getLevel(Level.END).getWorldBorder();
            helper.assertValueEqual(List.of(end.getCenterX(), end.getCenterZ()), List.of(0.0, 0.0), "the End border's centre");
        } finally {
            config.enabled = enabledBefore;
            for (ServerLevel level : server.getAllLevels()) {
                double[] centre = centresBefore.get(level);
                level.getWorldBorder().setCenter(centre[0], centre[1]);
            }
            TestPlayers.deop(player);
            TestPlayers.leave(player);
        }
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

    // The bundle's first border is the whole world and its target's Z the same for everyone, so both go as they are;
    // only the target's X is the player's own, in their frame
    @GameTest(environment = BORDER)
    public void northBundleCarriesNoOffset(GameTestHelper helper) {
        ServerPlayer player = withCompassSpawn(true, () -> OffsetTestKit.join(helper, "cbnorth", GameType.CREATIVE));
        try {
            Offset offset = CoordinateOffsets.offset(player);
            List<Packet<?>> parts = northBundle(helper, player);
            ClientboundInitializeBorderPacket first = (ClientboundInitializeBorderPacket) parts.get(0);
            helper.assertValueEqual(List.of(first.getNewCenterX(), first.getNewCenterZ(), first.getNewSize()), List.of(0.0, 0.0, WHOLE),
                    "the bundle's first border");
            BlockPos target = ((ClientboundSetDefaultSpawnPositionPacket) parts.get(1)).respawnData().pos();
            helper.assertValueEqual(target.getX(), player.getBlockX() + offset.x(), "target x");
            helper.assertValueEqual(target.getZ(), -NORTH, "target z");
            helper.assertTrue(new WorldBorder().isWithinBounds(target), "the target is outside the whole-world border: " + target);
            assertSameBorder(helper, (ClientboundInitializeBorderPacket) parts.get(2),
                    OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class), "the bundle's last border");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Far out, the target's X stops at the edge in the client's frame, not in the real one, where stopping it and then
    // shifting it would show the offset
    @GameTest(environment = BORDER)
    public void northTargetStopsAtTheEdgeInTheClientsFrame(GameTestHelper helper) {
        ServerPlayer player = withCompassSpawn(true, () -> OffsetTestKit.join(helper, "cbnorthedge", GameType.CREATIVE));
        Vec3 joined = player.position();
        try {
            Offset offset = CoordinateOffsets.offset(player);
            helper.assertFalse(offset.isZero(), "the player got no offset");
            for (int realX : new int[] {29_500_000, -29_500_000}) {
                player.snapTo(realX + 0.5, joined.y, joined.z);
                BlockPos target = NorthCompass.targetFor(player, helper.getLevel());
                helper.assertValueEqual(target.getX() + offset.x(), Mth.clamp(realX + offset.x(), -NORTH, NORTH),
                        "target x the client is shown at real x " + realX);
            }
        } finally {
            player.snapTo(joined.x, joined.y, joined.z);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The bundle puts back the walls the client had, not the real border shifted
    @GameTest(environment = BORDER)
    public void northBundleRestoresTheWallsBorder(GameTestHelper helper) {
        withBorder(helper, eastWallFrom(helper, 100), 4_000, () -> {
            ServerPlayer player = withCompassSpawn(true, () -> OffsetTestKit.join(helper, "cbnorthwall", GameType.CREATIVE));
            try {
                Offset offset = CoordinateOffsets.offset(player);
                ClientboundInitializeBorderPacket walls = OffsetTestKit.first(helper, player, 0, ClientboundInitializeBorderPacket.class);
                ClientboundInitializeBorderPacket last = (ClientboundInitializeBorderPacket) northBundle(helper, player).get(2);
                assertSameBorder(helper, last, walls, "the bundle's last border");
                WorldBorder real = helper.getLevel().getWorldBorder();
                helper.assertFalse(last.getNewCenterX() == real.getCenterX() + offset.x() && last.getNewSize() == real.getSize(),
                        "the bundle's last border is the real one shifted");
            } finally {
                TestPlayers.leave(player);
            }
        });
        helper.succeed();
    }

    // The offset stays under a million blocks, so the target, a million blocks inside the edge, stays inside it
    @GameTest(environment = BORDER)
    public void limitKeepsTheNorthTargetInside(GameTestHelper helper) {
        helper.assertValueEqual(Offset.LIMIT, 999_984, "offset limit");
        helper.assertTrue(new WorldBorder().isWithinBounds(new BlockPos(NORTH + Offset.LIMIT, 64, -NORTH)),
                "the target at the largest offset is outside the whole-world border");
        helper.assertFalse(new WorldBorder().isWithinBounds(new BlockPos(NORTH + 1_000_000, 64, -NORTH)),
                "a million blocks out would still be inside");
        helper.assertTrue(NorthCompass.isNorthTarget(new BlockPos(17, 63, -NORTH)), "the north target is not known");
        helper.succeed();
    }

    @GameTest(environment = BORDER)
    public void plainSpawnShiftedWithCompassSpawnOff(GameTestHelper helper) {
        ServerPlayer player = withCompassSpawn(false, () -> OffsetTestKit.join(helper, "cbspawn", GameType.CREATIVE));
        try {
            Offset offset = CoordinateOffsets.offset(player);
            BlockPos spawn = OffsetTestKit.last(helper, player, 0, ClientboundSetDefaultSpawnPositionPacket.class).respawnData().pos();
            helper.assertValueEqual(spawn, offset.shift(helper.getLevel().getRespawnData().pos()), "spawn sent");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
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

    private static <T> T withCompassSpawn(boolean on, Supplier<T> body) {
        boolean before = Settings.get().hide.compassSpawn;
        Settings.get().hide.compassSpawn = on;
        try {
            return body.get();
        } finally {
            Settings.get().hide.compassSpawn = before;
        }
    }

    // The parts of the join's north compass bundle, the first bundle with a spawn in it
    private static List<Packet<?>> northBundle(GameTestHelper helper, ServerPlayer player) {
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundBundlePacket bundle) {
                List<Packet<?>> parts = new ArrayList<>();
                bundle.subPackets().forEach(parts::add);
                if (parts.stream().anyMatch(part -> part instanceof ClientboundSetDefaultSpawnPositionPacket)) {
                    helper.assertValueEqual(parts.size(), 3, "parts of the north bundle");
                    return parts;
                }
            }
        }
        helper.fail("no north compass bundle was sent");
        return List.of();
    }

    private static void assertSameBorder(GameTestHelper helper, ClientboundInitializeBorderPacket actual,
                                         ClientboundInitializeBorderPacket expected, String what) {
        helper.assertValueEqual(List.<Object>of(actual.getNewCenterX(), actual.getNewCenterZ(), actual.getOldSize(), actual.getNewSize(),
                        actual.getLerpTime()),
                List.<Object>of(expected.getNewCenterX(), expected.getNewCenterZ(), expected.getOldSize(), expected.getNewSize(),
                        expected.getLerpTime()), what);
    }

    // Where the client puts the east wall, which it keeps inside the world's edge
    private static double maxX(ClientboundInitializeBorderPacket packet) {
        return Math.min(packet.getNewCenterX() + packet.getNewSize() / 2.0, packet.getNewAbsoluteMaxSize());
    }
}
