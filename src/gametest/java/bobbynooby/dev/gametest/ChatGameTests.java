package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Other tests share the server, so these check who is in a set, never the whole set.
// Switches are restored in a finally block in the same tick.
public class ChatGameTests {
    @GameTest
    public void proximityRecipientsUseLivePositions(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            ServerPlayer a = join(helper, joined, "chata", BlockPos.ZERO);
            ServerPlayer b = join(helper, joined, "chatb", new BlockPos(10, 0, 0));
            ServerPlayer c = join(helper, joined, "chatc", new BlockPos(5, 0, 0));
            helper.assertTrue(ChatDelivery.chatRecipients(a).contains(c), "c not heard before moving away");

            c.teleportTo(c.getX() + 200, c.getY(), c.getZ());
            Set<ServerPlayer> heard = ChatDelivery.chatRecipients(a);
            helper.assertTrue(heard.contains(a), "sender not in recipients");
            helper.assertTrue(heard.contains(b), "player 10 blocks away not in recipients");
            helper.assertTrue(!heard.contains(c), "player 200 blocks away still in recipients");
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void globalModeIsVanilla(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.GLOBAL;
            ServerPlayer a = join(helper, joined, "globala", BlockPos.ZERO);
            ServerPlayer b = join(helper, joined, "globalb", new BlockPos(10, 0, 0));
            ServerPlayer c = join(helper, joined, "globalc", BlockPos.ZERO);
            c.teleportTo(c.getX() + 200, c.getY(), c.getZ());

            Set<ServerPlayer> heard = ChatDelivery.chatRecipients(a);
            helper.assertTrue(heard.contains(a), "sender not in recipients");
            helper.assertTrue(heard.contains(b), "near player not in recipients");
            helper.assertTrue(heard.contains(c), "far player not in recipients in global mode");
        } finally {
            chat.mode = modeBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void hiddenGameMessageGoesToAdminsOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean hiddenBefore = hide.gameMessages;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            admin = join(helper, joined, "msgadmin", BlockPos.ZERO);
            ServerPlayer other = join(helper, joined, "msgother", new BlockPos(1, 0, 0));
            TestPlayers.op(admin);
            AdminView.set(admin, true);

            hide.gameMessages = true;
            Set<ServerPlayer> hidden = ChatDelivery.gameMessageRecipients(server);
            helper.assertTrue(hidden.contains(admin), "admin view player misses hidden game messages");
            helper.assertTrue(!hidden.contains(other), "normal player gets hidden game messages");

            hide.gameMessages = false;
            Set<ServerPlayer> shown = ChatDelivery.gameMessageRecipients(server);
            helper.assertTrue(shown.contains(admin), "admin misses game messages with hiding off");
            helper.assertTrue(shown.contains(other), "normal player misses game messages with hiding off");
        } finally {
            hide.gameMessages = hiddenBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void commandMessagesReachEveryone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            ServerPlayer a = join(helper, joined, "cmda", BlockPos.ZERO);
            ServerPlayer b = join(helper, joined, "cmdb", new BlockPos(10, 0, 0));
            ServerPlayer c = join(helper, joined, "cmdc", BlockPos.ZERO);
            c.teleportTo(c.getX() + 200, c.getY(), c.getZ());

            Set<ServerPlayer> heard = ChatDelivery.commandRecipients(server);
            helper.assertTrue(heard.contains(a), "a misses command messages");
            helper.assertTrue(heard.contains(b), "b misses command messages");
            helper.assertTrue(heard.contains(c), "far player misses command messages");
        } finally {
            leaveAll(joined);
        }
        helper.succeed();
    }

    // With restrictCommands off anyone may /me, and only a gamemaster's is an announcement
    @GameTest
    public void nonOpEmoteIsChat(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.HideSection hide = Settings.get().hide;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "emotefrom", BlockPos.ZERO);
            TestPlayers.Recording near = joinRecording(helper, joined, "emotenear", new BlockPos(10, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "emotefar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());

            for (boolean distant : List.of(true, false)) {
                hide.distantPlayers = distant;
                String text = "waves " + distant;
                broadcast(helper, PlayerChatMessage.unsigned(sender.getUUID(), text), sender.createCommandSourceStack(), ChatType.EMOTE_COMMAND);
                String mode = "hide.distantPlayers " + distant + ": ";
                helper.assertTrue(heard(near, text), mode + "a near player missed a non-op's /me");
                helper.assertFalse(heard(far, text), mode + "a far player heard a non-op's /me");
            }
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void announcementsReachEveryone(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer op = null;
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            op = joinRecording(helper, joined, "announceop", BlockPos.ZERO);
            ServerPlayer nonOp = joinRecording(helper, joined, "announceas", new BlockPos(1, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "announcefar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());
            TestPlayers.op(op);
            CommandSourceStack opSource = op.createCommandSourceStack();

            broadcast(helper, PlayerChatMessage.unsigned(op.getUUID(), "op waves"), opSource, ChatType.EMOTE_COMMAND);
            helper.assertTrue(heard(far, "op waves"), "a far player missed an op's /me");
            // execute as runs with the op's permissions
            broadcast(helper, PlayerChatMessage.unsigned(nonOp.getUUID(), "waves for the op"), opSource.withEntity(nonOp), ChatType.EMOTE_COMMAND);
            helper.assertTrue(heard(far, "waves for the op"), "a far player missed an op's execute as /me");
            CommandSourceStack console = helper.getLevel().getServer().createCommandSourceStack();
            broadcast(helper, PlayerChatMessage.system("console says"), console, ChatType.SAY_COMMAND);
            helper.assertTrue(heard(far, "console says"), "a far player missed the console's /say");
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            if (op != null) {
                TestPlayers.deop(op);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // How /say and /me hand their message to the player list
    private static void broadcast(GameTestHelper helper, PlayerChatMessage message, CommandSourceStack source, ResourceKey<ChatType> type) {
        helper.getLevel().getServer().getPlayerList().broadcastChatMessage(message, source, ChatType.bind(type, source));
    }

    // Player chat from a sender the client knows, or the same line as text, or a console line with no sender
    private static boolean heard(TestPlayers.Recording player, String text) {
        for (Component message : player.messages) {
            if (message.getContents() instanceof TranslatableContents line) {
                for (Object arg : line.getArgs()) {
                    if (arg instanceof Component part && part.getString().equals(text)) {
                        return true;
                    }
                }
            }
        }
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundPlayerChatPacket signed && signed.body().content().equals(text)
                    || packet instanceof ClientboundDisguisedChatPacket disguised && disguised.message().getString().equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static TestPlayers.Recording joinRecording(GameTestHelper helper, List<ServerPlayer> joined, String name, BlockPos pos) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, name, GameType.SURVIVAL, pos);
        joined.add(player);
        return player;
    }

    private static ServerPlayer join(GameTestHelper helper, List<ServerPlayer> joined, String name, BlockPos pos) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, pos);
        joined.add(player);
        return player;
    }

    private static void leaveAll(List<ServerPlayer> joined) {
        for (ServerPlayer player : joined) {
            TestPlayers.leave(player);
        }
    }
}
