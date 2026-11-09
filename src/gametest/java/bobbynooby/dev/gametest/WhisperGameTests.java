package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.Whispers;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

// /w runs through the real dispatcher as a non-op test player, so the restriction mixin handles it.
// Switches are restored in a finally block in the same tick.
public class WhisperGameTests {
    @GameTest
    public void treeHasOnlyHelpWhenWhispersOff(GameTestHelper helper) {
        helper.assertValueEqual(childNames(Whispers.nonOpTree(false)), List.of("help"), "non-op commands");

        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = false;
            TestPlayers.Recording sender = joinRecording(helper, joined, "woff", BlockPos.ZERO);
            sender.messages.clear();

            run(sender, "w Nobody123 hi");
            helper.assertValueEqual(texts(sender), List.of(), "sender messages with whispers off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void treeHasWhisperWhenOn(GameTestHelper helper) {
        RootCommandNode<CommandSourceStack> root = Whispers.nonOpTree(true);
        helper.assertValueEqual(childNames(root), List.of("help", "w"), "non-op commands");

        CommandNode<CommandSourceStack> target = root.getChild("w").getChild("target");
        helper.assertTrue(target instanceof ArgumentCommandNode<?, ?> arg && arg.getCustomSuggestions() == null,
                "target has a suggestions provider");
        helper.assertTrue(target instanceof ArgumentCommandNode<?, ?> arg && arg.getType() instanceof StringArgumentType word
                && word.getType() == StringArgumentType.StringType.SINGLE_WORD, "target is not a single word");

        CommandNode<CommandSourceStack> message = target.getChild("message");
        helper.assertTrue(message instanceof ArgumentCommandNode<?, ?> arg && arg.getType() instanceof MessageArgument,
                "message is not a message argument");
        helper.assertTrue(message.getCommand() != null, "w <target> <message> is not executable");

        // The client signs what our tree marks as signable, the server checks what vanilla's /w marks
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack();
        String command = "w Somebody hi there";
        List<String> ours = signable(new CommandDispatcher<>(root), command, source);
        List<String> vanilla = signable(helper.getLevel().getServer().getCommands().getDispatcher(), command, source);
        helper.assertValueEqual(ours, List.of("message=hi there"), "signed arguments in our tree");
        helper.assertValueEqual(ours, vanilla, "signed arguments, ours vs vanilla");
        helper.succeed();
    }

    @GameTest
    public void whisperToUnknownNameLooksSent(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wsend", BlockPos.ZERO);
            sender.messages.clear();

            run(sender, "w Nobody123 hi");
            helper.assertValueEqual(texts(sender), List.of("You whisper to Nobody123: hi"), "sender messages");
            Style style = sender.messages.getFirst().getStyle();
            helper.assertTrue(style.isItalic() && TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(style.getColor()),
                    "whisper confirmation is not gray italic");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void whisperOutOfRangeNotDelivered(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        int radiusBefore = config.chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            config.chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wfrom", BlockPos.ZERO);
            TestPlayers.Recording near = joinRecording(helper, joined, "wnear", new BlockPos(10, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "wfar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 100, far.getY(), far.getZ());
            String senderName = sender.nameAndId().name();
            String nearName = near.nameAndId().name();
            String farName = far.nameAndId().name();
            sender.messages.clear();
            near.messages.clear();
            far.messages.clear();

            // The near player shows a whisper does get through, so the far one getting nothing is the range
            run(sender, "w " + nearName + " hi");
            helper.assertValueEqual(texts(near), List.of(senderName + " whispers to you: hi"), "near player messages");

            run(sender, "w " + farName + " hi");
            helper.assertValueEqual(texts(far), List.of(), "far player messages");
            helper.assertValueEqual(texts(sender),
                    List.of("You whisper to " + nearName + ": hi", "You whisper to " + farName + ": hi"), "sender messages");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.chat.radius = radiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    private static List<String> childNames(RootCommandNode<CommandSourceStack> root) {
        List<String> names = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            names.add(child.getName());
        }
        return names;
    }

    private static List<String> signable(CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        List<String> arguments = new ArrayList<>();
        for (SignableCommand.Argument<CommandSourceStack> argument : SignableCommand.of(dispatcher.parse(command, source)).arguments()) {
            arguments.add(argument.name() + "=" + argument.value());
        }
        return arguments;
    }

    private static void run(ServerPlayer player, String command) {
        MinecraftServer server = player.level().getServer();
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static List<String> texts(TestPlayers.Recording player) {
        List<String> texts = new ArrayList<>();
        for (Component message : player.messages) {
            texts.add(message.getString());
        }
        return texts;
    }

    private static TestPlayers.Recording joinRecording(GameTestHelper helper, List<ServerPlayer> joined, String name, BlockPos pos) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, name, GameType.SURVIVAL, pos);
        joined.add(player);
        return player;
    }

    private static void leaveAll(List<ServerPlayer> joined) {
        for (ServerPlayer player : joined) {
            TestPlayers.leave(player);
        }
    }
}
