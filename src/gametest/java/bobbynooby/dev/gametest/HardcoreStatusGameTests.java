package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.HardcoreConfig;
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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
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
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Each test flips its switches and puts them back inside one tick, so no other test sees them. A test line answers
// only for the test's own player, since every test shares the one server.
public class HardcoreStatusGameTests {
    // Every switch off, said out loud
    private static final String ALL_OFF = "you-only-live-twice-gametest:hardcore_all_off";
    // The limp on, which needs a tick to see a player's health
    private static final String LIMP = "you-only-live-twice-gametest:hardcore_limp";
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
            helper.assertTrue(lines.contains("Total " + percent + "% slower · cap " + cap + "%"),
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
        boolean coldBefore = config.hardcore.cold.enabled;
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
            config.hardcore.cold.enabled = true;
            player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            Legs.set(id, true);
            Curse.set(id, 1);
            HardcoreStates.update(id, s -> {
                s.protein = 0.6;
                s.vitamins = 0.1;
                s.sugar = 0.4;
                s.chill = 0.35;
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

            // Detail lines too, since each is a message of its own with the hover of its main line
            for (String start : List.of("Load ", "  Cobblestone ×64 ", "Legs ", "Total ", "Curse ", "  next heart back in ",
                    "Nutrients ", "  low vitamins: ", "Cold ", "Resting ", "Server rules", "  hunger ×1.5", "  Mending ")) {
                helper.assertTrue(first.stream().anyMatch(text -> text.startsWith(start)), "the report has no '" + start + "' line: " + first);
            }
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
            config.hardcore.cold.enabled = coldBefore;
            Legs.set(id, false);
            Curse.set(id, 0);
            HardcoreStates.update(id, s -> {
                s.protein = HardcoreState.UNSET;
                s.vitamins = HardcoreState.UNSET;
                s.sugar = HardcoreState.UNSET;
                s.chill = 0.0;
            });
            player.getInventory().clearContent();
            TestPlayers.leave(player);
            TestPlayers.leave(other);
        }
        helper.succeed();
    }

    // No line wraps in a default chat window, even at its longest: a long item name with a count, the curse at its
    // most, all three nutrients low, frozen, and every rule on. A name too long to fit is cut, never the count or weight.
    @GameTest
    public void status1EveryLineFitsTheChat(GameTestHelper helper) {
        List<String> lines = fullReport(helper, "Wordy").stream().map(Component::getString).toList();
        int most = Settings.get().hardcore.curse.maxLostHearts;
        helper.assertTrue(lines.contains("Curse −" + most + " max hearts (the most)"), "the curse at its most: " + lines);
        helper.assertTrue(lines.contains("Nutrients protein 24% · vitamins 24% · sugar 24%"), "the nutrients line: " + lines);
        helper.assertTrue(lines.stream().anyMatch(line -> line.startsWith("  Waxed Weathered") && line.contains(" ×20 (")),
                "the report has no long item name with its count: " + lines);
        for (String line : lines) {
            helper.assertTrue(line.length() <= Status.WIDTH, "'" + line + "' is " + line.length() + " characters");
        }
        helper.assertValueEqual(Status.fit("Waxed Weathered Copper Golem Statue", 20), "Waxed Weathered Cop…", "a cut name");
        helper.assertValueEqual(Status.fit("Cobblestone", 11), "Cobblestone", "a name that fits");
        helper.succeed();
    }

    // Read from each part's style as the client puts it together, the line's own style under the part's
    @GameTest
    public void status1LinesCarryColoursAndHovers(GameTestHelper helper) {
        List<Component> report = fullReport(helper, "Colourful");
        Component header = report.getFirst();
        helper.assertValueEqual(header.getString(), "Your body", "the first line");
        helper.assertTrue(coloured(header.getStyle(), ChatFormatting.GOLD) && header.getStyle().isBold(),
                "the header's style: " + header.getStyle());

        Component legs = startingWith(helper, report, "Legs ");
        helper.assertTrue(coloured(styleOf(helper, legs, "Legs"), ChatFormatting.YELLOW), "the legs label: " + legs);
        helper.assertTrue(coloured(styleOf(helper, legs, "broken"), ChatFormatting.RED), "broken legs: " + legs);
        helper.assertTrue(coloured(styleOf(helper, legs, "healing"), ChatFormatting.GREEN), "the healing: " + legs);
        helper.assertTrue(coloured(styleOf(helper, legs, " · "), ChatFormatting.GRAY), "the separator: " + legs);
        helper.assertTrue(coloured(styleOf(helper, startingWith(helper, report, "  low vitamins: "), "low vitamins"),
                ChatFormatting.RED), "the cost of low vitamins");

        int load = report.indexOf(startingWith(helper, report, "Load "));
        Component detail = report.get(load + 1);
        helper.assertTrue(detail.getString().startsWith("  Cobblestone"), "the line under the load: " + detail.getString());
        Style item = styleOf(helper, detail, "Cobblestone");
        helper.assertTrue(coloured(item, ChatFormatting.GRAY), "the detail line's style: " + item);
        String loadHover = hover(styleOf(helper, report.get(load), "Load"));
        helper.assertTrue(loadHover != null && loadHover.equals(hover(item)), "the detail's hover: " + hover(item));
        // The hover covers the whole line, not only its first part
        helper.assertValueEqual(hover(styleOf(helper, report.get(load), "slower")), loadHover, "the hover on the slowdown");
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

    // Something with no body, the console or a command block, is told so and nothing else, and the command fails
    @GameTest
    public void status1ConsoleIsToldOnlyAPlayerHasABody(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean weightBefore = config.hardcore.weight.enabled;
        try {
            config.hardcore.weight.enabled = true;
            int[] result = {-1};
            helper.assertValueEqual(CommandGameTests.run(server, "status", value -> result[0] = value),
                    List.of("Only a player has a body to report on."), "the console's /status");
            helper.assertValueEqual(result[0], 0, "the console's result");

            BlockPos blockAt = new BlockPos(4, 1, 4);
            helper.setBlock(blockAt, Blocks.COMMAND_BLOCK);
            CommandBlockEntity block = helper.getBlockEntity(blockAt, CommandBlockEntity.class);
            block.getCommandBlock().setCommand("status");
            block.getCommandBlock().performCommand(helper.getLevel());
            // A command block counts any run that parsed as a success, whatever the command returned, so only its
            // output tells
            helper.assertTrue(block.getCommandBlock().getLastOutput().getString().endsWith("Only a player has a body to report on."),
                    "the command block's output: " + block.getCommandBlock().getLastOutput().getString());
        } finally {
            config.hardcore.weight.enabled = weightBefore;
        }
        helper.succeed();
    }

    // The report goes straight to the player, so it shows even with command feedback off
    @GameTest
    public void status1ReportShowsWithCommandFeedbackOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Unfed", GameType.SURVIVAL, AT);
        YoltConfig config = Settings.get();
        boolean weightBefore = config.hardcore.weight.enabled;
        boolean feedbackBefore = helper.getLevel().getGameRules().get(GameRules.SEND_COMMAND_FEEDBACK);
        Runnable unregister = testLine(player);
        try {
            config.hardcore.weight.enabled = true;
            helper.getLevel().getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false, server);
            int from = player.messages.size();
            sendCommand(player, "status");
            List<String> lines = texts(player, from);
            helper.assertTrue(!lines.isEmpty() && lines.getFirst().equals("Your body"), "the report: " + lines);
            helper.assertTrue(lines.stream().anyMatch(line -> line.startsWith("Load ")), "the report has no load line: " + lines);
            helper.assertTrue(lines.contains(TEST_LINE), "the report has no test line: " + lines);
        } finally {
            helper.getLevel().getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, feedbackBefore, server);
            config.hardcore.weight.enabled = weightBefore;
            unregister.run();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The hunger tax has no switch, so a multiplier other than 1 is a feature on by itself: everyone gets /status, and
    // the report is the rules block alone
    @GameTest(environment = ALL_OFF)
    public void status1HungerTaxAloneTurnsStatusOn(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "TaxedOnly", GameType.SURVIVAL, AT);
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        double hungerBefore = config.hardcore.hungerTax.multiplier;
        try {
            helper.assertFalse(Hardcore.anyOn(), "a hardcore switch is on");
            config.hardcore.hungerTax.multiplier = 2.0;
            config.restrictCommands = true;
            helper.assertTrue(Hardcore.anyOn(), "the hunger tax alone isn't a feature on");
            helper.getLevel().getServer().getCommands().sendCommands(player);
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, player).getChild("status") != null,
                    "the tree sent to a non-op has no status with the hunger tax alone");
            int from = player.messages.size();
            sendCommand(player, "status");
            helper.assertValueEqual(texts(player, from), List.of("Your body", "Server rules", "  hunger ×2"), "the report");
        } finally {
            config.hardcore.hungerTax.multiplier = hungerBefore;
            config.restrictCommands = restrictBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A line that throws is left out and logged; the rest of the report still comes
    @GameTest
    public void status1ABrokenLineCostsNothingElse(GameTestHelper helper) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Broken", GameType.SURVIVAL, AT);
        UUID id = player.getUUID();
        YoltConfig config = Settings.get();
        boolean weightBefore = config.hardcore.weight.enabled;
        Runnable unregisterBroken = Status.registerForTesting(12, p -> {
            if (p.getUUID().equals(id)) {
                throw new IllegalStateException("a status line that fails");
            }
            return List.of();
        });
        Runnable unregisterGood = testLine(player);
        try {
            config.hardcore.weight.enabled = true;
            int from = player.messages.size();
            sendCommand(player, "status");
            List<String> lines = texts(player, from);
            helper.assertTrue(!lines.isEmpty() && lines.getFirst().equals("Your body"), "the report: " + lines);
            helper.assertTrue(lines.stream().anyMatch(line -> line.startsWith("Load ")), "the report has no load line: " + lines);
            helper.assertTrue(lines.contains(TEST_LINE), "the report has no test line: " + lines);
        } finally {
            config.hardcore.weight.enabled = weightBefore;
            unregisterBroken.run();
            unregisterGood.run();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // With a feature on but nothing to say about this player, an op reading their report is told so
    @GameTest
    public void status1OpReadsNothingToReportForAQuietPlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Quiet", GameType.SURVIVAL, AT);
        String name = player.nameAndId().name();
        YoltConfig config = Settings.get();
        boolean legsBefore = config.hardcore.legs.enabled;
        try {
            config.hardcore.legs.enabled = true;
            helper.assertTrue(Hardcore.anyOn(), "no hardcore feature is on");
            helper.assertValueEqual(CommandGameTests.run(server, "yolt status " + name), List.of(name + "'s body", "Nothing to report"),
                    "/yolt status for a player with whole legs");
        } finally {
            config.hardcore.legs.enabled = legsBefore;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The limp has no line of its own, so the Total line's hover names it, and only while the player limps
    @GameTest(environment = LIMP, maxTicks = 20)
    public void status1TotalHoverNamesALimp(GameTestHelper helper) {
        ServerPlayer limper = TestPlayers.join(helper, "HoverLimper", GameType.SURVIVAL, AT);
        ServerPlayer walker = TestPlayers.join(helper, "HoverWalker", GameType.SURVIVAL, AT.offset(3, 0, 0));
        UUID walkerId = walker.getUUID();
        Runnable unregister = Slowdowns.registerForTesting("test", p -> p.getUUID().equals(walkerId) ? 0.1 : 0.0);
        Runnable cleanUp = BodyTestKit.once(() -> {
            unregister.run();
            TestPlayers.leave(limper);
            TestPlayers.leave(walker);
        });
        boolean waiting = false;
        try {
            limper.setHealth(3.0F);
            helper.runAfterDelay(2, () -> {
                try {
                    String limping = totalHover(helper, limper);
                    helper.assertTrue(limping.endsWith(" It includes a limp from low health."), "the limper's hover: " + limping);
                    String walking = totalHover(helper, walker);
                    helper.assertFalse(walking.contains("limp"), "the hover at full health: " + walking);
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // The order the hardcore page shows, and the rules in theirs
    @GameTest
    public void status1LinesComeInTheDocumentedOrder(GameTestHelper helper) {
        List<String> lines = fullReport(helper, "Orderly").stream().map(Component::getString).toList();
        int last = -1;
        for (String start : List.of("Your body", "Load ", "Legs ", "Total ", "Curse ", "Nutrients ", "Cold ", "Resting ",
                "Server rules", "  hunger ×", "  Mending ", "  anvil: ")) {
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(start)) {
                    at = i;
                    break;
                }
            }
            helper.assertTrue(at > last, "'" + start + "' at line " + at + ", not after line " + last + ": " + lines);
            last = at;
        }
        helper.succeed();
    }

    // Names are matched as the player list matches them, whatever their case
    @GameTest
    public void status1YoltStatusNameCase(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "CaseMixed", GameType.SURVIVAL, AT);
        String name = player.nameAndId().name();
        Runnable unregister = testLine(player);
        YoltConfig config = Settings.get();
        boolean weightBefore = config.hardcore.weight.enabled;
        try {
            config.hardcore.weight.enabled = true;
            for (String typed : List.of(name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT))) {
                List<String> output = CommandGameTests.run(server, "yolt status " + typed);
                helper.assertTrue(!output.isEmpty() && output.getFirst().equals(name + "'s body"),
                        "/yolt status " + typed + ": " + output);
                helper.assertTrue(output.contains(TEST_LINE), "/yolt status " + typed + " left out the test line: " + output);
            }
        } finally {
            config.hardcore.weight.enabled = weightBefore;
            unregister.run();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The Total line's hover, which the report must have
    private static String totalHover(GameTestHelper helper, ServerPlayer player) {
        Component total = startingWith(helper, Status.report(player), "Total ");
        String text = hover(total.getStyle());
        helper.assertTrue(text != null, "the Total line has no hover");
        return text;
    }

    // A /status at its fullest: every feature on with something to say, in a bed. Everything is put back before it returns.
    private static List<Component> fullReport(GameTestHelper helper, String name) {
        BlockPos bed = bed(helper, new BlockPos(1, 1, 1));
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, name, GameType.SURVIVAL, AT);
        UUID id = player.getUUID();
        HardcoreConfig.HardcoreSection hardcore = Settings.get().hardcore;
        boolean weightBefore = hardcore.weight.enabled;
        boolean legsBefore = hardcore.legs.enabled;
        boolean curseBefore = hardcore.curse.enabled;
        boolean nutrientsBefore = hardcore.nutrients.enabled;
        boolean bedRestBefore = hardcore.bedRest.enabled;
        boolean coldBefore = hardcore.cold.enabled;
        boolean mendingBefore = hardcore.mending.enabled;
        boolean anvilBefore = hardcore.anvil.enabled;
        double hungerBefore = hardcore.hungerTax.multiplier;
        try {
            hardcore.weight.enabled = true;
            hardcore.legs.enabled = true;
            hardcore.curse.enabled = true;
            hardcore.nutrients.enabled = true;
            hardcore.bedRest.enabled = true;
            hardcore.cold.enabled = true;
            hardcore.mending.enabled = true;
            hardcore.anvil.enabled = true;
            hardcore.hungerTax.multiplier = 1.5;
            helper.assertTrue(0.24 < hardcore.nutrients.deficientBelow, "24% is not low");
            for (int i = 0; i < 12; i++) {
                player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            }
            // One at a time, so they end up together whether or not they stack
            for (int i = 0; i < 20; i++) {
                player.getInventory().add(new ItemStack(Items.COPPER_GOLEM_STATUE.waxed().weathered()));
            }
            Legs.set(id, true);
            Curse.set(id, hardcore.curse.maxLostHearts);
            HardcoreStates.update(id, s -> {
                s.protein = 0.24;
                s.vitamins = 0.24;
                s.sugar = 0.24;
                s.chill = 1.0;
            });
            helper.assertTrue(player.startSleeping(helper.absolutePos(bed)), "the player could not lie in the bed");
            int from = player.messages.size();
            sendCommand(player, "status");
            return List.copyOf(player.messages.subList(from, player.messages.size()));
        } finally {
            if (player.isSleeping()) {
                player.stopSleeping();
            }
            hardcore.weight.enabled = weightBefore;
            hardcore.legs.enabled = legsBefore;
            hardcore.curse.enabled = curseBefore;
            hardcore.nutrients.enabled = nutrientsBefore;
            hardcore.bedRest.enabled = bedRestBefore;
            hardcore.cold.enabled = coldBefore;
            hardcore.mending.enabled = mendingBefore;
            hardcore.anvil.enabled = anvilBefore;
            hardcore.hungerTax.multiplier = hungerBefore;
            Legs.set(id, false);
            Curse.set(id, 0);
            HardcoreStates.update(id, s -> {
                s.protein = HardcoreState.UNSET;
                s.vitamins = HardcoreState.UNSET;
                s.sugar = HardcoreState.UNSET;
                s.chill = 0.0;
            });
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        }
    }

    private static Component startingWith(GameTestHelper helper, List<Component> report, String start) {
        Optional<Component> line = report.stream().filter(text -> text.getString().startsWith(start)).findFirst();
        helper.assertTrue(line.isPresent(), "the report has no '" + start + "' line: " + report);
        return line.get();
    }

    // The style a part of the line is drawn with: its own, filled in from the line's
    private static Style styleOf(GameTestHelper helper, Component line, String part) {
        Optional<Style> style = line.visit((partStyle, text) -> text.contains(part) ? Optional.of(partStyle) : Optional.empty(),
                Style.EMPTY);
        helper.assertTrue(style.isPresent(), "'" + line.getString() + "' has no part '" + part + "'");
        return style.get();
    }

    private static boolean coloured(Style style, ChatFormatting colour) {
        return Objects.equals(style.getColor(), TextColor.fromLegacyFormat(colour));
    }

    private static @Nullable String hover(Style style) {
        return style.getHoverEvent() instanceof HoverEvent.ShowText(Component text) ? text.getString() : null;
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
                ? List.of(Status.line("A line for this test alone.", Component.literal(TEST_LINE)))
                : List.of());
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
