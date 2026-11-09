package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
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
