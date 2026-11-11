package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.NorthCompass;
import bobbynooby.dev.world.LiveSettings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Each test records what an online player is sent, and most change a switch, admin view, the world spawn or a border,
// so every one runs in a batch of its own
public class NorthCompassGameTests {
    private static final String DIMENSIONS = "you-only-live-twice-gametest:compass_north_dimensions";
    private static final String SET_SPAWN = "you-only-live-twice-gametest:compass_north_set_spawn";
    private static final String SWITCH_OFF = "you-only-live-twice-gametest:compass_north_switch_off";
    private static final String CONFIG = "you-only-live-twice-gametest:compass_north_config";
    private static final String ADMIN = "you-only-live-twice-gametest:compass_north_admin";
    private static final String RESPAWN = "you-only-live-twice-gametest:compass_north_respawn";
    private static final String MOVING_BORDER = "you-only-live-twice-gametest:compass_north_moving_border";
    private static final String DRIFT = "you-only-live-twice-gametest:compass_north_drift";
    // At least this far north, the needle is within a tenth of a degree for any holder in a 10,000 border
    private static final int FAR = 10_000_000;

    // The Nether and End borders get warnings of their own here, so a bundle that put back the old level's border fails
    @GameTest(environment = DIMENSIONS)
    public void compassTargetIsNorthInEveryDimension(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(nether != null && end != null, "the test server has no Nether or no End");
        WorldBorder netherBorder = nether.getWorldBorder();
        WorldBorder endBorder = end.getWorldBorder();
        int netherWarningBefore = netherBorder.getWarningBlocks();
        int endWarningBefore = endBorder.getWarningBlocks();
        ServerPlayer player = join(helper, "cnorth");
        try {
            netherBorder.setWarningBlocks(netherWarningBefore + 6);
            endBorder.setWarningBlocks(endWarningBefore + 8);
            assertNorth(helper, last(helper, spawnSends(player, 0)), player, helper.getLevel(), "the join's");

            player.teleportTo(nether, 37.5, 64.0, -50.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(player.level() == nether, "the player did not reach the Nether");
            assertNorth(helper, last(helper, spawnSends(player, afterRespawn(helper, player))), player, nether, "the Nether's");

            player.teleportTo(end, 41.5, 64.0, 23.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(player.level() == end, "the player did not reach the End");
            assertNorth(helper, last(helper, spawnSends(player, afterRespawn(helper, player))), player, end, "the End's");

            assertNeverReal(helper, player);
        } finally {
            netherBorder.setWarningBlocks(netherWarningBefore);
            endBorder.setWarningBlocks(endWarningBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla tells every player the new spawn at once; each gets their own north target instead
    @GameTest(environment = SET_SPAWN)
    public void vanillaSpawnPacketReplaced(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        LevelData.RespawnData spawnBefore = server.getRespawnData();
        ServerPlayer player = join(helper, "cnspawn");
        try {
            int mark = TestPlayers.sentPackets(player).size();
            BlockPos spawn = spawnBefore.pos().offset(5, 0, 5);
            CommandGameTests.run(server, "setworldspawn " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
            helper.assertValueEqual(server.getRespawnData().pos(), spawn, "the world spawn after /setworldspawn");
            List<List<Packet<?>>> sends = spawnSends(player, mark);
            helper.assertValueEqual(sends.size(), 1, "spawn sends after /setworldspawn");
            assertNorth(helper, sends.get(0), player, helper.getLevel(), "the /setworldspawn");
        } finally {
            server.setRespawnData(spawnBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Off, the real spawn goes out as vanilla sends it: no bundle, so no whole-world border either
    @GameTest(environment = SWITCH_OFF)
    public void switchOffSendsRealSpawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean before = hide.compassSpawn;
        ServerPlayer player = null;
        try {
            hide.compassSpawn = false;
            player = join(helper, "cnoff");
            List<List<Packet<?>>> sends = spawnSends(player, 0);
            helper.assertFalse(sends.isEmpty(), "no spawn sent at the join");
            for (List<Packet<?>> send : sends) {
                assertReal(helper, send, player, "a spawn sent at the join with hide.compassSpawn off");
            }
            helper.assertFalse(TestPlayers.sentPackets(player).stream().anyMatch(p -> p instanceof ClientboundBundlePacket bundle
                    && parts(bundle).stream().anyMatch(part -> part instanceof ClientboundInitializeBorderPacket)),
                    "a bundle with a border went out with hide.compassSpawn off");

            hide.compassSpawn = true;
            LiveSettings.applyAll(server);
            assertNorth(helper, last(helper, spawnSends(player, 0)), player, helper.getLevel(), "the switch coming on's");
        } finally {
            hide.compassSpawn = before;
            if (player != null) {
                TestPlayers.leave(player);
            }
        }
        helper.succeed();
    }

    // The key needs no code of its own in the command, and the change reaches players already online
    @GameTest(environment = CONFIG)
    public void configCommandSwitchesCompassSpawn(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        ServerPlayer player = join(helper, "cnconfig");
        try {
            helper.assertValueEqual(CommandGameTests.run(server, "yolt config hide.compassSpawn false"),
                    List.of("hide.compassSpawn changed from true to false."), "reply to turning hide.compassSpawn off");
            assertReal(helper, last(helper, spawnSends(player, 0)), player, "the spawn after /yolt config hide.compassSpawn false");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt config hide.compassSpawn true"),
                    List.of("hide.compassSpawn changed from false to true."), "reply to turning hide.compassSpawn on");
            assertNorth(helper, last(helper, spawnSends(player, 0)), player, helper.getLevel(), "the /yolt config hide.compassSpawn true");
        } finally {
            ConfigCommandGameTests.restore(server, fileBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = ADMIN)
    public void adminViewGivesRealSpawn(GameTestHelper helper) {
        ServerPlayer admin = join(helper, "cnadmin");
        try {
            // Only what the op itself sends: the join's target is already there
            int beforeOp = TestPlayers.sentPackets(admin).size();
            TestPlayers.op(admin);
            helper.assertValueEqual(spawnSends(admin, beforeOp).size(), 0, "spawn sends for an op without admin view");
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            assertReal(helper, last(helper, spawnSends(admin, 0)), admin, "the spawn with admin view on");
            helper.assertFalse(AdminView.toggle(admin), "toggle did not turn admin view off");
            assertNorth(helper, last(helper, spawnSends(admin, 0)), admin, helper.getLevel(), "the admin view off");

            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on again");
            TestPlayers.deop(admin);
            assertNorth(helper, last(helper, spawnSends(admin, 0)), admin, helper.getLevel(), "the deop with admin view on");
            TestPlayers.op(admin);
            assertReal(helper, last(helper, spawnSends(admin, 0)), admin, "the spawn after the op came back");
        } finally {
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(admin);
        }
        helper.succeed();
    }

    // A respawn from the Nether into the overworld sends the spawn twice. Both must be for the new player in the overworld,
    // with the overworld's border, which the Nether's warning here tells apart.
    @GameTest(environment = RESPAWN)
    public void respawnTargetIsForTheDestination(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the test server has no Nether");
        WorldBorder netherBorder = nether.getWorldBorder();
        int netherWarningBefore = netherBorder.getWarningBlocks();
        ServerPlayer player = join(helper, "cnrespawn");
        ServerPlayer current = player;
        try {
            netherBorder.setWarningBlocks(netherWarningBefore + 6);
            player.teleportTo(nether, -20.5, 64.0, 90.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(player.level() == nether, "the player did not reach the Nether");
            BlockPos spot = helper.absolutePos(new BlockPos(2, 1, 2));
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    LevelData.RespawnData.of(helper.getLevel().dimension(), spot, 0.0F, 0.0F), true), false);
            current = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(current.level() == helper.getLevel(), "the respawn did not bring the player back to the overworld");
            List<List<Packet<?>>> sends = spawnSends(current, afterRespawn(helper, current));
            helper.assertValueEqual(sends.size(), 2, "spawn sends after the respawn packet");
            for (List<Packet<?>> send : sends) {
                assertNorth(helper, send, current, helper.getLevel(), "the respawn's");
            }
            assertNeverReal(helper, current);
        } finally {
            netherBorder.setWarningBlocks(netherWarningBefore);
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    // The bundle's last border must be the live one, lerp and warnings included, or a moving border would stop or jump
    @GameTest(environment = MOVING_BORDER)
    public void bundleRestoresMovingBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldBorder border = level.getWorldBorder();
        double sizeBefore = border.getSize();
        int warningBlocksBefore = border.getWarningBlocks();
        int warningTimeBefore = border.getWarningTime();
        ServerPlayer player = null;
        try {
            border.setWarningBlocks(warningBlocksBefore + 12);
            border.setWarningTime(warningTimeBefore + 7);
            border.lerpSizeBetween(sizeBefore, sizeBefore - 1000.0, 12_000L, level.getGameTime());
            player = join(helper, "cnmoving");
            List<Packet<?>> send = last(helper, spawnSends(player, 0));
            assertNorth(helper, send, player, level, "the join's");
            ClientboundInitializeBorderPacket restored = (ClientboundInitializeBorderPacket) send.get(2);
            helper.assertValueEqual(restored.getOldSize(), sizeBefore, "the restored border's size");
            helper.assertValueEqual(restored.getNewSize(), sizeBefore - 1000.0, "the restored border's target size");
            helper.assertTrue(restored.getLerpTime() > 0L, "the restored border isn't moving");
            helper.assertValueEqual(restored.getWarningBlocks(), warningBlocksBefore + 12, "the restored border's warning distance");
            helper.assertValueEqual(restored.getWarningTime(), warningTimeBefore + 7, "the restored border's warning time");
        } finally {
            border.setSize(sizeBefore);
            border.setWarningBlocks(warningBlocksBefore);
            border.setWarningTime(warningTimeBefore);
            if (player != null) {
                TestPlayers.leave(player);
            }
        }
        helper.succeed();
    }

    // A few thousand blocks sideways moves the needle by a fraction of a degree, so nothing is sent; a million is past
    // half a degree from anywhere in the test area, so the target follows. This is what the once-a-second check runs.
    @GameTest(environment = DRIFT)
    public void driftSendsTheTargetAgain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "cndrift");
        try {
            int mark = TestPlayers.sentPackets(player).size();
            NorthCompass.recheckAll(server);
            helper.assertValueEqual(spawnSends(player, mark).size(), 0, "spawn sends with no move");

            player.snapTo(player.getX() + 3000.0, player.getY(), player.getZ());
            NorthCompass.recheckAll(server);
            helper.assertValueEqual(spawnSends(player, mark).size(), 0, "spawn sends after 3,000 blocks sideways");

            player.snapTo(player.getX() + 1_000_000.0, player.getY(), player.getZ());
            NorthCompass.recheckAll(server);
            List<List<Packet<?>>> sends = spawnSends(player, mark);
            helper.assertValueEqual(sends.size(), 1, "spawn sends after a million blocks sideways");
            assertNorth(helper, sends.get(0), player, helper.getLevel(), "the drift's");
            NorthCompass.recheckAll(server);
            helper.assertValueEqual(spawnSends(player, mark).size(), 1, "spawn sends once the target followed");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper.getLevel().getServer(), name, GameType.SURVIVAL, helper.absolutePos(new BlockPos(1, 1, 1)));
    }

    // Our bundle: the border a client starts a level with, a spawn far north of the player in level and inside that
    // border, then level's live border. A spawn alone, or a bundle without the border put back, fails here.
    private static void assertNorth(GameTestHelper helper, List<Packet<?>> send, ServerPlayer player, ServerLevel level,
                                    String what) {
        helper.assertValueEqual(send.size(), 3, what + " packets in the spawn's bundle");
        helper.assertTrue(send.get(0) instanceof ClientboundInitializeBorderPacket, what + " bundle doesn't start with a border");
        helper.assertValueEqual(borderOf(send.get(0)), borderOf(new ClientboundInitializeBorderPacket(new WorldBorder())),
                what + " first border");
        helper.assertTrue(send.get(1) instanceof ClientboundSetDefaultSpawnPositionPacket, what + " bundle has no spawn second");
        GlobalPos target = ((ClientboundSetDefaultSpawnPositionPacket) send.get(1)).respawnData().globalPos();
        helper.assertValueEqual(target.dimension(), level.dimension(), what + " target's dimension");
        helper.assertValueEqual(target.pos().getX(), player.getBlockX(), what + " target's X");
        helper.assertTrue(player.getZ() - target.pos().getZ() > FAR, what + " target is at Z " + target.pos().getZ()
                + ", not far north of the player at Z " + player.getZ());
        // The client would move a target outside this border to the border's centre
        helper.assertTrue(new WorldBorder().isWithinBounds(target.pos()), what + " target is outside the first border");
        helper.assertTrue(send.get(2) instanceof ClientboundInitializeBorderPacket, what + " bundle doesn't end with a border");
        helper.assertValueEqual(borderOf(send.get(2)), borderOf(new ClientboundInitializeBorderPacket(level.getWorldBorder())),
                what + " restored border");
    }

    // Vanilla's own packet, alone, with the server's real spawn
    private static void assertReal(GameTestHelper helper, List<Packet<?>> send, ServerPlayer player, String what) {
        helper.assertValueEqual(send.size(), 1, what + ": packets sent with it");
        helper.assertValueEqual(((ClientboundSetDefaultSpawnPositionPacket) send.get(0)).respawnData(),
                player.level().getRespawnData(), what);
    }

    // With the switch on, the server's real spawn never reached this client
    private static void assertNeverReal(GameTestHelper helper, ServerPlayer player) {
        for (List<Packet<?>> send : spawnSends(player, 0)) {
            helper.assertValueEqual(send.size(), 3, "a spawn went out without its bundle");
        }
    }

    // Each spawn sent to the player from packet "from" on, oldest first: a bundle's packets, or vanilla's packet alone
    private static List<List<Packet<?>>> spawnSends(ServerPlayer player, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        List<List<Packet<?>>> sends = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundSetDefaultSpawnPositionPacket) {
                sends.add(List.of(packet));
            } else if (packet instanceof ClientboundBundlePacket bundle) {
                List<Packet<?>> parts = parts(bundle);
                if (parts.stream().anyMatch(part -> part instanceof ClientboundSetDefaultSpawnPositionPacket)) {
                    sends.add(parts);
                }
            }
        }
        return sends;
    }

    private static List<Packet<?>> parts(ClientboundBundlePacket bundle) {
        List<Packet<?>> parts = new ArrayList<>();
        bundle.subPackets().forEach(parts::add);
        return parts;
    }

    private static List<Packet<?>> last(GameTestHelper helper, List<List<Packet<?>>> sends) {
        helper.assertFalse(sends.isEmpty(), "no spawn was sent");
        return sends.getLast();
    }

    // Everything the client takes from a border packet
    private static List<Object> borderOf(Packet<?> packet) {
        ClientboundInitializeBorderPacket border = (ClientboundInitializeBorderPacket) packet;
        return List.of(border.getNewCenterX(), border.getNewCenterZ(), border.getOldSize(), border.getNewSize(),
                border.getLerpTime(), border.getNewAbsoluteMaxSize(), border.getWarningBlocks(), border.getWarningTime());
    }

    // The respawn packet makes the client's new level, so what counts comes after the last one
    private static int afterRespawn(GameTestHelper helper, ServerPlayer player) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        for (int i = packets.size() - 1; i >= 0; i--) {
            if (packets.get(i) instanceof ClientboundRespawnPacket) {
                return i + 1;
            }
        }
        helper.fail("no respawn packet was sent");
        return packets.size();
    }
}
