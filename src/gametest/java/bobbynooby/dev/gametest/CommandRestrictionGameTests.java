package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.IOException;
import java.lang.reflect.Method;
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
    public void restrictedParseFailsAtFirstWord(GameTestHelper helper) throws ReflectiveOperationException {
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
                Settings.save();
                run(server.createCommandSourceStack(), "yolt reload");
                helper.assertValueEqual(lastTreeHasWhisper(helper, player), whispers,
                        "/w in the tree resent after a reload with whispers " + (whispers ? "on" : "off"));
            }
        } finally {
            // The settings go back before the player leaves
            Files.writeString(path, fileBefore);
            CommandGameTests.restoreConfig(server);
            TestPlayers.leave(player);
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
            RequiredArgumentBuilder<CommandSourceStack, ?> argument = RequiredArgumentBuilder.argument(id, type);
            return argument;
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

    // What an op-placed sign runs its click command as (vanilla's SignBlockEntity)
    private static CommandSourceStack signSource(ServerPlayer player) {
        return new CommandSourceStack(CommandSource.NULL, player.position(), Vec2.ZERO, player.level(),
                LevelBasedPermissionSet.GAMEMASTER, player.level().getServer(), player);
    }

    // The private step every typed command goes through, before vanilla checks its signatures
    @SuppressWarnings("unchecked")
    private static ParseResults<CommandSourceStack> parseCommand(ServerPlayer player, String command) throws ReflectiveOperationException {
        Method parse = ServerGamePacketListenerImpl.class.getDeclaredMethod("parseCommand", String.class);
        parse.setAccessible(true);
        return (ParseResults<CommandSourceStack>) parse.invoke(player.connection, command);
    }

    private static void run(ServerPlayer player, String command) {
        run(player.createCommandSourceStack(), command);
    }

    private static void run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source, command);
    }
}
