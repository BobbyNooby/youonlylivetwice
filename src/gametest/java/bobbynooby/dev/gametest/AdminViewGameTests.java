package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static bobbynooby.dev.YouOnlyLiveTwice.TEAM_NAME;

public class AdminViewGameTests {
    @GameTest
    public void toggleFlipsAndPersists(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TestPlayers.Recording admin = TestPlayers.joinRecording(helper, "avtoggle", GameType.SURVIVAL, BlockPos.ZERO);
        NameAndId who = admin.nameAndId();
        ServerPlayer online = admin;
        try {
            // The test server gives ops level 0 unless told otherwise, below what /yolt needs
            server.getPlayerList().op(who, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            run(admin, "yolt adminview");
            helper.assertTrue(said(admin, "Admin view on"), "the command did not reply \"Admin view on\"");
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
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = true;
            admin = TestPlayers.join(helper, "avdeop", GameType.SURVIVAL, BlockPos.ZERO);
            joined.add(admin);
            ServerPlayer far = TestPlayers.join(helper, "avdeopfar", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            joined.add(far);
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            TestPlayers.op(admin);
            helper.assertTrue(AdminView.toggle(admin), "toggle did not turn admin view on");
            helper.assertTrue(PlayerVisibility.known(admin).contains(far.getUUID()), "admin view does not know the far player");

            TestPlayers.deop(admin);
            helper.assertFalse(AdminView.isOn(admin), "admin view still counts after a deop");
            helper.assertFalse(PlayerVisibility.known(admin).contains(far.getUUID()), "a de-opped player still knows the far player");

            TestPlayers.op(admin);
            helper.assertTrue(AdminView.isOn(admin), "admin view did not come back with op");
            helper.assertTrue(PlayerVisibility.known(admin).contains(far.getUUID()), "a re-opped admin does not know the far player");
        } finally {
            hide.distantPlayers = distantBefore;
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

    @GameTest
    public void commandRequiresOp(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "avnonop", GameType.SURVIVAL, BlockPos.ZERO);
        try {
            // Off, so it is /yolt's own op check that refuses, not the command filter for non-ops
            config.restrictCommands = false;
            run(player, "yolt adminview");
            // Compared as text, since a command failure arrives wrapped in another component
            helper.assertTrue(said(player, Component.translatable("command.unknown.command").getString()),
                    "a non-op's /yolt adminview was not refused");
            helper.assertFalse(said(player, "Admin view on"), "a non-op was told admin view is on");
            helper.assertFalse(player.hasAttached(AdminView.ATTACHMENT), "a non-op's command changed admin view");
        } finally {
            config.restrictCommands = restrictBefore;
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
            NameTagHider.apply(server);
        }
        helper.succeed();
    }

    private static void assertAdminRuleKept(GameTestHelper helper, String mode) {
        ServerPlayer admin = TestPlayers.join(helper, "avtag", GameType.SURVIVAL, BlockPos.ZERO);
        ServerPlayer other = TestPlayers.join(helper, "avtagother", GameType.SURVIVAL, new BlockPos(1, 0, 0));
        try {
            AdminView.set(admin, true);
            PlayerVisibility.refresh(admin);
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, mode + ": admin's nametag rule");

            NameTagHider.apply(helper.getLevel().getServer());
            helper.assertValueEqual(lastRule(admin), Team.Visibility.ALWAYS, mode + ": admin's nametag rule after a team change");
            helper.assertValueEqual(lastRule(other), Team.Visibility.NEVER, mode + ": normal player's nametag rule after a team change");

            AdminView.set(admin, false);
            PlayerVisibility.refresh(admin);
            helper.assertValueEqual(lastRule(admin), Team.Visibility.NEVER, mode + ": nametag rule after admin view is off");
        } finally {
            AdminView.set(admin, false);
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
