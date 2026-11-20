package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.mixin.ServerGamePacketListenerInvoker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Each test flips the switch and restores it inside one tick, so no other test sees it
public class CommandRestrictionGameTests {
    private static final String LEVEL_ONE_OP = "you-only-live-twice-gametest:level_one_op";

    @GameTest
    public void restrictedHelpGetsReply(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Help", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        try {
            Settings.get().restrictCommands = true;
            run(player, "help");

            boolean answered = player.messages.stream().map(Component::getString).anyMatch("But nobody answered...."::equals);
            helper.assertTrue(answered, "restricted player got no reply to help");
        } finally {
            Settings.get().restrictCommands = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // messages.help in place of the spooky line: red, as it ships, for plain text, and as typed for text with colours of
    // its own
    @GameTest
    public void restrictedHelpTakesTheConfigsWords(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "HelpWords", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        String helpBefore = config.messages.help;
        try {
            config.restrictCommands = true;
            run(player, "help");
            assertHelpReply(helper, player, "But nobody answered....", true);
            config.messages.help = "Only the wind.";
            run(player, "help");
            assertHelpReply(helper, player, "Only the wind.", true);
            config.messages.help = "\u00a7aEcho";
            run(player, "help");
            assertHelpReply(helper, player, "\u00a7aEcho", false);
        } finally {
            config.restrictCommands = restrictBefore;
            config.messages.help = helpBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static void assertHelpReply(GameTestHelper helper, TestPlayers.Recording player, String text, boolean red) {
        helper.assertFalse(player.messages.isEmpty(), "no reply to /help");
        Component reply = player.messages.getLast();
        helper.assertValueEqual(reply.getString(), text, "reply to /help");
        // -1 for none
        TextColor colour = reply.getStyle().getColor();
        int expected = red ? TextColor.fromLegacyFormat(ChatFormatting.RED).getValue() : -1;
        helper.assertValueEqual(colour == null ? -1 : colour.getValue(), expected, "colour of the reply " + text);
    }

    // trigger is a level 0 command, so only the restriction can keep it from running
    @GameTest
    public void restrictedCommandDoesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Scoreboard scoreboard = server.getScoreboard();
        ServerPlayer player = TestPlayers.join(helper, "Trigger", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        // Made after the join, so a join that fails leaves no objective behind
        Objective objective = null;
        try {
            objective = scoreboard.addObjective("yolt_t", ObjectiveCriteria.TRIGGER, Component.literal("yolt_t"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(player, objective);
            score.unlock();

            Settings.get().restrictCommands = true;
            run(player, "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 0, "score after a restricted trigger");

            Settings.get().restrictCommands = false;
            run(player, "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 1, "score after an unrestricted trigger");
        } finally {
            Settings.get().restrictCommands = before;
            if (objective != null) {
                scoreboard.removeObjective(objective);
            }
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The commands that would tell a non-op who is online, or reach someone, sent the way a client sends a command its
    // tree doesn't have. Restricted with whispers off, none of them reaches anyone, and /msg and /tell only get the
    // answer /w gets. The same packets unrestricted with whispers on, in the same tick, show what each would have done.
    @GameTest
    public void nonOpGetsNoListMsgTellMeOrTrigger(GameTestHelper helper) {
        Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        TestPlayers.Recording sender = TestPlayers.joinRecording(helper, "Leaky", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        TestPlayers.Recording near = TestPlayers.joinRecording(helper, "Listener", GameType.SURVIVAL, new BlockPos(2, 1, 1));
        String target = near.nameAndId().name();
        List<String> commands = List.of("list", "msg " + target + " leak by msg", "tell " + target + " leak by tell",
                "me leak by me", "trigger yolt_leak set 1");
        boolean before = Settings.get().restrictCommands;
        boolean whispersBefore = Settings.get().chat.allowWhispers;
        Objective objective = null;
        try {
            objective = scoreboard.addObjective("yolt_leak", ObjectiveCriteria.TRIGGER, Component.literal("yolt_leak"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(sender, objective);
            score.unlock();

            Settings.get().restrictCommands = true;
            Settings.get().chat.allowWhispers = false;
            int senderFrom = TestPlayers.sentPackets(sender).size();
            int nearFrom = TestPlayers.sentPackets(near).size();
            commands.forEach(command -> sendCommand(sender, command));
            helper.assertValueEqual(chatLines(sender, senderFrom), List.of("Whispers are turned off.", "Whispers are turned off."),
                    "what the restricted sender was told");
            helper.assertValueEqual(chatLines(near, nearFrom), List.of(), "what the near player got from the restricted sender");
            helper.assertValueEqual(score.get(), 0, "score after the restricted trigger");

            Settings.get().restrictCommands = false;
            // On, so /msg and /tell, now whispers, reach the near player as they did before
            Settings.get().chat.allowWhispers = true;
            senderFrom = TestPlayers.sentPackets(sender).size();
            nearFrom = TestPlayers.sentPackets(near).size();
            commands.forEach(command -> sendCommand(sender, command));
            List<String> told = chatLines(sender, senderFrom);
            helper.assertTrue(told.stream().anyMatch(line -> line.startsWith("There are ") && line.contains(target)),
                    "the unrestricted /list named no one: " + told);
            List<String> heard = chatLines(near, nearFrom);
            for (String text : List.of("leak by msg", "leak by tell", "leak by me")) {
                helper.assertTrue(heard.stream().anyMatch(line -> line.contains(text)), "the near player missed '" + text + "': " + heard);
            }
            helper.assertValueEqual(score.get(), 1, "score after the unrestricted trigger");
        } finally {
            Settings.get().restrictCommands = before;
            Settings.get().chat.allowWhispers = whispersBefore;
            if (objective != null) {
                scoreboard.removeObjective(objective);
            }
            TestPlayers.leave(sender);
            TestPlayers.leave(near);
        }
        helper.succeed();
    }

    // A sign an op wrote runs its click command as the clicking player, but with the sign's own permissions
    @GameTest
    public void opSignCommandRunsForNonOp(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Sign", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        try {
            Settings.get().restrictCommands = true;
            run(signSource(player), "tag @s add yolt_sign");
            helper.assertTrue(player.entityTags().contains("yolt_sign"), "the sign's command did not run");
        } finally {
            Settings.get().restrictCommands = before;
            player.removeTag("yolt_sign");
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The same level 0 trigger: typed by the player it stays blocked, clicked on an op's sign it runs
    @GameTest
    public void nonOpTypedCommandStillBlocked(GameTestHelper helper) {
        Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        ServerPlayer player = TestPlayers.join(helper, "Typed", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        Objective objective = null;
        try {
            objective = scoreboard.addObjective("yolt_t", ObjectiveCriteria.TRIGGER, Component.literal("yolt_t"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(player, objective);
            score.unlock();
            Settings.get().restrictCommands = true;

            run(player, "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 0, "score after a typed trigger");

            run(signSource(player), "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 1, "score after the sign's trigger");
        } finally {
            Settings.get().restrictCommands = before;
            if (objective != null) {
                scoreboard.removeObjective(objective);
            }
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Read against the full tree, /msg has a message to sign, and an online-mode server answers the client's unsigned
    // /msg with a signature error. Read against the restricted player's own tree it fails at once, with nothing to sign.
    // The test server is offline, so the signature check itself can't run here.
    @GameTest
    public void restrictedParseFailsAtFirstWord(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Parse", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().restrictCommands;
        try {
            Settings.get().restrictCommands = true;
            ParseResults<CommandSourceStack> restricted = parseCommand(player, "msg x hi");
            CommandSyntaxException error = Commands.getParseException(restricted);
            helper.assertTrue(error != null && error.getCursor() == 0
                            && error.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(),
                    "a restricted player's /msg did not fail at the first word: " + error);
            helper.assertFalse(SignableCommand.hasSignableArguments(restricted), "a restricted player's /msg has a message to sign");

            Settings.get().restrictCommands = false;
            helper.assertTrue(SignableCommand.hasSignableArguments(parseCommand(player, "msg x hi")),
                    "an unrestricted /msg has no message to sign");
        } finally {
            Settings.get().restrictCommands = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Whether /w is in a restricted player's tree depends on the whisper switch, so a reload sends every player its
    // tree again. It writes the config file and swaps the whole config object, so it runs with the other tests that do.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void reloadResendsCommandTree(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        ServerPlayer player = TestPlayers.join(helper, "Reload", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            // Both ways, so the tree from the join can't pass for a resent one
            for (boolean whispers : new boolean[] {true, false}) {
                Settings.get().restrictCommands = true;
                Settings.get().chat.allowWhispers = whispers;
                ConfigLoader.write(path, Settings.get());
                run(server.createCommandSourceStack(), "yolt reload");
                helper.assertValueEqual(lastTreeHasWhisper(helper, player), whispers,
                        "/w in the tree resent after a reload with whispers " + (whispers ? "on" : "off"));
            }
        } finally {
            // The settings go back before the player leaves, each step on its own, so a failed write still does the rest
            try {
                Files.writeString(path, fileBefore);
            } finally {
                try {
                    CommandGameTests.restoreConfig(server);
                } finally {
                    TestPlayers.leave(player);
                }
            }
        }
        helper.succeed();
    }

    private static boolean lastTreeHasWhisper(GameTestHelper helper, ServerPlayer player) {
        return lastTree(helper, player).getChild("w") != null;
    }

    // The last command tree the server sent this player, read back the way the client reads it
    static RootCommandNode<CommandSourceStack> lastTree(GameTestHelper helper, ServerPlayer player) {
        ClientboundCommandsPacket last = null;
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundCommandsPacket commands) {
                last = commands;
            }
        }
        helper.assertTrue(last != null, "the player was never sent a command tree");
        MinecraftServer server = helper.getLevel().getServer();
        CommandBuildContext context = CommandBuildContext.simple(server.registryAccess(), server.getWorldData().enabledFeatures());
        return last.getRoot(context, TREE_READER);
    }

    private static List<String> childNames(RootCommandNode<CommandSourceStack> root) {
        List<String> names = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            names.add(child.getName());
        }
        return names;
    }

    private static final ClientboundCommandsPacket.NodeBuilder<CommandSourceStack> TREE_READER = new ClientboundCommandsPacket.NodeBuilder<>() {
        @Override
        public ArgumentBuilder<CommandSourceStack, ?> createLiteral(String id) {
            return LiteralArgumentBuilder.literal(id);
        }

        @Override
        public ArgumentBuilder<CommandSourceStack, ?> createArgument(String id, ArgumentType<?> type, Identifier suggestionId) {
            return RequiredArgumentBuilder.argument(id, type);
        }

        @Override
        public ArgumentBuilder<CommandSourceStack, ?> configure(ArgumentBuilder<CommandSourceStack, ?> input, boolean executable,
                                                                 boolean restricted) {
            return input;
        }
    };

    // A level 1 op can't run gamemaster commands, so the restriction holds them like a non-op: what they may run and the
    // tree they are sent. A batch of its own, since it changes the op list and the switch.
    @GameTest(environment = LEVEL_ONE_OP)
    public void levelOneOpIsRestricted(GameTestHelper helper) {
        Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        ServerPlayer player = TestPlayers.join(helper, "Moderator", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        boolean skinChoiceBefore = config.skins.allowPlayerChoice;
        Objective objective = null;
        try {
            config.restrictCommands = true;
            config.chat.allowWhispers = false;
            config.skins.allowPlayerChoice = false;
            // Opping sends the player its command tree again
            TestPlayers.op(player, LevelBasedPermissionSet.MODERATOR);
            helper.assertTrue(CommandRestriction.restricted(player), "a level 1 op is not restricted");
            helper.assertValueEqual(childNames(lastTree(helper, player)), List.of("help"), "commands in the tree sent to a level 1 op");

            objective = scoreboard.addObjective("yolt_t", ObjectiveCriteria.TRIGGER, Component.literal("yolt_t"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(player, objective);
            score.unlock();
            run(player, "trigger yolt_t set 1");
            helper.assertValueEqual(score.get(), 0, "score after a level 1 op's typed trigger");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.skins.allowPlayerChoice = skinChoiceBefore;
            TestPlayers.deop(player);
            if (objective != null) {
                scoreboard.removeObjective(objective);
            }
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // allowedCommands gives a non-op one more root command: it is in the tree they are sent, the server suggests it, and
    // typed as a client sends it, it runs. One not listed stays out of the tree and does nothing, and so does the listed
    // one once the list is empty again. trigger is a level 0 command, so only the restriction can keep it from running.
    @GameTest
    public void allowedCommandIsSentSuggestedAndRuns(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Scoreboard scoreboard = server.getScoreboard();
        ServerPlayer player = TestPlayers.join(helper, "Allowed", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        List<String> allowedBefore = config.allowedCommands;
        Objective objective = null;
        try {
            objective = scoreboard.addObjective("yolt_a", ObjectiveCriteria.TRIGGER, Component.literal("yolt_a"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(player, objective);
            score.unlock();
            config.restrictCommands = true;
            config.allowedCommands = List.of("trigger");

            server.getCommands().sendCommands(player);
            RootCommandNode<CommandSourceStack> tree = lastTree(helper, player);
            helper.assertTrue(tree.getChild("trigger") != null, "the listed /trigger is not in the non-op's tree");
            helper.assertTrue(tree.getChild("list") == null, "the unlisted /list is in the non-op's tree");
            helper.assertTrue(WhisperGameTests.suggestions(helper, player, "/tri").contains("trigger"),
                    "the server doesn't suggest the listed /trigger");
            helper.assertValueEqual(WhisperGameTests.suggestions(helper, player, "/lis"), List.of(),
                    "the server's suggestions for the unlisted /list");
            sendCommand(player, "trigger yolt_a set 1");
            helper.assertValueEqual(score.get(), 1, "score after the listed trigger");
            int from = TestPlayers.sentPackets(player).size();
            sendCommand(player, "list");
            helper.assertFalse(chatLines(player, from).stream().anyMatch(line -> line.startsWith("There are ")),
                    "the unlisted /list answered: " + chatLines(player, from));

            config.allowedCommands = List.of();
            server.getCommands().sendCommands(player);
            helper.assertTrue(lastTree(helper, player).getChild("trigger") == null, "/trigger is still in the tree once unlisted");
            sendCommand(player, "trigger yolt_a set 2");
            helper.assertValueEqual(score.get(), 1, "score after the trigger once unlisted");
        } finally {
            config.restrictCommands = restrictBefore;
            config.allowedCommands = allowedBefore;
            if (objective != null) {
                scoreboard.removeObjective(objective);
            }
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // An op keeps vanilla's tree whatever the list says: commands it doesn't name still reach them
    @GameTest
    public void allowedCommandsLeaveAnOpAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TestPlayers.Recording op = TestPlayers.joinRecording(helper, "AllowedOp", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        List<String> allowedBefore = config.allowedCommands;
        try {
            config.restrictCommands = true;
            config.allowedCommands = List.of("trigger");
            TestPlayers.op(op);
            helper.assertFalse(CommandRestriction.restricted(op), "the op is restricted");
            RootCommandNode<CommandSourceStack> tree = lastTree(helper, op);
            for (String name : List.of("trigger", "list", "gamemode")) {
                helper.assertTrue(tree.getChild(name) != null, "the op's tree has no /" + name);
            }
            int from = TestPlayers.sentPackets(op).size();
            sendCommand(op, "list");
            helper.assertTrue(chatLines(op, from).stream().anyMatch(line -> line.startsWith("There are ")),
                    "the op's /list didn't answer: " + chatLines(op, from));
        } finally {
            config.restrictCommands = restrictBefore;
            config.allowedCommands = allowedBefore;
            TestPlayers.deop(op);
            TestPlayers.leave(op);
        }
        helper.succeed();
    }

    // A listed command is copied for the player: a branch they may not use stays out, a redirect to the root reaches
    // only the non-op tree, as /execute run would, and a name YOLT decides itself or the server lacks adds nothing
    @GameTest
    public void allowedCommandReachesOnlyTheNonOpTree(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Relay", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            CommandDispatcher<CommandSourceStack> full = new CommandDispatcher<>();
            full.register(Commands.literal("secret").executes(c -> 1));
            full.register(Commands.literal("help").then(Commands.literal("everything").executes(c -> 1)));
            full.register(Commands.literal("relay")
                    .then(Commands.literal("run").redirect(full.getRoot()))
                    .then(Commands.literal("opsonly").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> 1)));
            CommandSourceStack source = player.createCommandSourceStack();
            RootCommandNode<CommandSourceStack> tree = CommandRestriction.nonOpTree(false, false, false,
                    List.of("relay", "help", "nosuchcommand"), full, source);
            CommandDispatcher<CommandSourceStack> nonOp = new CommandDispatcher<>(tree);

            helper.assertValueEqual(childNames(tree), List.of("help", "relay"), "the non-op tree's commands");
            helper.assertTrue(tree.getChild("relay").getChild("opsonly") == null, "the gamemaster branch was copied");
            helper.assertTrue(tree.getChild("help").getChild("everything") == null, "the server's /help replaced YOLT's");
            helper.assertTrue(Commands.getParseException(nonOp.parse("relay run help", source)) == null,
                    "relay run help didn't reach the non-op tree's /help");
            helper.assertTrue(Commands.getParseException(nonOp.parse("relay run secret", source)) != null,
                    "relay run secret reached the server's tree");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A name the server has no command for, and one YOLT decides itself, are said at a reload and the rest applies: the
    // tree it sends again has the listed command. It writes the config file, so it runs with the other tests that do.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void reloadAppliesAllowedCommandsAndNamesTheOddOnes(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        ServerPlayer player = TestPlayers.join(helper, "ReloadAllowed", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            // The border stays off, as the test server needs
            Files.writeString(path, "{\"restrictCommands\": true, \"allowedCommands\": [\"trigger\", \"nosuchcommand\", \"msg\"],"
                    + " \"worldBorder\": {\"enabled\": false}}");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt reload"), List.of("Config reloaded.",
                    "allowedCommands: the server has no /nosuchcommand command, so listing it does nothing",
                    "allowedCommands: /msg follows YOLT's own settings, so listing it changes nothing"), "the reload's reply");
            helper.assertValueEqual(Settings.get().allowedCommands, List.of("trigger", "nosuchcommand", "msg"), "the live list");
            helper.assertTrue(lastTree(helper, player).getChild("trigger") != null, "the tree resent after the reload has no /trigger");
            helper.assertTrue(lastTree(helper, player).getChild("msg") == null, "listing /msg put it in the tree with whispers off");
        } finally {
            try {
                Files.writeString(path, fileBefore);
            } finally {
                try {
                    CommandGameTests.restoreConfig(server);
                } finally {
                    TestPlayers.leave(player);
                }
            }
        }
        helper.succeed();
    }

    // With restrictCommands off a non-op has vanilla's commands, but /msg and /tell stay YOLT's ranged whisper: a player
    // 200 blocks off gets neither, and the sender's reply is the usual one, which never says whether it arrived
    @GameTest
    public void nonOpMsgStaysRangedWithRestrictionOff(GameTestHelper helper) {
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean whispersBefore = config.chat.allowWhispers;
        int radiusBefore = config.chat.radius;
        int whisperRadiusBefore = config.chat.whisperRadius;
        TestPlayers.Recording sender = TestPlayers.joinRecording(helper, "msgfrom", GameType.SURVIVAL, BlockPos.ZERO);
        TestPlayers.Recording far = TestPlayers.joinRecording(helper, "msgfar", GameType.SURVIVAL, BlockPos.ZERO);
        try {
            config.restrictCommands = false;
            config.chat.allowWhispers = true;
            config.chat.radius = 48;
            config.chat.whisperRadius = 0;
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());
            String farName = far.nameAndId().name();
            sender.messages.clear();
            far.messages.clear();
            int from = TestPlayers.sentPackets(far).size();

            run(sender, "msg " + farName + " hi");
            run(sender, "tell " + farName + " hi");
            helper.assertValueEqual(sender.messages.stream().map(Component::getString).toList(),
                    List.of("You whisper to " + farName + ": hi", "You whisper to " + farName + ": hi"), "sender messages");
            helper.assertValueEqual(far.messages.stream().map(Component::getString).toList(), List.of(), "far player messages");
            helper.assertValueEqual(chatLines(far, from), List.of(), "chat lines sent to the far player");
        } finally {
            config.restrictCommands = restrictBefore;
            config.chat.allowWhispers = whispersBefore;
            config.chat.radius = radiusBefore;
            config.chat.whisperRadius = whisperRadiusBefore;
            TestPlayers.leave(sender);
            TestPlayers.leave(far);
        }
        helper.succeed();
    }

    // What a client sends for a command it has no signature for, as a vanilla client does for one its tree lacks
    private static void sendCommand(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    // Every chat line, system message and command reply sent to the player from packet number "from" on
    private static List<String> chatLines(ServerPlayer player, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        List<String> lines = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundSystemChatPacket system) {
                lines.add(system.content().getString());
            } else if (packet instanceof ClientboundPlayerChatPacket chat) {
                lines.add(chat.body().content());
            } else if (packet instanceof ClientboundDisguisedChatPacket disguised) {
                lines.add(disguised.message().getString());
            }
        }
        return lines;
    }

    // What an op-placed sign runs its click command as (vanilla's SignBlockEntity)
    private static CommandSourceStack signSource(ServerPlayer player) {
        return new CommandSourceStack(CommandSource.NULL, player.position(), Vec2.ZERO, player.level(),
                LevelBasedPermissionSet.GAMEMASTER, player.level().getServer(), player);
    }

    // The private step every typed command goes through, before vanilla checks its signatures
    private static ParseResults<CommandSourceStack> parseCommand(ServerPlayer player, String command) {
        return ((ServerGamePacketListenerInvoker) player.connection).yoltParseCommand(command);
    }

    private static void run(ServerPlayer player, String command) {
        run(player.createCommandSourceStack(), command);
    }

    private static void run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source, command);
    }
}
