package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.visibility.AdminView;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatLog;
import net.minecraft.client.multiplayer.chat.LoggedChatMessage;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

// Chat, whispers and the player list as a real client sees them, with one test player near it and one far off.
// There is only one real client, so each point of view is its own screenshot.
public class ChatStoryClientTest implements FabricClientGameTest {
    private static final int NEAR_BLOCKS = 4;
    private static final int FAR_BLOCKS = 200;
    private static final int RADIUS = 48;
    private static final int PREFIX = 3;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            YoltConfig.ChatMode modeWas = server.computeOnServer(s -> Settings.get().chat.mode);
            int radiusWas = server.computeOnServer(s -> Settings.get().chat.radius);
            boolean whispersWas = server.computeOnServer(s -> Settings.get().chat.allowWhispers);
            boolean distantWas = server.computeOnServer(s -> Settings.get().hide.distantPlayers);
            boolean tabListWas = server.computeOnServer(s -> Settings.get().hide.tabList);
            UUID self = server.computeOnServer(s -> connection.getServerPlayer().getUUID());
            boolean wasOp = server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId()));
            // Only touched on the server thread
            List<ServerPlayer> joined = new ArrayList<>();
            try {
                server.runOnServer(s -> {
                    Settings.get().chat.mode = YoltConfig.ChatMode.PROXIMITY;
                    Settings.get().chat.radius = RADIUS;
                    Settings.get().hide.distantPlayers = true;
                    Settings.get().hide.tabList = true;
                });
                TestPlayers.Recording near = join(server, connection, joined, "Near", NEAR_BLOCKS);
                TestPlayers.Recording far = join(server, connection, joined, "Far", FAR_BLOCKS);
                waitDriving(ctx, server, joined, 20);
                String selfName = server.computeOnServer(s -> connection.getServerPlayer().nameAndId().name());

                showNoNametag(ctx, server, connection, near);
                showChat(ctx, server, connection, near, far);
                showWhispers(ctx, server, connection, near, far, selfName);
                showTabList(ctx, near, far);
                showTabComplete(ctx, near, far);
                showAdminView(ctx, server, connection, near, far);
            } finally {
                server.runOnServer(s -> {
                    // The switches first: they are static, so later client tests in this JVM would see them
                    Settings.get().chat.mode = modeWas;
                    Settings.get().chat.radius = radiusWas;
                    Settings.get().chat.allowWhispers = whispersWas;
                    Settings.get().hide.distantPlayers = distantWas;
                    Settings.get().hide.tabList = tabListWas;
                    List<Runnable> steps = new ArrayList<>();
                    ServerPlayer player = s.getPlayerList().getPlayer(self);
                    if (player != null) {
                        steps.add(() -> AdminView.set(player, false));
                        // Only undo the op this test gave
                        if (!wasOp) {
                            steps.add(() -> TestPlayers.deop(player));
                        }
                        // The client's command tree follows the whisper switch
                        steps.add(() -> s.getCommands().sendCommands(player));
                    }
                    for (ServerPlayer testPlayer : joined) {
                        steps.add(() -> ClientTests.leaveWithoutBody(s, testPlayer.getUUID()));
                    }
                    ClientTests.runAll(steps);
                });
            }
        });
    }

    private static void showNoNametag(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, ServerPlayer near) {
        String name = near.getScoreboardName();
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.getEntity(near.getUUID()) instanceof Player,
                mc -> "the client never got a player entity for the near player");
        server.runOnServer(s -> connection.getServerPlayer().lookAt(EntityAnchorArgument.Anchor.EYES, near.getBoundingBox().getCenter()));
        connection.waitForClientboundPackets();
        connection.waitForChunksRender();
        ctx.runOnClient(mc -> {
            PlayerTeam team = mc.level.getScoreboard().getPlayersTeam(name);
            if (team == null) {
                throw new AssertionError("the client has no team for the near player, so nothing hides their nametag");
            }
            if (team.getNameTagVisibility() != Team.Visibility.NEVER) {
                throw new AssertionError("the near player's nametag is " + team.getNameTagVisibility() + " on the client");
            }
        });
        ClientTests.assertInView(ctx, near.getUUID(), "near player");
        // With the HUD on, since hiding it hides every nametag anyway
        ClientTests.screenshot(ctx, "06-no-nametag-near");
    }

    private static void showChat(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                 TestDedicatedServerConnection connection, ServerPlayer near, ServerPlayer far) {
        String nearLine = "hello from Near";
        chat(server, near, nearLine);
        connection.waitForClientboundPackets();
        if (!chatLogHas(ctx, nearLine)) {
            throw new AssertionError("the client missed chat from a player " + NEAR_BLOCKS + " blocks away");
        }
        ClientTests.screenshot(ctx, "06-chat-in-range");

        String farLine = "hello from Far";
        chat(server, far, farLine);
        // Anything sent before this reply has arrived by now
        connection.waitForClientboundPackets();
        if (chatLogHas(ctx, farLine)) {
            throw new AssertionError("the client got chat from a player " + FAR_BLOCKS + " blocks away");
        }
        // The open chat shows all of it: the near line, and nothing from far under it
        screenshotWithChatOpen(ctx, "06-chat-out-of-range");
    }

    private static void showWhispers(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                     TestDedicatedServerConnection connection, TestPlayers.Recording near,
                                     TestPlayers.Recording far, String selfName) {
        server.runOnServer(s -> {
            Settings.get().chat.allowWhispers = true;
            // Like a config reload, which sends the client its new command tree with /w in it
            s.getCommands().sendCommands(connection.getServerPlayer());
        });
        connection.waitForClientboundPackets();

        String nearWhisper = near.getScoreboardName() + " whispers to you: psst from Near";
        run(server, near, "w " + selfName + " psst from Near");
        connection.waitForClientboundPackets();
        if (!chatLogHas(ctx, nearWhisper)) {
            throw new AssertionError("the client missed a whisper from a player " + NEAR_BLOCKS + " blocks away");
        }
        screenshotWithChatOpen(ctx, "06-whisper-in-range");

        run(server, far, "w " + selfName + " psst from Far");
        connection.waitForClientboundPackets();
        if (chatLogHas(ctx, "psst from Far")) {
            throw new AssertionError("the client got a whisper from a player " + FAR_BLOCKS + " blocks away");
        }

        String farName = far.getScoreboardName();
        server.runOnServer(s -> far.messages.clear());
        sendFromChat(ctx, connection, "/w " + farName + " can you hear me");
        if (!chatLogHas(ctx, "You whisper to " + farName + ": can you hear me")) {
            throw new AssertionError("the client got no reply to its own whisper");
        }
        List<String> farGot = server.computeOnServer(s -> far.messages.stream().map(Component::getString).toList());
        if (farGot.stream().anyMatch(message -> message.contains("can you hear me"))) {
            throw new AssertionError("the far player got the client's whisper: " + farGot);
        }
        screenshotWithChatOpen(ctx, "06-whisper-out-of-range");
    }

    private static void showTabList(ClientGameTestContext ctx, ServerPlayer near, ServerPlayer far) {
        ctx.runOnClient(mc -> {
            ClientPacketListener players = mc.getConnection();
            if (!players.getListedOnlinePlayers().isEmpty()) {
                throw new AssertionError("the client lists players in the tab list");
            }
            // Tab-complete offers the names of the entries the client has
            if (players.getPlayerInfo(near.getUUID()) == null) {
                throw new AssertionError("the client has no entry for the near player");
            }
            if (players.getPlayerInfo(far.getUUID()) != null) {
                throw new AssertionError("the client has an entry for the far player");
            }
        });
        ctx.getInput().holdKey(options -> options.keyPlayerList);
        try {
            ClientTests.screenshot(ctx, "06-tab-near-players");
        } finally {
            ctx.getInput().releaseKey(options -> options.keyPlayerList);
        }
    }

    private static void showTabComplete(ClientGameTestContext ctx, ServerPlayer near, ServerPlayer far) {
        String nearName = near.getScoreboardName();
        String farName = far.getScoreboardName();
        ctx.runOnClient(mc -> {
            Collection<String> names = mc.getConnection().getSuggestionsProvider().getCustomTabSuggestions();
            if (!names.contains(nearName)) {
                throw new AssertionError("the client's tab-complete names " + names + " leave out the near player");
            }
            if (names.contains(farName)) {
                throw new AssertionError("the client's tab-complete names " + names + " include the far player");
            }
        });

        String nearTyped = nearName.substring(0, PREFIX);
        String completed = completeInChat(ctx, nearTyped);
        if (!completed.strip().equals(nearName)) {
            throw new AssertionError("tab-complete made \"" + nearTyped + "\" into \"" + completed + "\", not " + nearName);
        }
        ClientTests.screenshot(ctx, "06-tab-complete-near");
        closeChat(ctx);

        String farTyped = farName.substring(0, PREFIX);
        String left = completeInChat(ctx, farTyped);
        if (!left.equals(farTyped)) {
            throw new AssertionError("tab-complete made \"" + farTyped + "\" into \"" + left + "\"");
        }
        ClientTests.screenshot(ctx, "06-tab-complete-far");
        closeChat(ctx);
    }

    private static void showAdminView(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, ServerPlayer near, ServerPlayer far) {
        // Admin view only counts for ops. Opping sends the client the command tree with /yolt in it.
        server.runOnServer(s -> TestPlayers.op(connection.getServerPlayer()));
        connection.waitForClientboundPackets();
        sendFromChat(ctx, connection, "/yolt adminview");
        if (!server.computeOnServer(s -> AdminView.isOn(connection.getServerPlayer()))) {
            throw new AssertionError("/yolt adminview from the client did not turn admin view on");
        }
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.getConnection().getPlayerInfo(far.getUUID()) != null,
                mc -> "admin view never gave the client the far player's entry");

        // The same path the far chat missed the client by before, so that miss was the range
        String farLine = "hello again from Far";
        chat(server, far, farLine);
        connection.waitForClientboundPackets();
        if (!chatLogHas(ctx, farLine)) {
            throw new AssertionError("an admin client missed chat from a player " + FAR_BLOCKS + " blocks away");
        }

        ctx.getInput().holdKey(options -> options.keyPlayerList);
        try {
            ctx.runOnClient(mc -> {
                if (!listed(mc, near.getUUID()) || !listed(mc, far.getUUID())) {
                    throw new AssertionError("admin view does not list both players in the client's tab list");
                }
            });
            ClientTests.screenshot(ctx, "06-adminview-pov");
        } finally {
            ctx.getInput().releaseKey(options -> options.keyPlayerList);
        }
    }

    // In front of the client and facing it
    private static TestPlayers.Recording join(TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                                              List<ServerPlayer> joined, String name, int blocks) {
        return server.computeOnServer(s -> {
            ServerPlayer self = connection.getServerPlayer();
            Direction facing = self.getDirection();
            TestPlayers.Recording player = TestPlayers.joinRecording(s, name, GameType.SURVIVAL,
                    self.blockPosition().relative(facing, blocks));
            joined.add(player);
            float yaw = facing.getOpposite().toYRot();
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setYBodyRot(yaw);
            return player;
        });
    }

    // Test players have no client, so each tick the server gets what a client would have sent it
    private static void waitDriving(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                    List<ServerPlayer> players, int ticks) {
        for (int i = 0; i < ticks; i++) {
            server.runOnServer(s -> players.forEach(TestPlayers::driveTracking));
            ctx.waitTick();
        }
    }

    // Through the player list's chat broadcast, where a player's chat goes once the server has checked it
    private static void chat(TestDedicatedServerContext server, ServerPlayer sender, String text) {
        server.runOnServer(s -> s.getPlayerList().broadcastChatMessage(PlayerChatMessage.unsigned(sender.getUUID(), text),
                sender, ChatType.bind(ChatType.CHAT, sender)));
    }

    private static void run(TestDedicatedServerContext server, ServerPlayer player, String command) {
        server.runOnServer(s -> s.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command));
    }

    // Everything the client showed in its chat, player chat and server lines alike. VisibilityClientTest reads it too.
    static boolean chatLogHas(ClientGameTestContext ctx, String text) {
        return ctx.computeOnClient(mc -> chatLogHas(mc, text));
    }

    // The same on the client thread, for a wait's condition
    static boolean chatLogHas(Minecraft mc, String text) {
        ChatLog log = mc.getReportingContext().chatLog();
        for (int id = log.start(); id <= log.end(); id++) {
            if (log.lookup(id) instanceof LoggedChatMessage message && message.toContentComponent().getString().contains(text)) {
                return true;
            }
        }
        return false;
    }

    // Typed into the chat box and sent with Enter, like a player would
    private static void sendFromChat(ClientGameTestContext ctx, TestDedicatedServerConnection connection, String line) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        ctx.getInput().typeChars(line);
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitForScreen(null);
        // The command reaches the server, then its reply comes back
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    private static String completeInChat(ClientGameTestContext ctx, String typed) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        ctx.getInput().typeChars(typed);
        // The first Tab shows the names that fit, the second puts the first of them in
        ctx.getInput().pressKey(InputConstants.KEY_TAB);
        ctx.getInput().pressKey(InputConstants.KEY_TAB);
        return ctx.computeOnClient(mc -> chatBox(mc).getValue());
    }

    private static void closeChat(ClientGameTestContext ctx) {
        // Emptied first, so the chat doesn't keep it as a draft for next time
        ctx.runOnClient(mc -> chatBox(mc).setValue(""));
        ctx.setScreen(() -> null);
    }

    private static void screenshotWithChatOpen(ClientGameTestContext ctx, String name) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        ClientTests.screenshot(ctx, name);
        ctx.setScreen(() -> null);
    }

    private static EditBox chatBox(Minecraft mc) {
        if (mc.gui.screen() instanceof ChatScreen screen) {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof EditBox box) {
                    return box;
                }
            }
        }
        throw new AssertionError("no chat box is open");
    }

    private static boolean listed(Minecraft mc, UUID id) {
        return mc.getConnection().getListedOnlinePlayers().stream().anyMatch(info -> info.getProfile().id().equals(id));
    }
}
