package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Team;

import static bobbynooby.dev.YouOnlyLiveTwice.TEAM_NAME;

public class AdminViewGameTests {
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
