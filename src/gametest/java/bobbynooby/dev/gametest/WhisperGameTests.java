package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

// /w runs through the real dispatcher as a non-op test player, so both the restriction mixin and our /w command run.
// Switches are restored in a finally block in the same tick, apart from the signed whisper's, which waits in a batch of its own.
public class WhisperGameTests {
    private static final String SIGNED_WHISPER = "you-only-live-twice-gametest:signed_whisper";
    private static final SharedHold SIGNED_HOLD = new SharedHold();

    // The reply a restricted player's /w gets with whispers off is checked in whisperOffReplyIsSameWhenRestricted
    @GameTest
    public void treeHasOnlyHelpWhenWhispersOff(GameTestHelper helper) {
        helper.assertValueEqual(childNames(CommandRestriction.nonOpTree(false, false)), List.of("help"), "non-op commands");
        helper.succeed();
    }

    @GameTest
    public void treeHasWhisperWhenOn(GameTestHelper helper) {
        RootCommandNode<CommandSourceStack> root = CommandRestriction.nonOpTree(true, false);
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

        // The client signs what this tree marks as signable, the server checks what its own dispatcher marks
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack();
        String command = "w Somebody hi there";
        List<String> client = signable(new CommandDispatcher<>(root), command, source);
        List<String> server = signable(helper.getLevel().getServer().getCommands().getDispatcher(), command, source);
        helper.assertValueEqual(client, List.of("message=hi there"), "signed arguments in the non-op tree");
        helper.assertValueEqual(server, client, "signed arguments, server vs non-op tree");
        helper.succeed();
    }

    @GameTest
    public void serverWhisperNodeIsOurs(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandNode<CommandSourceStack> w = dispatcher.getRoot().getChild("w");
        helper.assertTrue(w != null && w.getRedirect() == null, "server /w is missing or still redirects to /msg");
        CommandNode<CommandSourceStack> target = w.getChild("target");
        helper.assertTrue(target instanceof ArgumentCommandNode<?, ?> arg && arg.getType() instanceof StringArgumentType,
                "server /w target is not a string argument");
        CommandNode<CommandSourceStack> message = target.getChild("message");
        helper.assertTrue(message instanceof ArgumentCommandNode<?, ?> arg && arg.getType() instanceof MessageArgument,
                "server /w message is not a message argument");
        helper.assertTrue(dispatcher.getRoot().getChild("msg") != null && dispatcher.getRoot().getChild("tell") != null,
                "vanilla /msg or /tell is gone");

        // Vanilla's /w refused these names after the client had signed them, which broke the sender's chat
        CommandSourceStack source = server.createCommandSourceStack();
        CommandDispatcher<CommandSourceStack> client = new CommandDispatcher<>(CommandRestriction.nonOpTree(true, false));
        for (String command : List.of("w  hi", "w NameLongerThan16Chars hi")) {
            helper.assertValueEqual(signable(client, command, source), List.of("message=hi"), "client signs '" + command + "'");
            helper.assertValueEqual(signable(dispatcher, command, source), List.of("message=hi"), "server expects '" + command + "'");
        }
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

    // A whisper is private, so its text never reaches the server log, as vanilla's /msg doesn't either
    @GameTest
    public void whisperTextStaysOutOfTheLog(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wlogfrom", BlockPos.ZERO);
            TestPlayers.Recording target = joinRecording(helper, joined, "wlogto", new BlockPos(2, 0, 0));
            String targetName = target.nameAndId().name();
            String text = "meet me at the secret base " + CommandGameTests.uniqueName("x");
            target.messages.clear();
            List<String> log = OfflineGameTests.yoltLog(() -> run(sender, "w " + targetName + " " + text));
            helper.assertValueEqual(texts(target), List.of(sender.nameAndId().name() + " whispers to you: " + text),
                    "the whisper did not arrive");
            helper.assertValueEqual(log.stream().filter(line -> line.contains(text)).toList(), List.of(), "log lines with the whisper");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // chat.whisperRadius 10 keeps a whisper from a player 20 blocks off, well inside the chat radius; at 0 the chat
    // radius counts again and it arrives
    @GameTest
    public void whisperRadiusShortensTheRange(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        int radiusBefore = config.chat.radius;
        int whisperRadiusBefore = config.chat.whisperRadius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            config.chat.radius = 48;
            config.chat.whisperRadius = 10;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wshort", BlockPos.ZERO);
            TestPlayers.Recording target = joinRecording(helper, joined, "wshortto", BlockPos.ZERO);
            target.teleportTo(sender.getX() + 20, sender.getY(), sender.getZ());
            String targetName = target.nameAndId().name();
            sender.messages.clear();
            target.messages.clear();

            run(sender, "w " + targetName + " hi");
            helper.assertValueEqual(texts(target), List.of(), "target messages with whisperRadius 10");

            config.chat.whisperRadius = 0;
            run(sender, "w " + targetName + " hi");
            helper.assertValueEqual(texts(target), List.of(sender.nameAndId().name() + " whispers to you: hi"),
                    "target messages with whisperRadius 0");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.chat.radius = radiusBefore;
            config.chat.whisperRadius = whisperRadiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // With restrictCommands off a non-op gets the full tree, so /w itself has to refuse
    @GameTest
    public void whisperRefusedWhenOffWithoutRestriction(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        int radiusBefore = config.chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer moderator = null;
        try {
            config.restrictCommands = false;
            config.chat.allowWhispers = false;
            config.chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wrefuse", BlockPos.ZERO);
            // In range, so only the switch keeps the whisper from arriving
            TestPlayers.Recording target = joinRecording(helper, joined, "wrefuseto", new BlockPos(10, 0, 0));
            sender.messages.clear();
            target.messages.clear();

            run(sender, "w " + target.nameAndId().name() + " hi");
            helper.assertValueEqual(texts(sender), List.of("Whispers are turned off."), "sender messages");
            helper.assertValueEqual(texts(target), List.of(), "target messages");

            // A level 1 op is an op, but not one who may whisper with whispers off
            moderator = sender;
            TestPlayers.op(moderator, LevelBasedPermissionSet.MODERATOR);
            sender.messages.clear();
            run(sender, "w " + target.nameAndId().name() + " hi");
            helper.assertValueEqual(texts(sender), List.of("Whispers are turned off."), "level 1 op's messages");
            helper.assertValueEqual(texts(target), List.of(), "target messages after the level 1 op's whisper");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.chat.radius = radiusBefore;
            if (moderator != null) {
                TestPlayers.deop(moderator);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // A restricted player's tree has no /w with whispers off, so its client sends the command unsigned, as here
    @GameTest
    public void whisperOffReplyIsSameWhenRestricted(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        int radiusBefore = config.chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = false;
            config.chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "woff", BlockPos.ZERO);
            // In range, so only the switch keeps the whisper from arriving
            TestPlayers.Recording target = joinRecording(helper, joined, "woffto", new BlockPos(10, 0, 0));
            sender.messages.clear();
            target.messages.clear();

            sender.connection.handleChatCommand(new ServerboundChatCommandPacket("w " + target.nameAndId().name() + " hi"));
            helper.assertValueEqual(texts(sender), List.of("Whispers are turned off."), "sender messages");
            helper.assertValueEqual(texts(target), List.of(), "target messages");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.chat.radius = radiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // "w  hi" reads as an empty name. The client has signed the message by then, so /w itself has to refuse it.
    @GameTest
    public void whisperToBlankNameIsRefused(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wblank", BlockPos.ZERO);
            TestPlayers.Recording near = joinRecording(helper, joined, "wblanknear", new BlockPos(1, 0, 0));
            sender.messages.clear();
            near.messages.clear();

            run(sender, "w  hi");
            helper.assertValueEqual(texts(sender), List.of("Name a player to whisper to."), "sender messages");
            helper.assertValueEqual(texts(near), List.of(), "near player messages");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Global chat reaches everyone, so a whisper has no range either
    @GameTest
    public void whisperInGlobalModeHasNoRange(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        YoltConfig.ChatMode modeBefore = config.chat.mode;
        int radiusBefore = config.chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            config.chat.mode = YoltConfig.ChatMode.GLOBAL;
            config.chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wglobal", BlockPos.ZERO);
            TestPlayers.Recording far = joinRecording(helper, joined, "wglobalfar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 100, far.getY(), far.getZ());
            far.messages.clear();

            run(sender, "w " + far.nameAndId().name() + " hi");
            helper.assertValueEqual(texts(far), List.of(sender.nameAndId().name() + " whispers to you: hi"), "far player messages");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.chat.mode = modeBefore;
            config.chat.radius = radiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // An online-mode client signs /w's message, and the server matches each signature to an argument of its own reading
    // of the command; for a restricted player that reading uses the non-op tree. A batch of its own: the whisper goes out
    // a tick or so later, through the sender's chat chain, so the switches stay set until then.
    @GameTest(environment = SIGNED_WHISPER, maxTicks = 20)
    public void signedWhisperDeliversWhenRestricted(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        // Copies of this test in a flake hunt all want the same switches until their own whisper, so they share the hold
        SIGNED_HOLD.take(() -> {
            boolean restrict = config.restrictCommands;
            boolean whispers = config.chat.allowWhispers;
            int radius = config.chat.radius;
            return () -> {
                config.restrictCommands = restrict;
                config.chat.allowWhispers = whispers;
                config.chat.radius = radius;
            };
        });
        List<ServerPlayer> joined = new ArrayList<>();
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanUp = () -> {
            if (cleaned.compareAndSet(false, true)) {
                SIGNED_HOLD.release();
                leaveAll(joined);
            }
        };
        boolean waiting = false;
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            config.chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "wsigned", BlockPos.ZERO);
            TestPlayers.Recording target = joinRecording(helper, joined, "wsignedto", new BlockPos(10, 0, 0));
            String senderName = sender.nameAndId().name();
            String targetName = target.nameAndId().name();
            sender.messages.clear();
            target.messages.clear();

            sendSigned(sender, "w " + targetName + " hi");
            helper.runBeforeTestEnd(cleanUp);
            helper.succeedWhen(() -> {
                // A signature the server can't place gets the sender an error here instead
                helper.assertValueEqual(texts(sender), List.of("You whisper to " + targetName + ": hi"), "sender messages");
                helper.assertValueEqual(texts(target), List.of(senderName + " whispers to you: hi"), "target messages");
                cleanUp.run();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A modified client can ask for suggestions on commands its tree doesn't have, like /msg and its online names
    @GameTest
    public void suggestionsComeFromNonOpTree(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        boolean skinChoiceBefore = config.skins.allowPlayerChoice;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer op = null;
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = false;
            // Off, so the tree's only other command is /w
            config.skins.allowPlayerChoice = false;
            ServerPlayer nonOp = joinRecording(helper, joined, "wsuggest", BlockPos.ZERO);
            op = joinRecording(helper, joined, "wsuggestop", new BlockPos(1, 0, 0));
            TestPlayers.op(op);

            helper.assertValueEqual(suggestions(helper, nonOp, "/msg "), List.of(), "a non-op's suggestions for /msg");
            helper.assertValueEqual(suggestions(helper, nonOp, "/"), List.of("help"), "a non-op's commands with whispers off");
            config.chat.allowWhispers = true;
            helper.assertValueEqual(suggestions(helper, nonOp, "/"), List.of("help", "w"), "a non-op's commands with whispers on");
            // Ops keep vanilla's suggestions, which shows /msg would name online players
            helper.assertTrue(suggestions(helper, op, "/msg ").contains(nonOp.getGameProfile().name()),
                    "an op's suggestions for /msg have no online names");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.skins.allowPlayerChoice = skinChoiceBefore;
            if (op != null) {
                TestPlayers.deop(op);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // With skin choice on a restricted non-op's tree has /yolt too, for their own skin. Asking for suggestions shows it,
    // and /yolt skin set offers no names, though another player is online a few blocks away.
    @GameTest
    public void suggestionsWithSkinChoiceOfferNoNames(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        boolean skinChoiceBefore = config.skins.allowPlayerChoice;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = false;
            config.skins.allowPlayerChoice = true;
            ServerPlayer nonOp = joinRecording(helper, joined, "wskin", BlockPos.ZERO);
            ServerPlayer near = joinRecording(helper, joined, "wskinnear", new BlockPos(2, 0, 0));
            String name = near.getGameProfile().name();

            helper.assertValueEqual(suggestions(helper, nonOp, "/"), List.of("help", "yolt"), "a non-op's commands with skin choice on");
            helper.assertValueEqual(suggestions(helper, nonOp, "/yolt skin "), List.of("clear", "set"), "a non-op's /yolt skin");
            helper.assertValueEqual(suggestions(helper, nonOp, "/yolt skin set "), List.of(), "a non-op's suggestions for a skin");
            helper.assertValueEqual(suggestions(helper, nonOp, "/yolt skin set " + name.substring(0, 4)), List.of(),
                    "a non-op's suggestions for the start of a name");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.skins.allowPlayerChoice = skinChoiceBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // With a player online a few blocks away, tab-complete after /w offers no names, for a bare /w or the start of that
    // player's name. The server's own /w, which ops use, offers none either.
    @GameTest
    public void whisperTabCompleteOffersNoNames(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        boolean skinChoiceBefore = config.skins.allowPlayerChoice;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer op = null;
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = true;
            config.skins.allowPlayerChoice = false;
            ServerPlayer nonOp = joinRecording(helper, joined, "wtab", BlockPos.ZERO);
            ServerPlayer near = joinRecording(helper, joined, "wtabnear", new BlockPos(2, 0, 0));
            op = joinRecording(helper, joined, "wtabop", new BlockPos(1, 0, 0));
            TestPlayers.op(op);
            String name = near.getGameProfile().name();

            // So the answers below come from a tree that has /w in it
            helper.assertValueEqual(suggestions(helper, nonOp, "/"), List.of("help", "w"), "a non-op's commands");
            helper.assertValueEqual(suggestions(helper, nonOp, "/w "), List.of(), "a non-op's suggestions for /w");
            helper.assertValueEqual(suggestions(helper, nonOp, "/w " + name.substring(0, 4)), List.of(),
                    "a non-op's suggestions for the start of a name");
            helper.assertValueEqual(suggestions(helper, op, "/w "), List.of(), "an op's suggestions for /w");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.skins.allowPlayerChoice = skinChoiceBefore;
            if (op != null) {
                TestPlayers.deop(op);
            }
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

    // What the server answers a client's suggestion request with. A real connection ticks its own provider.
    static List<String> suggestions(GameTestHelper helper, ServerPlayer player, String command) {
        ServerCommandSuggestionsProvider provider = new ServerCommandSuggestionsProvider(player);
        CompletableFuture<Suggestions> answer = provider.request(command);
        provider.tick();
        helper.assertTrue(answer.isDone(), "no answer for '" + command + "' after a tick");
        List<String> texts = new ArrayList<>();
        for (Suggestion suggestion : answer.join().getList()) {
            texts.add(suggestion.getText());
        }
        return texts;
    }

    private static void run(ServerPlayer player, String command) {
        MinecraftServer server = player.level().getServer();
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    // The packet an online-mode client sends for /w, with the message argument signed. The test server is offline, so
    // any signature bytes do; what the server still checks is that its own reading has an argument by that name.
    private static void sendSigned(ServerPlayer player, String command) {
        ArgumentSignatures signatures = new ArgumentSignatures(List.of(
                new ArgumentSignatures.Entry("message", new MessageSignature(new byte[MessageSignature.BYTES]))));
        LastSeenMessages.Update nothingSeen = new LastSeenMessages.Update(0, new BitSet(), LastSeenMessages.Update.IGNORE_CHECKSUM);
        player.connection.handleSignedChatCommand(new ServerboundChatCommandSignedPacket(command, Instant.now(), 0L, signatures, nothingSeen));
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
