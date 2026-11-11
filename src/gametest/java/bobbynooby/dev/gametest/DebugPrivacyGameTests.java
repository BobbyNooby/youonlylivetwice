package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.world.LiveSettings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

// The game rule test sends every online player the flag, so these all run in a batch of their own
public class DebugPrivacyGameTests {
    private static final String DEBUG_PRIVACY = "you-only-live-twice-gametest:debug_privacy";
    private static final byte REDUCED = EntityEvent.REDUCED_DEBUG_INFO;
    private static final byte FULL = EntityEvent.FULL_DEBUG_INFO;
    private static final byte NONE = 0;

    @GameTest(environment = DEBUG_PRIVACY)
    public void reducedDebugSentAtJoin(GameTestHelper helper) {
        ServerPlayer player = join(helper, "dpjoin");
        try {
            helper.assertValueEqual(lastDebugEvent(player, 0), REDUCED, "debug flag after joining");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = DEBUG_PRIVACY)
    public void adminViewLiftsReducedDebug(GameTestHelper helper) {
        ServerPlayer admin = join(helper, "dpadmin");
        try {
            TestPlayers.op(admin);
            helper.assertValueEqual(lastDebugEvent(admin, 0), REDUCED, "debug flag of an op without admin view");
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            helper.assertValueEqual(lastDebugEvent(admin, 0), FULL, "debug flag with admin view on");
            helper.assertFalse(AdminView.toggle(admin), "toggle did not turn admin view off");
            helper.assertValueEqual(lastDebugEvent(admin, 0), REDUCED, "debug flag with admin view off again");

            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on again");
            TestPlayers.deop(admin);
            helper.assertValueEqual(lastDebugEvent(admin, 0), REDUCED, "debug flag after a deop with admin view on");
            TestPlayers.op(admin);
            helper.assertValueEqual(lastDebugEvent(admin, 0), FULL, "debug flag after the op came back");
        } finally {
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(admin);
        }
        helper.succeed();
    }

    @GameTest(environment = DEBUG_PRIVACY)
    public void switchOffGivesFullDebug(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean before = hide.debugScreen;
        ServerPlayer player = join(helper, "dpswitch");
        try {
            hide.debugScreen = false;
            LiveSettings.applyAll(server);
            helper.assertValueEqual(lastDebugEvent(player, 0), FULL, "debug flag with hide.debugScreen off");
            hide.debugScreen = true;
            LiveSettings.applyAll(server);
            helper.assertValueEqual(lastDebugEvent(player, 0), REDUCED, "debug flag with hide.debugScreen on again");
        } finally {
            hide.debugScreen = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The key needs no code of its own in the command: it is found from the config's fields
    @GameTest(environment = DEBUG_PRIVACY)
    public void configCommandSwitchesDebugScreen(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        ServerPlayer player = join(helper, "dpconfig");
        try {
            helper.assertValueEqual(CommandGameTests.run(server, "yolt config hide.debugScreen false"),
                    List.of("hide.debugScreen changed from true to false."), "reply to turning hide.debugScreen off");
            helper.assertValueEqual(lastDebugEvent(player, 0), FULL, "debug flag after /yolt config hide.debugScreen false");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt config hide.debugScreen true"),
                    List.of("hide.debugScreen changed from false to true."), "reply to turning hide.debugScreen on");
            helper.assertValueEqual(lastDebugEvent(player, 0), REDUCED, "debug flag after /yolt config hide.debugScreen true");
        } finally {
            ConfigCommandGameTests.restore(server, fileBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A spot of its own, so the respawn doesn't go looking for a random one
    @GameTest(environment = DEBUG_PRIVACY)
    public void reducedDebugResentAfterRespawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = join(helper, "dprespawn");
        ServerPlayer current = player;
        try {
            BlockPos spot = helper.absolutePos(new BlockPos(2, 1, 2));
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    LevelData.RespawnData.of(helper.getLevel().dimension(), spot, 0.0F, 0.0F), true), false);
            current = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(current != player, "the respawn kept the old player object");
            int respawn = lastRespawnPacket(current);
            helper.assertTrue(respawn >= 0, "the respawn sent no respawn packet");
            helper.assertValueEqual(lastDebugEvent(current, respawn + 1), REDUCED, "debug flag sent after the respawn packet");
        } finally {
            TestPlayers.leave(current);
        }
        helper.succeed();
    }

    @GameTest(environment = DEBUG_PRIVACY)
    public void reducedDebugResentAfterDimensionChange(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        ServerPlayer player = join(helper, "dpnether");
        try {
            player.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true);
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            int respawn = lastRespawnPacket(player);
            helper.assertTrue(respawn >= 0, "the dimension change sent no respawn packet");
            helper.assertValueEqual(lastDebugEvent(player, respawn + 1), REDUCED, "debug flag sent after the respawn packet");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Vanilla sends every player the rule's own flag when it changes, which would give everyone the full screen
    @GameTest(environment = DEBUG_PRIVACY)
    public void gameRuleChangeKeepsPrivacy(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        boolean ruleBefore = rules.get(GameRules.REDUCED_DEBUG_INFO);
        ServerPlayer normal = join(helper, "dprulenormal");
        ServerPlayer admin = join(helper, "dpruleadmin");
        try {
            TestPlayers.op(admin);
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            rules.set(GameRules.REDUCED_DEBUG_INFO, true, server);
            helper.assertValueEqual(lastDebugEvent(normal, 0), REDUCED, "normal player's debug flag with the rule on");
            helper.assertValueEqual(lastDebugEvent(admin, 0), REDUCED, "admin's debug flag with the rule on");
            rules.set(GameRules.REDUCED_DEBUG_INFO, false, server);
            helper.assertValueEqual(lastDebugEvent(normal, 0), REDUCED, "normal player's debug flag after the rule went off");
            helper.assertValueEqual(lastDebugEvent(admin, 0), FULL, "admin's debug flag after the rule went off");
        } finally {
            rules.set(GameRules.REDUCED_DEBUG_INFO, ruleBefore, server);
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(normal);
            TestPlayers.leave(admin);
        }
        helper.succeed();
    }

    @GameTest(environment = DEBUG_PRIVACY)
    public void vanillaRuleStillWinsForAdmins(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        boolean ruleBefore = rules.get(GameRules.REDUCED_DEBUG_INFO);
        ServerPlayer admin = join(helper, "dpruleop");
        try {
            TestPlayers.op(admin);
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            helper.assertValueEqual(lastDebugEvent(admin, 0), FULL, "admin's debug flag before the rule");
            rules.set(GameRules.REDUCED_DEBUG_INFO, true, server);
            helper.assertValueEqual(lastDebugEvent(admin, 0), REDUCED, "admin's debug flag once the rule is on");
            // Turning admin view on again while the rule is on
            helper.assertFalse(AdminView.toggle(admin), "toggle did not turn admin view off");
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on again");
            helper.assertValueEqual(lastDebugEvent(admin, 0), REDUCED, "admin's debug flag after admin view came on under the rule");
        } finally {
            rules.set(GameRules.REDUCED_DEBUG_INFO, ruleBefore, server);
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(admin);
        }
        helper.succeed();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper.getLevel().getServer(), name, GameType.SURVIVAL, helper.absolutePos(new BlockPos(1, 1, 1)));
    }

    // The debug flag last sent for the player's own entity from packet number "from" on, or NONE. Op level changes come in
    // the same kind of packet, so only the two debug events count.
    private static byte lastDebugEvent(ServerPlayer player, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        byte last = NONE;
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundEntityEventPacket event && event.getEntity(player.level()) == player
                    && (event.getEventId() == REDUCED || event.getEventId() == FULL)) {
                last = event.getEventId();
            }
        }
        return last;
    }

    // The respawn packet makes the client's new player, so a flag must come after the last one; -1 when none was sent
    private static int lastRespawnPacket(ServerPlayer player) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        for (int i = packets.size() - 1; i >= 0; i--) {
            if (packets.get(i) instanceof ClientboundRespawnPacket) {
                return i;
            }
        }
        return -1;
    }
}
