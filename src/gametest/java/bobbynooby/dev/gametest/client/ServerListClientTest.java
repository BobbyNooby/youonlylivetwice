package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

// The multiplayer list's hover over a server's player count, which a player sees before they ever join
public class ServerListClientTest implements FabricClientGameTest {
    private static final String SERVER_NAME = "YOLT test server";
    // The list's own layout, as ServerSelectionList.OnlineServerEntry draws it: the count sits left of the ping bars
    private static final int PING_BARS_WIDTH = 10;
    private static final int SPACING = 5;
    private static final int COUNT_HEIGHT = 8;

    // Where the count is drawn, in GUI pixels. The hover answers the mouse anywhere in it, edges included.
    private record Box(int left, int top, int right, int bottom) {
        boolean contains(int x, int y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private record Point(double x, double y) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            List<String> lines = server.computeOnServer(s -> List.copyOf(Settings.get().serverList.hoverLines));
            if (lines.isEmpty()) {
                throw new AssertionError("no hover lines are set, so the hover would show vanilla's");
            }
            String address = "localhost:" + server.computeOnServer(MinecraftServer::getPort);
            // The multiplayer screen reads its list from the file each time it opens
            ctx.runOnClient(mc -> {
                ServerList servers = new ServerList(mc);
                servers.load();
                servers.add(new ServerData(SERVER_NAME, address, ServerData.Type.OTHER), false);
                servers.save();
            });
            try {
                ctx.runOnClient(mc -> mc.gui.setScreen(new JoinMultiplayerScreen(mc.gui.screen())));
                ctx.waitForScreen(JoinMultiplayerScreen.class);
                // The list pings a server the first time it draws it
                ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> hoverLines(entry(mc, address)) != null,
                        mc -> "the ping never finished: the server is " + entry(mc, address).getServerData().state());
                hoverOverCount(ctx, address);
                checkHover(ctx, address, lines);
                ClientTests.screenshot(ctx, "07-server-list-hover");
            } finally {
                ClientTests.runAll(List.of(
                        // With no world open, no screen means the title screen, where every client test has to end
                        () -> ctx.setScreen(() -> null),
                        () -> ctx.runOnClient(mc -> removeFromList(mc, address))));
            }
        }
    }

    private static void hoverOverCount(ClientGameTestContext ctx, String address) {
        Point middle = ctx.computeOnClient(mc -> {
            Box count = countBox(mc, entry(mc, address));
            return new Point((count.left() + count.right()) / 2 + 0.5, (count.top() + count.bottom()) / 2 + 0.5);
        });
        ClientTests.moveCursorToGui(ctx, middle.x(), middle.y());
    }

    private static void checkHover(ClientGameTestContext ctx, String address, List<String> lines) {
        ctx.runOnClient(mc -> {
            ServerSelectionList.OnlineServerEntry entry = entry(mc, address);
            ServerData data = entry.getServerData();
            if (data.state() != ServerData.State.SUCCESSFUL) {
                throw new AssertionError("the ping ended " + data.state() + ", not successfully");
            }
            // The names in the ping's sample, as the client read them
            List<String> sample = data.playerList.stream().map(Component::getString).toList();
            if (!sample.equals(lines)) {
                throw new AssertionError("the client read the sample " + sample + ", not the hover lines " + lines);
            }
            List<Component> hover = hoverLines(entry);
            List<String> shown = hover == null ? List.of() : hover.stream().map(Component::getString).toList();
            if (!shown.equals(lines)) {
                throw new AssertionError("the hover shows " + shown + ", not the hover lines " + lines);
            }
            // Where the list will next draw the mouse, as the screen reads it
            int x = (int) mc.mouseHandler.getScaledXPos(mc.getWindow());
            int y = (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
            Box count = countBox(mc, entry);
            if (!count.contains(x, y)) {
                throw new AssertionError("the cursor is at " + x + ", " + y + ", not on the player count at " + count);
            }
        });
    }

    private static Box countBox(Minecraft mc, ServerSelectionList.OnlineServerEntry entry) {
        int width = mc.font.width(entry.getServerData().status);
        int right = entry.getContentRight() - PING_BARS_WIDTH - SPACING - SPACING;
        return new Box(right - width, entry.getContentY(), right, entry.getContentY() + COUNT_HEIGHT);
    }

    private static ServerSelectionList.OnlineServerEntry entry(Minecraft mc, String address) {
        if (!(mc.gui.screen() instanceof JoinMultiplayerScreen screen)) {
            throw new AssertionError("the multiplayer screen is not open");
        }
        ServerSelectionList list = screen.children().stream()
                .filter(ServerSelectionList.class::isInstance)
                .map(ServerSelectionList.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the multiplayer screen has no server list"));
        return list.children().stream()
                .filter(ServerSelectionList.OnlineServerEntry.class::isInstance)
                .map(ServerSelectionList.OnlineServerEntry.class::cast)
                .filter(entry -> entry.getServerData().ip.equals(address))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the server list has no entry for " + address));
    }

    // The entry's own copy of the sample's names, which it only takes once the ping's answer is in, and which the hover
    // draws. It is private, so it is read by name.
    @SuppressWarnings("unchecked")
    private static @Nullable List<Component> hoverLines(ServerSelectionList.OnlineServerEntry entry) {
        try {
            Field field = ServerSelectionList.OnlineServerEntry.class.getDeclaredField("onlinePlayersTooltip");
            field.setAccessible(true);
            return (List<Component>) field.get(entry);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read the server list entry's hover", e);
        }
    }

    private static void removeFromList(Minecraft mc, String address) {
        ServerList servers = new ServerList(mc);
        servers.load();
        for (int i = 0; i < servers.size(); i++) {
            ServerData data = servers.get(i);
            if (data.name.equals(SERVER_NAME) && data.ip.equals(address)) {
                servers.remove(data);
                servers.save();
                return;
            }
        }
    }
}
