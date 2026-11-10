package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.NameTagTeam;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.jsonrpc.internalapi.MinecraftOperatorListServiceImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.List;

import static bobbynooby.dev.visibility.NameTagTeam.TEAM_NAME;

public class AdminViewGameTests {
    @GameTest
    public void toggleFlipsAndPersists(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TestPlayers.Recording admin = TestPlayers.joinRecording(helper, "avtoggle", GameType.SURVIVAL, BlockPos.ZERO);
        NameAndId who = admin.nameAndId();
        ServerPlayer online = admin;
        try {
            TestPlayers.op(admin);
            run(admin, "yolt adminview");
            helper.assertTrue(said(admin, "Admin view on."), "the command did not reply \"Admin view on.\"");
            helper.assertTrue(AdminView.isOn(admin), "the command did not turn admin view on");

            // Leaving saves the player, like a real logout
            TestPlayers.leave(admin);
            // So the finally block can't remove it twice if joining again fails
            online = null;
            online = TestPlayers.rejoin(helper, admin.getGameProfile(), GameType.SURVIVAL, BlockPos.ZERO);
            helper.assertTrue(AdminView.isOn(online), "admin view was lost on a relog");
            helper.assertValueEqual(lastRule(online), Team.Visibility.ALWAYS, "nametag rule at login with admin view on");

            helper.assertFalse(AdminView.toggle(online), "toggling again did not turn admin view off");
            helper.assertFalse(AdminView.isOn(online), "admin view still on after toggling it off");
        } finally {
            server.getPlayerList().deop(who);
            if (online != null) {
                AdminView.set(online, false);
                TestPlayers.leave(online);
            }
        }
        helper.succeed();
    }

    // Admin view only counts for ops, so a deop must take it away at once and an op give it back
    @GameTest
    public void deopDropsAdminView(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean tabListBefore = hide.tabList;
        boolean nametagsBefore = hide.nametags;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = true;
            hide.tabList = true;
            hide.nametags = true;
            admin = TestPlayers.join(helper, "avdeop", GameType.SURVIVAL, BlockPos.ZERO);
            joined.add(admin);
            ServerPlayer far = TestPlayers.join(helper, "avdeopfar", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            joined.add(far);
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            TestPlayers.op(admin);
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            helper.assertTrue(PlayerVisibility.known(admin).contains(far.getUUID()), "admin view does not know the far player");
            helper.assertTrue(VisibilityGameTests.listedFor(admin, admin), "admin view does not list the admin");
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, "nametag rule with admin view on");

            TestPlayers.deop(admin);
            helper.assertFalse(AdminView.isOn(admin), "admin view still counts after a deop");
            helper.assertFalse(PlayerVisibility.known(admin).contains(far.getUUID()), "a de-opped player still knows the far player");
            helper.assertFalse(VisibilityGameTests.listedFor(admin, admin), "a de-opped player is still listed in its own tab list");
            helper.assertValueEqual(lastRule(admin), Team.Visibility.NEVER, "nametag rule after a deop");

            TestPlayers.op(admin);
            helper.assertTrue(AdminView.isOn(admin), "admin view did not come back with op");
            helper.assertTrue(PlayerVisibility.known(admin).contains(far.getUUID()), "a re-opped admin does not know the far player");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.tabList = tabListBefore;
            hide.nametags = nametagsBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            for (ServerPlayer player : joined) {
                TestPlayers.leave(player);
            }
        }
        helper.succeed();
    }

    // The test server has no management API, so this empties the list the way operators/clear does and calls our hook's helper.
    // Emptying the list deops everyone online, so this runs on its own.
    @GameTest(environment = "you-only-live-twice-gametest:solo")
    public void clearedOpListDropsAdminView(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerOpListEntry> opsBefore = List.copyOf(server.getPlayerList().getOps().getEntries());
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = true;
            admin = TestPlayers.join(helper, "avclear", GameType.SURVIVAL, BlockPos.ZERO);
            joined.add(admin);
            ServerPlayer far = TestPlayers.join(helper, "avclearfar", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            joined.add(far);
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            TestPlayers.op(admin);
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            helper.assertTrue(PlayerVisibility.known(admin).contains(far.getUUID()), "admin view does not know the far player");

            server.getPlayerList().getOps().clear();
            AdminView.onOpListCleared(server.getPlayerList());
            helper.assertFalse(AdminView.isOn(admin), "admin view still counts after the op list was cleared");
            helper.assertFalse(PlayerVisibility.known(admin).contains(far.getUUID()), "a de-opped player still knows the far player");
            // Nothing else loads the class our hook is on here; loading it applies the hook, which fails if clear is gone
            helper.assertTrue(MinecraftOperatorListServiceImpl.class.getDeclaredMethods().length > 0, "the operator list service has no methods");
        } finally {
            hide.distantPlayers = distantBefore;
            if (admin != null) {
                AdminView.set(admin, false);
            }
            for (ServerOpListEntry entry : opsBefore) {
                server.getPlayerList().getOps().add(entry);
            }
            for (ServerPlayer player : joined) {
                TestPlayers.leave(player);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void commandRequiresOp(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean skinChoiceBefore = config.skins.allowPlayerChoice;
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "avnonop", GameType.SURVIVAL, BlockPos.ZERO);
        try {
            // Off, so it is /yolt's own op check that refuses, not the command filter for non-ops
            config.restrictCommands = false;
            config.skins.allowPlayerChoice = true;
            run(player, "yolt adminview");
            // Compared as text, since a command failure arrives wrapped in another component. A non-op may use /yolt
            // itself, for /yolt skin, so it is adminview that they can't use.
            helper.assertTrue(said(player, Component.translatable("command.unknown.argument").getString()),
                    "a non-op's /yolt adminview was not refused");
            helper.assertFalse(said(player, "Admin view on."), "a non-op was told admin view is on");
            helper.assertFalse(player.hasAttached(AdminView.ATTACHMENT), "a non-op's command changed admin view");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = skinChoiceBefore;
            AdminView.set(player, false);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A /team modify or a reload sends our team's settings to everyone again, which must not undo an admin's rule
    @GameTest
    public void adminKeepsNametagsThroughTeamChanges(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean nametagsBefore = hide.nametags;
        try {
            hide.nametags = true;
            hide.distantPlayers = true;
            assertAdminRuleKept(helper, "per viewer");
            hide.distantPlayers = false;
            assertAdminRuleKept(helper, "shared");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.nametags = nametagsBefore;
            // The test re-applied the team's settings, so match them to the live switches again
            NameTagTeam.apply(server);
        }
        helper.succeed();
    }

    // What /team remove and /team add do. Removing our team takes every player out of it, so this runs on its own.
    @GameTest(environment = "you-only-live-twice-gametest:solo")
    public void adminKeepsNametagsWhenTeamIsMadeAgain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerScoreboard scoreboard = server.getScoreboard();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean nametagsBefore = hide.nametags;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = false;
            hide.nametags = true;
            admin = TestPlayers.join(helper, "avteam", GameType.SURVIVAL, BlockPos.ZERO);
            joined.add(admin);
            ServerPlayer other = TestPlayers.join(helper, "avteamother", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            joined.add(other);
            TestPlayers.op(admin);
            AdminView.set(admin, true);
            PlayerVisibility.refresh(admin);

            scoreboard.removePlayerTeam(scoreboard.getPlayerTeam(TEAM_NAME));
            scoreboard.addPlayerTeam(TEAM_NAME);
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, "admin's nametag rule after the team was made again");

            // The new team has no names on any client yet, so forgetting a player must not send a LEAVE
            int mark = TestPlayers.sentPackets(other).size();
            hide.distantPlayers = true;
            PlayerVisibility.onPairingStop(other, admin);
            helper.assertValueEqual(NameTagGameTests.moves(other, admin, mark), List.of(), "team changes after the team was made again");

            // A reload puts our settings and the online players back on the new team
            NameTagTeam.apply(server);
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, "admin's nametag rule after a reload");
            helper.assertValueEqual(lastRule(other), Team.Visibility.NEVER, "normal player's nametag rule after a reload");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.nametags = nametagsBefore;
            NameTagTeam.apply(server);
            // Back in the team, as joining would have put them
            PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getTeam() == null) {
                    scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
                }
            }
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            for (ServerPlayer player : joined) {
                TestPlayers.leave(player);
            }
        }
        helper.succeed();
    }

    private static void assertAdminRuleKept(GameTestHelper helper, String mode) {
        ServerPlayer admin = TestPlayers.join(helper, "avtag", GameType.SURVIVAL, BlockPos.ZERO);
        ServerPlayer other = TestPlayers.join(helper, "avtagother", GameType.SURVIVAL, new BlockPos(1, 0, 0));
        try {
            TestPlayers.op(admin);
            AdminView.set(admin, true);
            PlayerVisibility.refresh(admin);
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, mode + ": admin's nametag rule");

            NameTagTeam.apply(helper.getLevel().getServer());
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, mode + ": admin's nametag rule after a team change");
            helper.assertValueEqual(lastRule(other), Team.Visibility.NEVER, mode + ": normal player's nametag rule after a team change");

            AdminView.set(admin, false);
            PlayerVisibility.refresh(admin);
            helper.assertValueEqual(lastRule(admin), Team.Visibility.NEVER, mode + ": nametag rule after admin view is off");
        } finally {
            AdminView.set(admin, false);
            TestPlayers.deop(admin);
            TestPlayers.leave(admin);
            TestPlayers.leave(other);
        }
    }

    private static void run(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static boolean said(TestPlayers.Recording player, String text) {
        for (Component message : player.messages) {
            if (message.getString().equals(text)) {
                return true;
            }
        }
        return false;
    }

    // The rule this client has for our team: the newest packet that carried one
    private static Team.Visibility lastRule(ServerPlayer viewer) {
        Team.Visibility rule = null;
        for (Packet<?> packet : TestPlayers.sentPackets(viewer)) {
            if (packet instanceof ClientboundSetPlayerTeamPacket team && team.getName().equals(TEAM_NAME) && team.getParameters().isPresent()) {
                rule = team.getParameters().get().nameTagVisibility();
            }
        }
        return rule;
    }
}
