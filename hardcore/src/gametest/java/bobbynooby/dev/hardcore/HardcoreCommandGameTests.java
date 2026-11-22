package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * /status and /yolt-hardcore on their own, with YOLT's replies. Each test runs within one tick, but for the one with a
 * batch of its own, and before it ends puts back what it changed: the settings in use, the file, the players it joined
 * and opped, a blood moon it started and a curse it set. One thing stays, as in YOLT's own blood moon command test: a
 * blood moon started and stopped leaves its day in the store as the last one and the last one ended, and the store has
 * no way to take a day out again.
 */
public class HardcoreCommandGameTests {
    private static final BlockPos SPOT = new BlockPos(1, 1, 1);
    private static final String FAILED_READ = "Nothing changed: yolt-hardcore.json couldn't be read";
    // A batch of its own, for the test that holds the blood moon on across ticks
    private static final String WARNED = "yolt-hardcore-gametest:blood_moon_warned";
    private static final long DAY = 24000L;

    // Review Focus 5. Each admin command refused, then two of them run once the player is an op, so the refusals were
    // for the permission. The file that turns the blood moon on proves the refused reload read nothing.
    @GameTest
    public void aNonOpRunsStatusAndNothingUnderYoltHardcore(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        Path file = YoltHardcore.configFile();
        @Nullable String original = read(file);
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "notop", GameType.SURVIVAL, SPOT);
            String name = player.nameAndId().name();
            write(file, "{\"hardcore\": {\"bloodMoon\": {\"enabled\": true}}}");
            CommandSourceStack notOp = player.createCommandSourceStack();
            int mark = TestPlayers.mark(player);
            helper.assertValueEqual(TestPlayers.run(helper, notOp, "status"), 1, "/status as a non-op");
            helper.assertValueEqual(TestPlayers.said(player, mark), texts(Status.report(player)), "what /status said");
            helper.assertTrue(TestPlayers.said(player, mark).size() > 1, "the report had no lines");

            mark = TestPlayers.mark(player);
            for (String command : List.of("yolt-hardcore", "yolt-hardcore reload", "yolt-hardcore status " + name,
                    "yolt-hardcore bloodmoon start", "yolt-hardcore bloodmoon stop", "yolt-hardcore bloodmoon status",
                    "yolt-hardcore curse " + name + " set 1", "yolt-hardcore curse " + name + " clear")) {
                TestPlayers.refused(helper, notOp, command);
            }
            helper.assertValueEqual(TestPlayers.said(player, mark), List.of(), "what the refusals told the player");
            helper.assertFalse(Host.settings().bloodMoon.enabled, "a refused reload read the file");
            helper.assertFalse(BloodMoon.active(), "a refused start started a blood moon");
            helper.assertValueEqual(HardcoreStates.read(player.getUUID()).curseHeartsLost, 0, "the curse after a refused set");

            TestPlayers.op(player);
            CommandSourceStack op = player.createCommandSourceStack();
            helper.assertValueEqual(TestPlayers.run(helper, op, "yolt-hardcore"), 1, "/yolt-hardcore as an op");
            helper.assertValueEqual(TestPlayers.run(helper, op, "yolt-hardcore reload"), 1, "/yolt-hardcore reload as an op");
            helper.assertTrue(Host.settings().bloodMoon.enabled, "an op's reload didn't read the file");
        } finally {
            try {
                TestPlayers.leave(player);
            } finally {
                restore(file, original, before);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void theConsoleHasNoBodyToReportOn(GameTestHelper helper) {
        helper.assertValueEqual(TestPlayers.console(helper, "status", true, 0), List.of("Only a player has a body to report on."),
                "what /status told the console");
        helper.succeed();
    }

    @GameTest
    public void yoltHardcoreNamesTheFeaturesThatAreOn(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        try {
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore", false, 1),
                    List.of("Hardcore features on: curse, mending, anvil."), "the defaults");
            YoltHardcore.swapForTesting(with(c -> {
                c.curse.enabled = false;
                c.mending.enabled = false;
                c.anvil.enabled = false;
            }));
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore", false, 1),
                    List.of("Every hardcore feature is off."), "with every feature off");
            YoltHardcore.swapForTesting(with(c -> {
                c.weight.enabled = true;
                c.legs.enabled = true;
                c.limp.enabled = true;
                c.bedRest.enabled = true;
                c.nutrients.enabled = true;
                c.hungerTax.multiplier = 1.5;
                c.elytra.enabled = true;
                c.tougherMobs.enabled = true;
                c.relentlessMobs.enabled = true;
                c.bloodMoon.enabled = true;
                c.cold.enabled = true;
            }));
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore", false, 1),
                    List.of("Hardcore features on: weight, legs, limp, bedRest, curse, nutrients, hungerTax, mending, anvil,"
                            + " elytra, tougherMobs, relentlessMobs, bloodMoon, cold."), "with every feature on");
        } finally {
            YoltHardcore.swapForTesting(before);
        }
        helper.succeed();
    }

    // Through the command, as an op at the console would. The reload reads only, so the bad value stays in the file. A
    // {word} no text fills in is said after the notes, as YOLT's reload says it.
    @GameTest
    public void aReloadAppliesAChangedFileAndNamesABadValue(GameTestHelper helper) {
        HardcoreFile.Loaded inUse = HardcoreFile.Loaded.defaults();
        inUse.hardcore().weight.freeLoad = 20.0;
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(inUse);
        Path file = YoltHardcore.configFile();
        @Nullable String original = read(file);
        try {
            String typed = """
                    {"hardcore": {"bloodMoon": {"enabled": true}, "weight": {"freeLoad": "heavy"}},
                     "messages": {"bloodMoonSetLine": "Dawn {soon}."}}""";
            write(file, typed);
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore reload", false, 1), List.of(
                    "hardcore.weight.freeLoad: expected a number, using the current value",
                    "messages.bloodMoonSetLine: has {soon}, which it doesn't fill in, so it shows as typed",
                    "Reloaded yolt-hardcore.json."), "the reply");
            helper.assertTrue(Host.settings().bloodMoon.enabled, "the reload didn't turn the blood moon on");
            helper.assertValueEqual(Host.settings().weight.freeLoad, 20.0, "the value in use for the bad value");
            helper.assertValueEqual(Host.texts().setLine(), "Dawn {soon}.", "the set line");
            helper.assertValueEqual(read(file), typed, "the file after the reload");
        } finally {
            restore(file, original, before);
        }
        helper.succeed();
    }

    // Each run twice, for its notes and then its failure; a reload that reads nothing changes nothing, so the second
    // run reads the same
    @GameTest
    public void aReloadThatCantReadTheFileChangesNothing(GameTestHelper helper) {
        HardcoreFile.Loaded inUse = HardcoreFile.Loaded.defaults();
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(inUse);
        Path file = YoltHardcore.configFile();
        @Nullable String original = read(file);
        try {
            write(file, "{\"hardcore\": ");
            List<String> notes = TestPlayers.console(helper, "yolt-hardcore reload", false, 0);
            helper.assertValueEqual(notes.size(), 1, "the notes " + notes);
            helper.assertTrue(notes.getFirst().startsWith("config is not valid JSON ("), "the note " + notes.getFirst());
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore reload", true, 0), List.of(FAILED_READ),
                    "the failure for a file that isn't JSON");
            helper.assertTrue(YoltHardcore.swapForTesting(inUse) == inUse, "the settings in use changed");

            delete(file);
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore reload", false, 0),
                    List.of("config file is missing, nothing changed"), "the note for no file");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore reload", true, 0), List.of(FAILED_READ),
                    "the failure for no file");
            helper.assertTrue(YoltHardcore.swapForTesting(inUse) == inUse, "the settings in use changed");
            helper.assertFalse(Files.exists(file), "the reload wrote the missing file");
        } finally {
            restore(file, original, before);
        }
        helper.succeed();
    }

    @GameTest
    public void statusOfAPlayerReadsTheirBody(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "readme", GameType.SURVIVAL, SPOT);
            String name = player.nameAndId().name();
            List<String> expected = new ArrayList<>(List.of(name + "'s body"));
            expected.addAll(texts(Status.lines(player)));
            helper.assertTrue(expected.size() > 1, "the player had no status lines");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore status " + name, false, 1), expected,
                    "the report");

            YoltHardcore.swapForTesting(with(c -> {
                c.curse.enabled = false;
                c.mending.enabled = false;
                c.anvil.enabled = false;
            }));
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore status " + name, false, 1),
                    List.of(name + "'s body", "Nothing to report"), "the report with every feature off");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore status Nobody", true, 0),
                    List.of("Nobody is not online."), "the reply for a player who isn't online");
        } finally {
            try {
                TestPlayers.leave(player);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void bloodMoonCommandsStartStopAndReport(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        try {
            for (String command : List.of("start", "stop", "status")) {
                helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon " + command, true, 0),
                        List.of("The blood moon is turned off."), command + " while it is off");
            }
            YoltHardcore.swapForTesting(with(c -> c.bloodMoon.enabled = true));
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon status", false, 1),
                    List.of("No blood moon is up."), "status before");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon start", false, 1),
                    List.of("Started a blood moon."), "start");
            helper.assertTrue(BloodMoon.active(), "no blood moon after the start");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon start", true, 0),
                    List.of("A blood moon is up already."), "a second start");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon status", false, 1),
                    List.of("A blood moon is up, with 100% of it left."), "status during it");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon stop", false, 1),
                    List.of("Stopped the blood moon."), "stop");
            helper.assertFalse(BloodMoon.active(), "still up after the stop");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon stop", true, 0),
                    List.of("No blood moon is up."), "a second stop");
        } finally {
            try {
                BloodMoon.stop(server);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // From the warning until it rises, the status says one rises tonight. Only the blood moon's own tick warns, so the
    // settings stay swapped in across ticks, in a batch of its own. It reads a clock of the test's, past warnTick on a
    // day after every night the store has saved, so tonight rolls, and a warning saves no day.
    @GameTest(environment = WARNED, maxTicks = 100)
    public void bloodMoonStatusSaysOneRisesTonight(GameTestHelper helper) {
        HardcoreFile.Loaded settings = with(c -> {
            c.bloodMoon.enabled = true;
            c.bloodMoon.chance = 1.0;
            c.bloodMoon.minNightsBetween = 0;
        });
        HardcoreConfig.BloodMoonSection config = settings.hardcore().bloodMoon;
        long day = Math.max(Math.max(HardcoreStates.world(BloodMoon.LAST_DAY).orElse(0L),
                HardcoreStates.world(BloodMoon.ENDED_DAY).orElse(0L)), config.graceDays) + 100L;
        long clock = day * DAY + config.warnTick + 1;
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(settings);
        Runnable realClock = BloodMoon.useClockForTesting(() -> clock);
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanUp = () -> {
            if (cleaned.compareAndSet(false, true)) {
                try {
                    realClock.run();
                    BloodMoon.restartForTesting();
                } finally {
                    YoltHardcore.swapForTesting(before);
                }
            }
        };
        // For a test that times out; a step that fails cleans up for itself
        helper.runBeforeTestEnd(cleanUp);
        try {
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(BloodMoon.tonight(), "no warning past warnTick"))
                    .thenExecute(() -> {
                        try {
                            helper.assertFalse(BloodMoon.active(), "a blood moon is up before its start");
                            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore bloodmoon status", false, 1),
                                    List.of("A blood moon rises tonight."), "the status after the warning");
                        } finally {
                            cleanUp.run();
                        }
                    })
                    .thenSucceed();
        } catch (RuntimeException | Error e) {
            cleanUp.run();
            throw e;
        }
    }

    @GameTest
    public void curseCommandsSetAndClear(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(with(c -> c.curse.enabled = false));
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "cursed", GameType.SURVIVAL, SPOT);
            String name = player.nameAndId().name();
            String set = "yolt-hardcore curse " + name + " set ";
            helper.assertValueEqual(TestPlayers.console(helper, set + "1", true, 0),
                    List.of("The Curse of the Undead is turned off."), "set while it is off");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore curse " + name + " clear", true, 0),
                    List.of("The Curse of the Undead is turned off."), "clear while it is off");

            YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
            helper.assertValueEqual(TestPlayers.console(helper, set + "5", true, 0),
                    List.of("The curse takes at most 4 hearts."), "more than the cap");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore curse Nobody set 1", true, 0),
                    List.of("Nobody is not online."), "a player who isn't online");
            helper.assertValueEqual(TestPlayers.console(helper, set + "1", false, 1),
                    List.of(name + " has lost 1 max heart to the Curse of the Undead."), "set 1");
            helper.assertValueEqual(player.getMaxHealth(), 18.0F, "max health after set 1");
            helper.assertValueEqual(TestPlayers.console(helper, set + "2", false, 1),
                    List.of(name + " has lost 2 max hearts to the Curse of the Undead."), "set 2");
            helper.assertValueEqual(player.getMaxHealth(), 16.0F, "max health after set 2");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore curse " + name + " clear", false, 1),
                    List.of("Cleared " + name + "'s Curse of the Undead."), "clear");
            helper.assertValueEqual(player.getMaxHealth(), 20.0F, "max health after the clear");
            helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore curse " + name + " clear", false, 1),
                    List.of("Cleared " + name + "'s Curse of the Undead."), "a clear with nothing to clear");

            YoltHardcore.swapForTesting(with(c -> c.curse.maxLostHearts = 1));
            helper.assertValueEqual(TestPlayers.console(helper, set + "2", true, 0),
                    List.of("The curse takes at most 1 heart."), "more than a cap of 1");
        } finally {
            try {
                if (player != null) {
                    Curse.set(player.getUUID(), 0);
                }
            } finally {
                try {
                    TestPlayers.leave(player);
                } finally {
                    YoltHardcore.swapForTesting(before);
                }
            }
        }
        helper.succeed();
    }

    // The defaults with a change of the test's
    static HardcoreFile.Loaded with(Consumer<HardcoreConfig.HardcoreSection> change) {
        HardcoreFile.Loaded loaded = HardcoreFile.Loaded.defaults();
        change.accept(loaded.hardcore());
        return loaded;
    }

    private static List<String> texts(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    // The file as it was, or gone if it wasn't there, and the settings, which go back even when the file can't be
    static void restore(Path file, @Nullable String original, HardcoreFile.Loaded before) {
        try {
            if (original == null) {
                delete(file);
            } else {
                write(file, original);
            }
        } finally {
            YoltHardcore.swapForTesting(before);
        }
    }

    static @Nullable String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
