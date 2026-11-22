package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.HardcoreStates;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.level.GameType;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

// The Curse of the Undead as a real client meets it: a survival player dies, comes back with the death screen's own
// Respawn, and has nine hearts and the line that says one was lost. The curse ships on, and the settings in use are the
// defaults.
public class CurseAloneClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        UUID self = ctx.computeOnClient(mc -> mc.getGameProfile().id());
        ClientKit.withServer(ctx, (server, connection) -> {
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(c -> {}));
            try {
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the client's player isn't in survival, where a death counts");
                check(server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost) == 0,
                        () -> "the curse has taken hearts before the death");
                check(ctx.computeOnClient(mc -> mc.player.getMaxHealth()) == ClientKit.FULL_HEALTH,
                        () -> "the client's maximum health is " + ctx.computeOnClient(mc -> mc.player.getMaxHealth())
                                + " before the death");

                int mark = ClientKit.mark();
                ClientKit.die(ctx, server, connection);
                check(server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost) == 1,
                        () -> "the death took " + server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost)
                                + " hearts, not 1");
                // The curse saves a death it counts at once, into the world's own folder
                check(server.computeOnServer(s -> Files.isRegularFile(ClientBridge.storeFile(s))),
                        () -> "no " + server.computeOnServer(ClientBridge::storeFile) + " after the death");
                // The line waits for the respawn, since the death screen hides the hotbar
                ClientKit.holdOnClient(ctx, ClientKit.HOLD_TICKS, mc -> mc.gui.screen() instanceof DeathScreen
                                && !ClientKit.overlay(mark).contains(ClientKit.LOST_A_HEART),
                        mc -> mc.gui.screen() instanceof DeathScreen
                                ? "the client was told of the heart on the death screen: " + ClientKit.overlay(mark)
                                : "the client left the death screen for " + ClientKit.screenName(mc) + " by itself");

                ClientKit.respawn(ctx);
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.getMaxHealth() == ClientKit.CURSED_HEALTH,
                        mc -> "the client's maximum health is " + mc.player.getMaxHealth() + " after the respawn, not "
                                + ClientKit.CURSED_HEALTH);
                ClientKit.waitForOverlay(ctx, mark, ClientKit.LOST_A_HEART);
                ctx.waitTicks(ClientKit.HOTBAR_LINE_TICKS);
                ClientKit.screenshot(ctx, "hardcore-alone-curse");
            } finally {
                ClientKit.runAll(List.of(
                        () -> server.runOnServer(s -> Curse.set(self, 0)),
                        () -> server.runOnServer(s -> putBack.run())));
            }
        });
    }
}
