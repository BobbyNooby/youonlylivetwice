package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.HardcoreStates;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// A player who dies, leaves from the death screen and joins again is put back on the death screen, and isn't told of
// the lost heart there, where the hotbar can't be seen; the respawn tells them, once
public class RejoinAloneClientTest implements FabricClientGameTest {
    // Longer than a line the join or the respawn sends takes to reach the client and show
    private static final int HOLD_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        UUID self = ctx.computeOnClient(mc -> mc.getGameProfile().id());
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> {}));
            try {
                int mark = ClientKit.mark();
                dieAndLeave(ctx, server, self);
                check(!ClientKit.overlay(mark).contains(ClientKit.LOST_A_HEART),
                        () -> "the client was told of the heart before it left: " + ClientKit.overlay(mark));
                comeBackAndRespawn(ctx, server, mark);
            } finally {
                ClientKit.runAll(List.of(
                        () -> server.runOnServer(s -> Curse.set(self, 0)),
                        () -> server.runOnServer(s -> putBack.run()),
                        // Only when a step failed with the client still in the world, so the server never stops under it
                        () -> leaveWorld(ctx),
                        // Every client test has to end on the title screen
                        () -> ctx.setScreen(TitleScreen::new)));
            }
        }
    }

    private static void dieAndLeave(ClientGameTestContext ctx, TestDedicatedServerContext server, UUID self) {
        // Not closed by a try: the death screen's own button ends it, and closing a connection that has ended throws
        TestDedicatedServerConnection connection = server.connect();
        connection.waitForChunksRender();
        ClientKit.die(ctx, server, connection);
        check(server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost) == 1,
                () -> "the death took " + server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost)
                        + " hearts, not 1");
        // As a player leaves from the death screen: its title screen button, then the yes of the confirm screen
        ClientKit.click(ctx, ClientKit.TITLE_SCREEN);
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.gui.screen() instanceof ConfirmScreen,
                mc -> "the death screen's title screen button opened " + ClientKit.screenName(mc) + ", not its confirm screen");
        ClientKit.click(ctx, ClientKit.TITLE_SCREEN);
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.level == null && mc.gui.screen() instanceof TitleScreen,
                mc -> "the client is on " + ClientKit.screenName(mc) + (mc.level == null ? "" : ", still in the world")
                        + " after leaving from the death screen");
        ClientKit.waitOnServer(server, ClientKit.LONG_TIMEOUT, s -> s.getPlayerList().getPlayer(self) == null,
                s -> "the server still has the client's player after it left");
        check(server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost) == 1,
                () -> "leaving changed the hearts lost to " + server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost));
    }

    private static void comeBackAndRespawn(ClientGameTestContext ctx, TestDedicatedServerContext server, int mark) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.gui.screen() instanceof DeathScreen,
                    mc -> "the client joined to " + ClientKit.screenName(mc) + ", not the death screen it left from");
            check(server.computeOnServer(s -> connection.getServerPlayer().isDeadOrDying()),
                    () -> "the server has the client's player alive after it joined again");
            ClientKit.holdOnClient(ctx, HOLD_TICKS, mc -> mc.gui.screen() instanceof DeathScreen
                            && !ClientKit.overlay(mark).contains(ClientKit.LOST_A_HEART),
                    mc -> mc.gui.screen() instanceof DeathScreen
                            ? "the client was told of the heart on the death screen: " + ClientKit.overlay(mark)
                            : "the client left the death screen for " + ClientKit.screenName(mc) + " by itself");
            ClientKit.screenshot(ctx, "hardcore-alone-rejoin-dead");

            ClientKit.respawn(ctx);
            ClientKit.waitForOverlay(ctx, mark, ClientKit.LOST_A_HEART);
            ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.getMaxHealth() == ClientKit.CURSED_HEALTH,
                    mc -> "the client's maximum health is " + mc.player.getMaxHealth() + " after the respawn, not "
                            + ClientKit.CURSED_HEALTH);
            ctx.waitTicks(ClientKit.HOTBAR_LINE_TICKS);
            ClientKit.screenshot(ctx, "hardcore-alone-rejoin-told");
            ClientKit.holdOnClient(ctx, HOLD_TICKS, mc -> told(mark) == 1,
                    mc -> "the client was told of the heart " + told(mark) + " times: " + ClientKit.overlay(mark));
        }
    }

    private static int told(int mark) {
        return Collections.frequency(ClientKit.overlay(mark), ClientKit.LOST_A_HEART);
    }

    // As Fabric's own close does it
    private static void leaveWorld(ClientGameTestContext ctx) {
        if (ctx.computeOnClient(mc -> mc.level == null)) {
            return;
        }
        ctx.runOnClient(mc -> {
            mc.level.disconnect(Component.literal("Disconnecting"));
            mc.disconnectWithSavingScreen();
        });
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.level == null,
                mc -> "the client is still in its world, on " + ClientKit.screenName(mc));
    }
}
