package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Checks what a real client ends up holding, rather than the packets the server meant to send
public class VisibilityClientTest implements FabricClientGameTest {
    private static final int NEAR_BLOCKS = 5;
    private static final int FAR_BLOCKS = 1000;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            boolean distantWas = server.computeOnServer(s -> Settings.get().hide.distantPlayers);
            boolean tabListWas = server.computeOnServer(s -> Settings.get().hide.tabList);
            boolean wasOp = server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId()));
            List<ServerPlayer> joined = new ArrayList<>();
            try {
                server.runOnServer(s -> {
                    Settings.get().hide.distantPlayers = true;
                    Settings.get().hide.tabList = true;
                });
                ServerPlayer near = join(server, connection, joined, "Near", NEAR_BLOCKS);
                ServerPlayer far = join(server, connection, joined, "Far", FAR_BLOCKS);
                waitDriving(ctx, server, joined, 40);

                checkOnlyNearKnown(ctx, near.getUUID(), far.getUUID());
                showConsoleSay(ctx, server, connection);
                showAdminView(ctx, server, connection, joined, far.getUUID());
            } finally {
                server.runOnServer(s -> {
                    ServerPlayer self = connection.getServerPlayer();
                    AdminView.set(self, false);
                    // Only undo the op this test gave
                    if (!wasOp) {
                        TestPlayers.deop(self);
                    }
                    joined.forEach(TestPlayers::leave);
                    Settings.get().hide.distantPlayers = distantWas;
                    Settings.get().hide.tabList = tabListWas;
                });
            }
        });
    }

    private static void checkOnlyNearKnown(ClientGameTestContext ctx, UUID near, UUID far) {
        // Tab-complete and the social interactions screen read this same list, so a missing entry is a name never leaked
        Set<UUID> known = knownIds(ctx);
        if (!known.contains(near)) {
            throw new AssertionError("the client has no entry for the near player");
        }
        if (known.contains(far)) {
            throw new AssertionError("the client has an entry for the far player");
        }
        if (!ctx.computeOnClient(mc -> mc.getConnection().getListedOnlinePlayers().isEmpty())) {
            throw new AssertionError("the client lists players in the tab list");
        }
    }

    private static void showConsoleSay(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection) {
        server.runCommand("say hello from console");
        connection.waitForClientboundPackets();
        screenshot(ctx, "03-chat-say");
    }

    private static void showAdminView(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, List<ServerPlayer> joined, UUID far) {
        // Admin view only counts for ops
        server.runOnServer(s -> TestPlayers.op(connection.getServerPlayer()));
        if (!server.computeOnServer(s -> AdminView.toggle(connection.getServerPlayer()))) {
            throw new AssertionError("admin view was already on, so the toggle turned it off");
        }
        waitDriving(ctx, server, joined, 20);

        if (!knownIds(ctx).contains(far)) {
            throw new AssertionError("admin view did not give the client the far player's entry");
        }
        boolean farListed = ctx.computeOnClient(mc -> mc.getConnection().getListedOnlinePlayers().stream()
                .anyMatch(info -> info.getProfile().id().equals(far)));
        if (!farListed) {
            throw new AssertionError("admin view did not list the far player in the tab list");
        }

        ctx.getInput().holdKey(options -> options.keyPlayerList);
        screenshot(ctx, "03-adminview-tablist");
        ctx.getInput().releaseKey(options -> options.keyPlayerList);

        server.runOnServer(s -> AdminView.toggle(connection.getServerPlayer()));
    }

    private static ServerPlayer join(TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                                     List<ServerPlayer> joined, String name, int blocksEast) {
        ServerPlayer player = server.computeOnServer(s -> TestPlayers.join(s, name, GameType.SURVIVAL,
                connection.getServerPlayer().blockPosition().east(blocksEast)));
        joined.add(player);
        return player;
    }

    // Test players have no client, so each tick the server gets what a client would have sent it
    private static void waitDriving(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                    List<ServerPlayer> players, int ticks) {
        for (int i = 0; i < ticks; i++) {
            server.runOnServer(s -> players.forEach(TestPlayers::driveTracking));
            ctx.waitTick();
        }
    }

    // A copy, since the client changes its map on its own thread
    private static Set<UUID> knownIds(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> Set.copyOf(mc.getConnection().getOnlinePlayerIds()));
    }

    private static void screenshot(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
        // No counter prefix, so the CI artifact keeps the plain file names
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }
}
