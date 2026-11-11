package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

// The death lockout as a real client meets it: its death kicks it, and its next login is refused, both times with how it
// died and how long it has to wait
public class LockoutClientTest implements FabricClientGameTest {
    private static final String WAIT = "Try again in";
    // As long as Fabric's own connect gives a world to load
    private static final int LOGIN_TIMEOUT = SharedConstants.TICKS_PER_MINUTE;
    // The title vanilla gives a connection lost from inside the world. A refusal during login gets CONNECT_FAILED instead.
    private static final Component LOST = Component.translatable("disconnect.lost");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        UUID self = ctx.computeOnClient(mc -> mc.getGameProfile().id());
        // Its own server, since the client is thrown off it and tries to come back
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            boolean lockoutWas = server.computeOnServer(s -> Settings.get().deathLockout.enabled);
            String address = "localhost:" + server.computeOnServer(MinecraftServer::getPort);
            try {
                server.runOnServer(s -> Settings.get().deathLockout.enabled = true);
                String died = showKicked(ctx, server, self);
                showRefused(ctx, server, self, address, died);
            } finally {
                ClientTests.runAll(List.of(
                        // The switch is static, so later client tests in this JVM would see it
                        () -> server.runOnServer(s -> Settings.get().deathLockout.enabled = lockoutWas),
                        // A login let in and never kicked leaves the client in the world, and the server can't stop under it
                        () -> leaveWorld(ctx),
                        // Every client test has to end on the title screen
                        () -> ctx.setScreen(TitleScreen::new)));
            }
        }
    }

    // Returns the death message the lockout recorded
    private static String showKicked(ClientGameTestContext ctx, TestDedicatedServerContext server, UUID self) {
        // Not closed by a try: the kick ends it, and closing a connection the server has ended throws
        TestDedicatedServerConnection connection = server.connect();
        try {
            connection.waitForChunksRender();
            server.runOnServer(s -> {
                ServerPlayer player = connection.getServerPlayer();
                player.kill(player.level());
                if (!player.isDeadOrDying()) {
                    throw new AssertionError("the client's player did not die");
                }
            });
            // The lockout kicks once the tick of the death is over
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                    mc -> mc.level == null && mc.gui.screen() instanceof DisconnectedScreen,
                    mc -> "the death did not kick the client, which is on " + screenName(mc));
        } finally {
            // Only when the kick never came, so the server never stops under a client still in its world
            if (ctx.computeOnClient(mc -> mc.level != null)) {
                connection.close();
            }
        }
        String died = server.computeOnServer(s -> {
            DeathLog death = DeathLockout.newestDeath(self);
            if (death == null || death.deathMessage() == null) {
                throw new AssertionError("the lockout recorded no death message for the client: " + death);
            }
            return death.deathMessage();
        });
        checkTitle(ctx, LOST);
        checkReason(ctx, died);
        ClientTests.screenshot(ctx, "06-lockout-kicked");
        return died;
    }

    // From the title screen, the way a player would come back, rather than through Fabric's connect, which waits for a world
    private static void showRefused(ClientGameTestContext ctx, TestDedicatedServerContext server, UUID self, String address,
                                    String died) {
        ctx.setScreen(TitleScreen::new);
        ctx.runOnClient(mc -> ConnectScreen.startConnecting(mc.gui.screen(), mc, ServerAddress.parseString(address),
                new ServerData("localhost", address, ServerData.Type.OTHER), false, null));
        ClientTests.waitOnClient(ctx, LOGIN_TIMEOUT, mc -> mc.gui.screen() instanceof DisconnectedScreen,
                mc -> "the login was not refused: the client is on " + screenName(mc) + (mc.level == null ? "" : ", in a world"));
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            throw new AssertionError("the refused client is in a world");
        }
        if (server.computeOnServer(s -> s.getPlayerList().getPlayer(self) != null)) {
            throw new AssertionError("the server let the locked-out client in");
        }
        // Only a disconnect during login has this title, so a join and then a kick with the same reason fails here
        checkTitle(ctx, CommonComponents.CONNECT_FAILED);
        checkReason(ctx, died);
        ClientTests.screenshot(ctx, "06-lockout-refused");
    }

    private static void checkTitle(ClientGameTestContext ctx, Component expected) {
        ctx.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof DisconnectedScreen screen)) {
                throw new AssertionError("the client is on " + screenName(mc) + ", not the disconnect screen");
            }
            if (!screen.getTitle().equals(expected)) {
                throw new AssertionError("the disconnect screen says \"" + screen.getTitle().getString() + "\", not \""
                        + expected.getString() + "\"");
            }
        });
    }

    private static void checkReason(ClientGameTestContext ctx, String died) {
        String reason = ctx.computeOnClient(LockoutClientTest::shownReason);
        if (!reason.contains(died)) {
            throw new AssertionError("the client was told \"" + reason + "\", which leaves out how it died: " + died);
        }
        if (!reason.contains(WAIT)) {
            throw new AssertionError("the client was told \"" + reason + "\", which leaves out how long to wait");
        }
    }

    // The reason as the disconnect screen shows it, under the screen's title
    private static String shownReason(Minecraft mc) {
        if (!(mc.gui.screen() instanceof DisconnectedScreen screen)) {
            throw new AssertionError("the client is on " + screenName(mc) + ", not the disconnect screen");
        }
        return screen.children().stream()
                .filter(MultiLineTextWidget.class::isInstance)
                .map(widget -> ((MultiLineTextWidget) widget).getMessage().getString())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the disconnect screen shows no reason"));
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
        ctx.waitFor(mc -> mc.level == null);
    }

    private static String screenName(Minecraft mc) {
        Screen screen = mc.gui.screen();
        return screen == null ? "no screen" : screen.getClass().getSimpleName();
    }
}
