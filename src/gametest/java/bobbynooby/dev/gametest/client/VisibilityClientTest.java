package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

// Checks what a real client ends up holding, rather than the packets the server meant to send
public class VisibilityClientTest implements FabricClientGameTest {
    private static final int NEAR_BLOCKS = 5;
    private static final int FAR_BLOCKS = 1000;
    // How long the far player has to stay unknown once the near one is known: the fixed wait this test used to have
    private static final int FAR_UNKNOWN_TICKS = 40;

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
                waitDriving(ctx, server, joined, mc -> mc.getConnection().getPlayerInfo(near.getUUID()) != null,
                        mc -> "the client never got an entry for the near player");
                // A leak could come a few ticks later, so the far player has to stay unknown for a while
                holdDriving(ctx, server, joined, FAR_UNKNOWN_TICKS, mc -> mc.getConnection().getPlayerInfo(far.getUUID()) == null,
                        mc -> "the client got an entry for the far player");
                // So whatever the server sent about the far player by now has reached the client too
                connection.waitForClientboundPackets();

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
        int mark = ChatStoryClientTest.chatLogEnd(ctx);
        server.runCommand("say hello from console");
        connection.waitForClientboundPackets();
        if (!ChatStoryClientTest.chatLogHas(ctx, mark, "hello from console")) {
            throw new AssertionError("the client's chat has no \"hello from console\"");
        }
        screenshot(ctx, "03-chat-say");
    }

    private static void showAdminView(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, List<ServerPlayer> joined, UUID far) {
        // Admin view only counts for ops
        server.runOnServer(s -> TestPlayers.op(connection.getServerPlayer()));
        if (!server.computeOnServer(s -> AdminView.toggle(connection.getServerPlayer()))) {
            throw new AssertionError("admin view was already on, so the toggle turned it off");
        }
        waitDriving(ctx, server, joined, mc -> mc.getConnection().getPlayerInfo(far) != null && listed(mc, far),
                mc -> mc.getConnection().getPlayerInfo(far) == null ? "admin view did not give the client the far player's entry"
                        : "admin view did not list the far player in the tab list");

        ctx.getInput().holdKey(options -> options.keyPlayerList);
        try {
            screenshot(ctx, "03-adminview-tablist");
        } finally {
            ctx.getInput().releaseKey(options -> options.keyPlayerList);
        }

        server.runOnServer(s -> AdminView.toggle(connection.getServerPlayer()));
    }

    private static ServerPlayer join(TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                                     List<ServerPlayer> joined, String name, int blocksEast) {
        ServerPlayer player = server.computeOnServer(s -> TestPlayers.join(s, name, GameType.SURVIVAL,
                connection.getServerPlayer().blockPosition().east(blocksEast)));
        joined.add(player);
        return player;
    }

    // Test players have no client, so each tick the server gets what a client would have sent it, until the client
    // has what the condition asks. Like ClientTests.waitOnClient, whose condition runs on the client thread, where the
    // server can't be driven; a timeout says what was wrong.
    private static void waitDriving(ClientGameTestContext ctx, TestDedicatedServerContext server, List<ServerPlayer> players,
                                    Predicate<Minecraft> condition, Function<Minecraft, String> failure) {
        for (int tick = 0; tick < ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (ctx.computeOnClient(condition::test)) {
                return;
            }
            server.runOnServer(s -> players.forEach(TestPlayers::driveTracking));
            ctx.waitTick();
        }
        if (!ctx.computeOnClient(condition::test)) {
            throw ClientTests.explained(new AssertionError("timed out after " + ClientGameTestContext.DEFAULT_TIMEOUT
                    + " driven ticks"), () -> ctx.computeOnClient(failure::apply));
        }
    }

    // The same driving, while the condition has to hold on every one of the ticks
    private static void holdDriving(ClientGameTestContext ctx, TestDedicatedServerContext server, List<ServerPlayer> players,
                                    int ticks, Predicate<Minecraft> condition, Function<Minecraft, String> failure) {
        for (int tick = 0; tick <= ticks; tick++) {
            if (!ctx.computeOnClient(condition::test)) {
                throw ClientTests.explained(new AssertionError("held for " + tick + " of " + ticks + " driven ticks"),
                        () -> ctx.computeOnClient(failure::apply));
            }
            if (tick < ticks) {
                server.runOnServer(s -> players.forEach(TestPlayers::driveTracking));
                ctx.waitTick();
            }
        }
    }

    private static boolean listed(Minecraft mc, UUID id) {
        return mc.getConnection().getListedOnlinePlayers().stream().anyMatch(info -> info.getProfile().id().equals(id));
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
