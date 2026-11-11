package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** What a player's own debug screen (F3) shows, and the server brand it names. Admin view gives an op the full screen back. */
public class DebugPrivacyClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        // The config outlives each test's server, and between servers nothing else reads it
        boolean brandWas = Settings.get().hide.serverBrand;
        try {
            ClientTests.withServer(ctx, server -> server.runOnServer(s -> {
                // The brand goes out as the client logs in
                Settings.get().hide.serverBrand = true;
            }), (server, connection) -> {
                boolean wasOp = server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId()));
                try {
                    showReducedDebugScreen(ctx);
                    showAdminDebugScreen(ctx, server, connection);
                } finally {
                    ClientTests.runAll(List.of(
                            // The client keeps the overlay up into the tests after this one otherwise
                            () -> ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false)),
                            () -> server.runOnServer(s -> {
                                ServerPlayer self = connection.getServerPlayer();
                                // Toggled, so the client is sent the reduced screen again
                                if (self.getAttachedOrElse(AdminView.ATTACHMENT, false)) {
                                    AdminView.toggle(self);
                                }
                                // Only undo the op this test gave
                                if (!wasOp) {
                                    TestPlayers.deop(self);
                                }
                            })));
                }
            });
        } finally {
            // Only once the server is gone, so a join that failed can't leave it on for the tests after this one
            Settings.get().hide.serverBrand = brandWas;
        }
    }

    private static void showReducedDebugScreen(ClientGameTestContext ctx) {
        // Sent at the join
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, Minecraft::showOnlyReducedInfo,
                mc -> "the client never got the reduced debug screen");
        ctx.runOnClient(mc -> {
            mc.debugEntries.setOverlayVisible(true);
            // The control: the screen is up, with the line that names the server's brand
            if (!mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.TPS)) {
                throw new AssertionError("the debug screen doesn't show its server line");
            }
            if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.PLAYER_POSITION)) {
                throw new AssertionError("the reduced debug screen shows the player's position");
            }
            String brand = mc.getConnection().serverBrand();
            if (!"vanilla".equals(brand)) {
                throw new AssertionError("the client was told the server's brand is " + brand + ", not vanilla");
            }
        });
        ClientTests.screenshot(ctx, "10-f3-reduced");
    }

    private static void showAdminDebugScreen(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                             TestDedicatedServerConnection connection) {
        server.runOnServer(s -> {
            ServerPlayer self = connection.getServerPlayer();
            // Admin view only counts for ops. Toggled, since set only writes the flag and sends the client nothing.
            TestPlayers.op(self);
            if (!AdminView.toggle(self)) {
                throw new AssertionError("admin view was already on, so the toggle turned it off");
            }
        });
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> !mc.showOnlyReducedInfo(),
                mc -> "admin view did not give the client the full debug screen");
        ctx.runOnClient(mc -> {
            // The default debug profile has it in the overlay
            if (!mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.PLAYER_POSITION)) {
                throw new AssertionError("the full debug screen doesn't show the player's position");
            }
        });
        ClientTests.screenshot(ctx, "10-f3-admin");
    }
}
