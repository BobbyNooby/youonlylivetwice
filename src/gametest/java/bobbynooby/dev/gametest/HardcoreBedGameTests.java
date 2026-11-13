package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.BedRest;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.List;
import java.util.UUID;

// Bed rest stays on through each test's environment, which also holds the clock at noon and the sky clear, so every
// bed here is a daytime nap. Test players get no living tick from a client: the tests that need vanilla's wake check
// or its sleep counter drive the sleeper's tick themselves, and the others leave vanilla's own regen out of it.
public class HardcoreBedGameTests {
    // Noon with the clock stopped, legs on as well
    private static final String BED = "you-only-live-twice-gametest:hardcore_bed";
    // The clock running and the night skip allowed, with the napper the only player
    private static final String BED_SKIP = "you-only-live-twice-gametest:hardcore_bed_skip";
    private static final BlockPos STANDING = new BlockPos(4, 1, 4);
    // Four blocks across from the bed's head, inside vanilla's 8 by 5 monster box
    private static final BlockPos ZOMBIE_AT = new BlockPos(5, 1, 2);
    private static final double CLOSE = 1e-9;

    // One health every 50 ticks from the moment the player lies down; each check is a few ticks after a heal and well
    // before the next. Food 17 is too little for vanilla's regen, so only the bed heals.
    @GameTest(environment = BED, maxTicks = 260)
    public void bed1HealsAtTheSetPaceAndStopsOnGettingUp(GameTestHelper helper) {
        BlockPos head = SleepGameTests.placeBed(helper);
        ServerPlayer player = TestPlayers.join(helper, "Rester", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        // Loaded, so the player can be hurt, which vanilla holds off until a client says the world is in
        TestPlayers.markLoaded(player);
        Runnable cleanUp = BodyTestKit.once(() -> SleepGameTests.wakeAndLeave(player));
        boolean waiting = false;
        try {
            feed(player, 17, 0.0F);
            player.setHealth(10.0F);
            SleepGameTests.useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the player could not lie down at noon");
            helper.runAfterDelay(55, () -> {
                try {
                    assertHealth(helper, player, 11.0F, "50 ticks in bed");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(105, () -> {
                try {
                    assertHealth(helper, player, 12.0F, "100 ticks in bed");
                    player.stopSleepInBed(false, true);
                    helper.assertFalse(player.isSleeping(), "the player is still in bed after getting up");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(205, () -> {
                try {
                    assertHealth(helper, player, 12.0F, "100 ticks after getting up");
                    SleepGameTests.useBed(helper, player, head);
                    helper.assertTrue(player.isSleeping(), "the player could not lie down again");
                    player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 1.0F);
                    helper.assertTrue(player.getHealth() < 12.0F, "the hit did no damage");
                    helper.assertFalse(player.isSleeping(), "taking damage did not wake the player");
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

    // Food 18 with no saturation heals legs a point every 80 ticks, so every 40 in bed. Health is full, so the bed's
    // own healing has nothing to do.
    @GameTest(environment = BED, maxTicks = 120)
    public void bed2LegsHealTwiceAsFast(GameTestHelper helper) {
        BlockPos head = SleepGameTests.placeBed(helper);
        ServerPlayer rester = TestPlayers.join(helper, "LegRester", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        ServerPlayer stander = TestPlayers.join(helper, "LegStander", GameType.SURVIVAL, STANDING);
        UUID resterId = rester.getUUID();
        UUID standerId = stander.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Legs.set(resterId, false);
            Legs.set(standerId, false);
            SleepGameTests.wakeAndLeave(rester);
            TestPlayers.leave(stander);
        });
        boolean waiting = false;
        try {
            for (ServerPlayer player : List.of(rester, stander)) {
                Legs.set(player.getUUID(), true);
                feed(player, 18, 0.0F);
            }
            SleepGameTests.useBed(helper, rester, head);
            helper.assertTrue(rester.isSleeping(), "the player could not lie down at noon");
            // Points come at 40 and 80 ticks in bed and at 80 out of it, and the next not before 120
            helper.runAfterDelay(85, () -> {
                try {
                    assertPoints(helper, rester, 2.0, "80 ticks in bed");
                    assertPoints(helper, stander, 1.0, "80 ticks out of bed");
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

    // Vanilla's wake check runs in the sleeper's own tick, which the test drives, so staying asleep means it let them
    @GameTest(environment = BED, maxTicks = 80)
    public void bed3DaytimeNap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos head = SleepGameTests.placeBed(helper);
        TestPlayers.Recording napper = TestPlayers.joinRecording(helper, "Napper", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        Runnable cleanUp = BodyTestKit.once(() -> SleepGameTests.wakeAndLeave(napper));
        boolean waiting = false;
        try {
            helper.assertFalse(level.isDarkOutside(), "it is dark, so vanilla would let anyone sleep");
            SleepGameTests.useBed(helper, napper, head);
            helper.assertTrue(napper.isSleeping(), "the napper could not lie down at noon: " + keys(napper.messages, 0));
            helper.assertTrue(BedRest.napping(napper), "lying down at noon is not a nap");
            helper.onEachTick(() -> {
                if (!napper.isRemoved()) {
                    napper.doTick();
                }
            });
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertTrue(napper.isSleeping(), "the napper was woken up at noon");
                } finally {
                    cleanUp.run();
                }
                refusedInDaytime(helper, head);
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // The clock runs, so the test moves it to noon itself: a test only starts once its chunks are ready
    @GameTest(environment = BED_SKIP, maxTicks = 260)
    public void bed4ANapNeverSkipsTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertFalse(Settings.get().sleep.noNightSkip, "the environment left the night skip blocked");
        helper.assertTrue(level.getGameRules().get(GameRules.ADVANCE_TIME), "the clock is stopped, so nothing could move it");
        BlockPos head = SleepGameTests.placeBed(helper);
        TestPlayers.Recording napper = TestPlayers.joinRecording(helper, "Dozer", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        Runnable cleanUp = BodyTestKit.once(() -> SleepGameTests.wakeAndLeave(napper));
        boolean waiting = false;
        try {
            Holder<WorldClock> clock = level.dimensionType().defaultClock().orElseThrow();
            level.getServer().clockManager().moveToTimeMarker(clock, ClockTimeMarkers.NOON);
            level.updateSkyBrightness();
            helper.assertFalse(level.isDarkOutside(), "it is not day after moving the clock to noon");
            SleepGameTests.useBed(helper, napper, head);
            helper.assertTrue(napper.isSleeping(), "the napper could not lie down at noon: " + keys(napper.messages, 0));
            long asleepAt = SleepGameTests.clockTicks(helper);
            helper.onEachTick(() -> {
                if (!napper.isRemoved()) {
                    napper.doTick();
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    long moved = SleepGameTests.clockTicks(helper) - asleepAt;
                    helper.assertTrue(Math.abs(moved - 200) <= 5, "the clock moved " + moved + " ticks in 200, not about 200");
                    helper.assertTrue(napper.isSleeping(), "the napper was woken up");
                    helper.assertTrue(napper.isSleepingLongEnough(), "the napper has not slept long enough for vanilla to skip");
                    // Vanilla's count has the napper in bed, so only leaving the nap out of the deep count held the clock
                    SleepStatus status = new SleepStatus();
                    status.update(level.players());
                    int percentage = level.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
                    helper.assertTrue(status.areEnoughSleeping(percentage), "someone else in the overworld is awake, so nothing was tested");
                    helper.assertFalse(status.areEnoughDeepSleeping(percentage, level.players()), "the nap counts towards the night skip");
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

    // A monster close by still keeps the player up, and with allowDaytime off vanilla's own rule decides
    private static void refusedInDaytime(GameTestHelper helper, BlockPos head) {
        TestPlayers.Recording wary = TestPlayers.joinRecording(helper, "Wary", GameType.SURVIVAL, SleepGameTests.NEXT_TO_BED);
        HardcoreConfig.BedRestSection bedRest = Settings.get().hardcore.bedRest;
        boolean before = bedRest.allowDaytime;
        Zombie zombie = null;
        try {
            zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, ZOMBIE_AT);
            int from = wary.messages.size();
            SleepGameTests.useBed(helper, wary, head);
            helper.assertFalse(wary.isSleeping(), "the player lay down with a zombie 4 blocks away");
            helper.assertTrue(keys(wary.messages, from).contains("block.minecraft.bed.not_safe"),
                    "vanilla said " + keys(wary.messages, from) + " with a zombie close by");
            zombie.discard();
            zombie = null;

            // Off for this tick only
            bedRest.allowDaytime = false;
            from = wary.messages.size();
            SleepGameTests.useBed(helper, wary, head);
            helper.assertFalse(wary.isSleeping(), "the player lay down at noon with allowDaytime off");
            helper.assertTrue(keys(wary.messages, from).contains("block.minecraft.bed.no_sleep"),
                    "vanilla said " + keys(wary.messages, from) + " at noon with allowDaytime off");
        } finally {
            bedRest.allowDaytime = before;
            if (zombie != null) {
                zombie.discard();
            }
            SleepGameTests.wakeAndLeave(wary);
        }
    }

    private static void feed(ServerPlayer player, int food, float saturation) {
        FoodData data = player.getFoodData();
        data.setFoodLevel(food);
        data.setSaturation(saturation);
        ((FoodDataAccessor) data).yoltSetExhaustion(0.0F);
    }

    private static void assertHealth(GameTestHelper helper, ServerPlayer player, float expected, String when) {
        helper.assertTrue(Math.abs(player.getHealth() - expected) < CLOSE, "health " + player.getHealth() + " after " + when + ", not " + expected);
    }

    private static void assertPoints(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double points = HardcoreStates.read(player.getUUID()).legHealPoints;
        helper.assertTrue(HardcoreStates.read(player.getUUID()).legsBroken, "the legs healed after " + when);
        helper.assertTrue(Math.abs(points - expected) < CLOSE, points + " heal points after " + when + ", not " + expected);
    }

    // The translation keys of the lines sent from message number "from" on
    private static List<String> keys(List<Component> messages, int from) {
        return messages.subList(from, messages.size()).stream()
                .map(line -> line.getContents() instanceof TranslatableContents text ? text.getKey() : line.getString())
                .toList();
    }
}
