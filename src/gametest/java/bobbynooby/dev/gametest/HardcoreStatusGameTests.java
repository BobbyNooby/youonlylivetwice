package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.mixin.ServerGamePacketListenerInvoker;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Each test flips its switches and puts them back inside one tick, so no other test sees them. A test line answers
// only for the test's own player, since every test shares the one server.
public class HardcoreStatusGameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final String TEST_LINE = "Feeling fine";
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    @GameTest
    public void status1NonOpCanRunItWhenAFeatureIsOn(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Body", GameType.SURVIVAL, AT);
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean weightBefore = config.hardcore.weight.enabled;
        Runnable unregister = testLine(player);
        try {
            config.restrictCommands = true;
            config.hardcore.weight.enabled = true;
            helper.assertTrue(CommandRestriction.restricted(player), "the test player is not restricted");
            // What a config change does for every player
            helper.getLevel().getServer().getCommands().sendCommands(player);
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, player).getChild("status") != null,
                    "the tree sent to a non-op has no status");

            int from = player.messages.size();
            sendCommand(player, "status");
            List<String> lines = texts(player, from);
            helper.assertTrue(!lines.isEmpty() && lines.getFirst().equals("Your body"), "the report: " + lines);
            helper.assertTrue(lines.contains(TEST_LINE), "the report has no test line: " + lines);
        } finally {
            config.restrictCommands = restrictBefore;
            config.hardcore.weight.enabled = weightBefore;
            unregister.run();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The numbers the report does show are picked so none of them can be one of the caller's coordinates by chance
    @GameTest
    public void status1NeverPrintsCoordinatesOrPlayers(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Private", GameType.SURVIVAL, AT);
        ServerPlayer other = TestPlayers.join(helper, "Nearby", GameType.SURVIVAL, AT.offset(3, 0, 0));
        String otherName = other.nameAndId().name();
        BlockPos pos = player.blockPosition();
        Set<String> coordinates = Set.of(Integer.toString(Math.abs(pos.getX())), Integer.toString(Math.abs(pos.getY())),
                Integer.toString(Math.abs(pos.getZ())));
        int percent = notIn(coordinates, 20, 25, 30, 35);
        int cap = notIn(coordinates, 50, 55, 60, 65);
        UUID id = player.getUUID();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean weightBefore = config.hardcore.weight.enabled;
        double capBefore = config.hardcore.slow.cap;
        Runnable unregisterLine = testLine(player);
        Runnable unregisterSlow = Slowdowns.registerForTesting("test", p -> p.getUUID().equals(id) ? percent / 100.0 : 0.0);
        try {
            config.restrictCommands = true;
            // Every feature built so far
            config.hardcore.weight.enabled = true;
            config.hardcore.slow.cap = cap / 100.0;

            int from = player.messages.size();
            sendCommand(player, "status");
            List<Component> report = player.messages.subList(from, player.messages.size());
            List<String> lines = report.stream().map(Component::getString).toList();
            helper.assertTrue(lines.contains("Total slowdown " + percent + "% (cap " + cap + "%; potions not included)"),
                    "the report has no total slowdown: " + lines);
            helper.assertTrue(lines.contains(TEST_LINE), "the report has no test line: " + lines);

            for (Component line : report) {
                List<String> texts = new ArrayList<>(List.of(line.getString()));
                if (line.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component hover)) {
                    texts.add(hover.getString());
                }
                for (String text : texts) {
                    Matcher digits = DIGITS.matcher(text);
                    while (digits.find()) {
                        helper.assertFalse(coordinates.contains(digits.group()), "'" + text + "' holds a coordinate of " + pos);
                    }
                    helper.assertFalse(text.contains(otherName), "'" + text + "' names the other player");
                }
            }
        } finally {
            config.restrictCommands = restrictBefore;
            config.hardcore.weight.enabled = weightBefore;
            config.hardcore.slow.cap = capBefore;
            unregisterLine.run();
            unregisterSlow.run();
            TestPlayers.leave(player);
            TestPlayers.leave(other);
        }
        helper.succeed();
    }

    @GameTest
    public void status1NonOpCantNameATarget(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TestPlayers.Recording caller = TestPlayers.joinRecording(helper, "Caller", GameType.SURVIVAL, AT);
        ServerPlayer other = TestPlayers.join(helper, "Other", GameType.SURVIVAL, AT.offset(3, 0, 0));
        String otherName = other.nameAndId().name();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean weightBefore = config.hardcore.weight.enabled;
        Runnable unregister = testLine(other);
        try {
            config.restrictCommands = true;
            config.hardcore.weight.enabled = true;
            helper.assertTrue(Commands.getParseException(parseCommand(caller, "status")) == null, "a non-op's /status did not parse");
            helper.assertTrue(Commands.getParseException(parseCommand(caller, "status " + otherName)) != null,
                    "a non-op's /status parsed with a target");
            int from = caller.messages.size();
            sendCommand(caller, "status " + otherName);
            List<String> told = texts(caller, from);
            helper.assertFalse(told.contains("Your body") || told.contains(TEST_LINE), "a non-op's /status with a target reported: " + told);

            List<String> output = CommandGameTests.run(server, "yolt status " + otherName);
            helper.assertTrue(!output.isEmpty() && output.getFirst().equals(otherName + "'s body"), "/yolt status output: " + output);
            helper.assertTrue(output.contains(TEST_LINE), "/yolt status left out the test line: " + output);
            helper.assertValueEqual(CommandGameTests.run(server, "yolt status NoSuchPlayer123"),
                    List.of("NoSuchPlayer123 is not online."), "/yolt status for someone not online");
        } finally {
            config.restrictCommands = restrictBefore;
            config.hardcore.weight.enabled = weightBefore;
            unregister.run();
            TestPlayers.leave(caller);
            TestPlayers.leave(other);
        }
        helper.succeed();
    }

    @GameTest
    public void off1StatusSaysNothingToReport(GameTestHelper helper) {
        helper.assertFalse(Hardcore.anyOn(), "a hardcore switch is on");
        MinecraftServer server = helper.getLevel().getServer();
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Unbothered", GameType.SURVIVAL, AT);
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        try {
            // Run with an op's permissions, so the restriction can't be what answers
            CommandSourceStack asOp = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            int from = player.messages.size();
            server.getCommands().performPrefixedCommand(asOp, "status");
            helper.assertValueEqual(texts(player, from), List.of("Nothing to report"), "an op's /status with the layer off");

            config.restrictCommands = true;
            helper.assertTrue(CommandRestriction.restricted(player), "the test player is not restricted");
            server.getCommands().sendCommands(player);
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, player).getChild("status") == null,
                    "the tree sent to a non-op has status with the layer off");
        } finally {
            config.restrictCommands = restrictBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static Runnable testLine(ServerPlayer player) {
        UUID id = player.getUUID();
        return Status.registerForTesting(15, p -> p.getUUID().equals(id)
                ? Optional.of(Status.line(TEST_LINE, "A line for this test alone."))
                : Optional.empty());
    }

    private static int notIn(Set<String> taken, int... candidates) {
        for (int candidate : candidates) {
            if (!taken.contains(Integer.toString(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("every candidate is a coordinate");
    }

    // What a client sends for a command typed in chat
    private static void sendCommand(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    // The private step every typed command goes through
    private static ParseResults<CommandSourceStack> parseCommand(ServerPlayer player, String command) {
        return ((ServerGamePacketListenerInvoker) player.connection).yoltParseCommand(command);
    }

    private static List<String> texts(TestPlayers.Recording player, int from) {
        return player.messages.subList(from, player.messages.size()).stream().map(Component::getString).toList();
    }
}
