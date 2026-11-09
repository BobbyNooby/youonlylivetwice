package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.TabHeaders;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;

public class TabHeaderGameTests {
    @GameTest
    public void headerEntryRemovedOnLeave(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "tabhead", GameType.SURVIVAL, BlockPos.ZERO);
        UUID uuid = player.getUUID();
        boolean left = false;
        try {
            helper.assertTrue(TabHeaders.has(uuid), "no header after join");
            TestPlayers.leave(player);
            left = true;
            helper.assertTrue(!TabHeaders.has(uuid), "header still stored after leave");
        } finally {
            if (!left) {
                TestPlayers.leave(player);
            }
        }
        helper.succeed();
    }

    // Turning the switch on mid-session must also cover players who joined while it was off
    @GameTest
    public void headerPickedWithSwitchOff(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean before = hide.tabHeader;
        hide.tabHeader = false;
        ServerPlayer player;
        try {
            player = TestPlayers.join(helper, "taboff", GameType.SURVIVAL, BlockPos.ZERO);
        } finally {
            hide.tabHeader = before;
        }
        try {
            helper.assertTrue(TabHeaders.has(player.getUUID()), "no header for a player who joined with the switch off");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void sendAllSkipsUnknownPlayer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "tabskip", GameType.SURVIVAL, BlockPos.ZERO);
        try {
            TabHeaders.remove(player.getUUID());
            TabHeaders.sendAll(helper.getLevel().getServer().getPlayerList());
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }
}
