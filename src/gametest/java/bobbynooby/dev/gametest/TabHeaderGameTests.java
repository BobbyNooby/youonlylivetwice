package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.TabHeaders;
import bobbynooby.dev.world.LiveSettings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TabHeaderGameTests {
    private static final String TAB_HEADER = "you-only-live-twice-gametest:tab_header";

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

    // A batch of its own: a reload in another test would send this player its header again
    @GameTest(environment = TAB_HEADER, maxTicks = 30)
    public void headerSentOncePerJoin(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "tabonce", GameType.SURVIVAL, BlockPos.ZERO);
        boolean waiting = false;
        try {
            helper.runAfterDelay(20, () -> {
                try {
                    helper.assertValueEqual(tabListPackets(player).size(), 1, "tab list packets 20 ticks after joining");
                } finally {
                    TestPlayers.leave(player);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(player);
            }
        }
    }

    // The switch is restored and applied again in the same tick, so other tests never see it off
    @GameTest
    public void headerClearedWhenSwitchedOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean before = hide.tabHeader;
        ServerPlayer player = TestPlayers.join(helper, "tabclear", GameType.SURVIVAL, BlockPos.ZERO);
        try {
            hide.tabHeader = false;
            LiveSettings.applyAll(server);
            List<ClientboundTabListPacket> sent = tabListPackets(player);
            helper.assertFalse(sent.isEmpty(), "no tab list packet after the switch went off");
            ClientboundTabListPacket last = sent.getLast();
            helper.assertValueEqual(last.header().getString(), "", "header after the switch went off");
            helper.assertValueEqual(last.footer().getString(), "", "footer after the switch went off");
        } finally {
            hide.tabHeader = before;
            LiveSettings.applyAll(server);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static List<ClientboundTabListPacket> tabListPackets(ServerPlayer player) {
        List<ClientboundTabListPacket> packets = new ArrayList<>();
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundTabListPacket tabList) {
                packets.add(tabList);
            }
        }
        return packets;
    }
}
