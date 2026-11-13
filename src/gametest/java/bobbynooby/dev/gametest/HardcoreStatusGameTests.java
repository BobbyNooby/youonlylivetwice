package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.mixin.ServerGamePacketListenerInvoker;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.Hardcore;
import bobbynooby.dev.hardcore.HardcoreState;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.hardcore.Status;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

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

    // Every line there is, each with something to say. A report that stays the same when the player moves along every
    // axis holds no position or distance, and unlike a digit scan it can't trip on a number that happens to match one.
    @GameTest
    public void status1EveryLineHidesCoordinatesAndPlayers(GameTestHelper helper) {
        BlockPos firstBed = bed(helper, new BlockPos(1, 1, 1));
        BlockPos secondBed = bed(helper, new BlockPos(4, 2, 3));
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Secretive", GameType.SURVIVAL, AT);
        ServerPlayer other = TestPlayers.join(helper, "Neighbour", GameType.SURVIVAL, AT.offset(3, 0, 0));
        String otherName = other.nameAndId().name();
        String otherId = other.getStringUUID();
        UUID id = player.getUUID();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean weightBefore = config.hardcore.weight.enabled;
        boolean legsBefore = config.hardcore.legs.enabled;
        boolean limpBefore = config.hardcore.limp.enabled;
        boolean curseBefore = config.hardcore.curse.enabled;
        boolean nutrientsBefore = config.hardcore.nutrients.enabled;
        boolean bedRestBefore = config.hardcore.bedRest.enabled;
        boolean mendingBefore = config.hardcore.mending.enabled;
        double hungerBefore = config.hardcore.hungerTax.multiplier;
        try {
            config.restrictCommands = true;
            config.hardcore.weight.enabled = true;
            config.hardcore.legs.enabled = true;
            config.hardcore.limp.enabled = true;
            config.hardcore.curse.enabled = true;
            config.hardcore.nutrients.enabled = true;
            config.hardcore.bedRest.enabled = true;
            config.hardcore.mending.enabled = true;
            config.hardcore.hungerTax.multiplier = 1.5;
            player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            Legs.set(id, true);
            Curse.set(id, 1);
            HardcoreStates.update(id, s -> {
                s.protein = 0.6;
                s.vitamins = 0.1;
                s.sugar = 0.4;
            });

            helper.assertTrue(player.startSleeping(helper.absolutePos(firstBed)), "the player could not lie in the first bed");
            BlockPos firstPos = player.blockPosition();
            List<String> first = statusTexts(player);
            player.stopSleeping();
            helper.assertTrue(player.startSleeping(helper.absolutePos(secondBed)), "the player could not lie in the second bed");
            BlockPos secondPos = player.blockPosition();
            List<String> second = statusTexts(player);
            helper.assertTrue(firstPos.getX() != secondPos.getX() && firstPos.getY() != secondPos.getY()
                    && firstPos.getZ() != secondPos.getZ(), "the beds share an axis: " + firstPos + ", " + secondPos);

            for (String start : List.of("Load ", "  heaviest: ", "Legs ", "Total slowdown ", "Curse of the Undead ",
                    "Nutrients ", "Resting ", "Rules ")) {
                helper.assertTrue(first.stream().anyMatch(text -> text.startsWith(start)), "the report has no '" + start + "' line: " + first);
            }
            helper.assertTrue(first.stream().anyMatch(text -> text.startsWith("Nutrients ") && text.contains("(low: ")),
                    "the nutrients line shows nothing low: " + first);
            helper.assertTrue(first.stream().anyMatch(text -> text.startsWith("Rules ") && text.contains("hunger ×1.5")
                    && text.contains("Mending ")), "the rules line misses hunger or Mending: " + first);
            helper.assertValueEqual(second, first, "the report after moving from " + firstPos + " to " + secondPos);
            for (String text : first) {
                helper.assertFalse(text.contains(otherName) || text.contains(otherId), "'" + text + "' names the other player");
            }
        } finally {
            if (player.isSleeping()) {
                player.stopSleeping();
            }
            config.restrictCommands = restrictBefore;
            config.hardcore.weight.enabled = weightBefore;
            config.hardcore.legs.enabled = legsBefore;
            config.hardcore.limp.enabled = limpBefore;
            config.hardcore.curse.enabled = curseBefore;
            config.hardcore.nutrients.enabled = nutrientsBefore;
            config.hardcore.bedRest.enabled = bedRestBefore;
            config.hardcore.mending.enabled = mendingBefore;
            config.hardcore.hungerTax.multiplier = hungerBefore;
            Legs.set(id, false);
            Curse.set(id, 0);
            HardcoreStates.update(id, s -> {
                s.protein = HardcoreState.UNSET;
                s.vitamins = HardcoreState.UNSET;
                s.sugar = HardcoreState.UNSET;
            });
            player.getInventory().clearContent();
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

    // Legs are still off, so only the load shows of the two
    @GameTest
    public void status1ShowsOnlyEnabledFeatures(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Featured", GameType.SURVIVAL, AT);
        YoltConfig config = Settings.get();
        boolean weightBefore = config.hardcore.weight.enabled;
        try {
            config.hardcore.weight.enabled = true;
            int from = player.messages.size();
            sendCommand(player, "status");
            List<String> lines = texts(player, from);
            helper.assertTrue(lines.stream().anyMatch(line -> line.startsWith("Load")), "the report has no load line: " + lines);
            helper.assertFalse(lines.stream().anyMatch(line -> line.startsWith("Legs")), "the report has a legs line: " + lines);
        } finally {
            config.hardcore.weight.enabled = weightBefore;
            TestPlayers.leave(player);
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

    // A red bed with its foot here, facing south; returns the head
    private static BlockPos bed(GameTestHelper helper, BlockPos foot) {
        BlockPos head = foot.relative(Direction.SOUTH);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
        return head;
    }

    // The /status reply, each line followed by its hover text
    private static List<String> statusTexts(TestPlayers.Recording player) {
        int from = player.messages.size();
        sendCommand(player, "status");
        List<String> texts = new ArrayList<>();
        for (Component line : player.messages.subList(from, player.messages.size())) {
            texts.add(line.getString());
            if (line.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component hover)) {
                texts.add(hover.getString());
            }
        }
        return texts;
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
