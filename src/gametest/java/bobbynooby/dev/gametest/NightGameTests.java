package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.concurrent.atomic.AtomicBoolean;

// The night passing through the level's own tick. Each test holds the switch and the clock across ticks, so each has a
// batch of its own, where the sleeper is the only player: anyone else awake would hold the night up anyway. The batch
// puts the clock back when it ends. The clock keeps running, so each test moves it to midnight itself: a test only
// starts once its chunks are ready, which can take thousands of ticks.
// Test players get no living tick from a client, so each test drives the sleeper's, which counts how long it has slept.
public class NightGameTests {
    private static final String NIGHT = "you-only-live-twice-gametest:night";
    private static final String NIGHT_SKIP = "you-only-live-twice-gametest:night_skip";
    // Vanilla skips the night once everyone has slept this long
    private static final int DEEP_SLEEP_TICKS = 100;

    @GameTest(environment = NIGHT, maxTicks = 140, skyAccess = true)
    public void nightDoesNotSkipWithEveryoneAsleep(GameTestHelper helper) {
        BlockPos head = SleepGameTests.placeBed(helper);
        ServerPlayer sleeper = TestPlayers.join(helper, "NightOwl", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noNightSkip;
        Runnable cleanUp = cleanUp(sleeper, () -> sleep.noNightSkip = before);
        boolean waiting = false;
        try {
            sleep.noNightSkip = true;
            long asleepAt = goToSleep(helper, sleeper, head);
            helper.assertTrue(enoughAsleepForVanilla(helper, false), "someone else in the overworld is awake, so the night would not skip anyway");
            helper.runAfterDelay(DEEP_SLEEP_TICKS + 10, () -> {
                try {
                    long moved = SleepGameTests.clockTicks(helper) - asleepAt;
                    helper.assertTrue(moved < 200, "the clock moved " + moved + " ticks while everyone slept, so the night skipped");
                    helper.assertTrue(sleeper.isSleeping(), "the sleeper was woken up");
                    helper.assertTrue(sleeper.isSleepingLongEnough(), "the sleeper has not slept long enough for the night to skip");
                    helper.assertTrue(enoughAsleepForVanilla(helper, true), "vanilla would not skip the night now either, so nothing was tested");
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

    // Vanilla moves the clock on to the next morning and wakes everyone once all are deep asleep
    @GameTest(environment = NIGHT_SKIP, maxTicks = 140, skyAccess = true)
    public void nightSkipsWhenSwitchOff(GameTestHelper helper) {
        BlockPos head = SleepGameTests.placeBed(helper);
        ServerPlayer sleeper = TestPlayers.join(helper, "EarlyBird", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noNightSkip;
        Runnable cleanUp = cleanUp(sleeper, () -> sleep.noNightSkip = before);
        boolean waiting = false;
        try {
            sleep.noNightSkip = false;
            long asleepAt = goToSleep(helper, sleeper, head);
            helper.assertTrue(enoughAsleepForVanilla(helper, false), "someone else in the overworld is awake, so the night can't skip");
            helper.runBeforeTestEnd(cleanUp);
            helper.succeedWhen(() -> {
                long now = SleepGameTests.clockTicks(helper);
                // Midnight is 6000 ticks before the morning, and the wake-up time is the very start of the day
                helper.assertTrue(now - asleepAt > 5000 && Math.floorMod(now, 24000L) < 1000,
                        "the clock is at " + now + ", " + (now - asleepAt) + " ticks after the sleeper got into bed, not the next morning");
                helper.assertFalse(sleeper.isSleeping(), "the sleeper is still in bed");
                cleanUp.run();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Into bed at midnight, then the sleeper's tick from now on. Returns the clock at that point.
    private static long goToSleep(GameTestHelper helper, ServerPlayer sleeper, BlockPos head) {
        SleepGameTests.toMidnight(helper);
        SleepGameTests.useBed(helper, sleeper, head);
        helper.assertTrue(sleeper.isSleeping(), "the sleeper could not get into the bed");
        // Stops once the sleeper has left
        helper.onEachTick(() -> {
            if (!sleeper.isRemoved()) {
                sleeper.doTick();
            }
        });
        return SleepGameTests.clockTicks(helper);
    }

    // Vanilla's own check of whether enough of the level's players are in bed (deep: and have slept long enough), with
    // YOLT's night block out of the way for the call. Anyone else awake in the overworld makes it false.
    private static boolean enoughAsleepForVanilla(GameTestHelper helper, boolean deep) {
        ServerLevel level = helper.getLevel();
        YoltConfig.SleepSection sleep = Settings.get().sleep;
        boolean before = sleep.noNightSkip;
        sleep.noNightSkip = false;
        try {
            SleepStatus status = new SleepStatus();
            status.update(level.players());
            int percentage = level.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
            return status.areEnoughSleeping(percentage) && (!deep || status.areEnoughDeepSleeping(percentage, level.players()));
        } finally {
            sleep.noNightSkip = before;
        }
    }

    // Puts the switch back and the sleeper out, once only, at the test's end or just before its timeout
    private static Runnable cleanUp(ServerPlayer sleeper, Runnable restore) {
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                restore.run();
                SleepGameTests.wakeAndLeave(sleeper);
            }
        };
    }
}
