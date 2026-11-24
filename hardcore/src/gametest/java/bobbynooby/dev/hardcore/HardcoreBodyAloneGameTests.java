package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

import static bobbynooby.dev.hardcore.HardcoreTestKit.NEXT_TO_BED;
import static bobbynooby.dev.hardcore.HardcoreTestKit.assertClose;
import static bobbynooby.dev.hardcore.HardcoreTestKit.assertSlow;
import static bobbynooby.dev.hardcore.HardcoreTestKit.placeBed;
import static bobbynooby.dev.hardcore.HardcoreTestKit.useBed;

/**
 * Weight, broken legs, the limp and bed rest with no YOLT on the server, each the simplest case of YOLT's own test of
 * it. The hardcore tick reads the settings at the end of every server tick, so each test holds its switch on in a batch
 * of its own. Test players get no living tick from a client, so vanilla's own regen never moves a health a test sets.
 */
public class HardcoreBodyAloneGameTests {
    private static final String WEIGHT = "yolt-hardcore-gametest:weight_alone";
    private static final String LEGS = "yolt-hardcore-gametest:legs_alone";
    private static final String LIMP = "yolt-hardcore-gametest:limp_alone";
    // Noon with the clock stopped and the sky clear, so the bed is a daytime nap
    private static final String BED = "yolt-hardcore-gametest:bed_rest_alone";
    // The same noon and midnight, each in a batch of its own, so no other test's player is in the dimension to be told
    // of a sleeper or to be one
    private static final String NAP_LINE = "yolt-hardcore-gametest:nap_line_alone";
    private static final String NIGHT_LINE = "yolt-hardcore-gametest:night_line_alone";
    // The same again, one batch for each way a sleep ends
    private static final String NAP_UP = "yolt-hardcore-gametest:nap_up_alone";
    private static final String NAP_LEAVE = "yolt-hardcore-gametest:nap_leave_alone";
    private static final String NIGHT_UP = "yolt-hardcore-gametest:night_up_alone";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final BlockPos AWAY_FROM_BED = new BlockPos(4, 1, 4);
    // Weight weighs again every 10 ticks, plus 2 for the slowdown to follow
    private static final int SETTLE = 12;

    // 12 stacks of cobblestone at 3 each: (36 - 16) / (144 - 16) × 0.5 is 0.078, which the shared slowdown's steps make
    // 0.1. Nothing is slowed until the hardcore tick weighs the load, and a weighing is kept for 10 ticks, so putting
    // it down shows within SETTLE ticks as well.
    @GameTest(environment = WEIGHT, maxTicks = 60)
    public void aLoadSlowsThePlayerAndPuttingItDownLiftsIt(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.weight.enabled = true);
        try {
            ServerPlayer player = held.join("Hauler", GameType.SURVIVAL, AT);
            held.onCleanUp(() -> player.getInventory().clearContent());
            int from = TestPlayers.mark(player);
            fill(player, Items.COBBLESTONE, 12);
            assertSlow(helper, player, 0.0, "as the load is picked up");
            helper.runAfterDelay(SETTLE, held.step(() -> {
                assertSlow(helper, player, 0.1, "at a load of 36");
                helper.assertValueEqual(TestPlayers.actionBars(player, from), List.of("Your load is slowing you down."),
                        "the hotbar");
                List<String> status = TestPlayers.status(helper, player);
                helper.assertTrue(status.contains("Load 36 / 144 · 8% slower") && status.contains("  Cobblestone ×768 (36.0)")
                        && status.contains("Total 8% slower · cap 50%"), "/status: " + status);
                player.getInventory().clearContent();
            }));
            helper.runAfterDelay(2 * SETTLE, held.last(() -> assertSlow(helper, player, 0.0, "after the load was put down")));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // A fall is the damage itself, sent into the hurt path as a landing does: 9 blocks less the 3 safe ones is 6, which
    // breaks them. Healing waits for full health and takes a point every 40 ticks on full, saturated food, so the
    // thirtieth point comes 40 ticks after the twenty-ninth is set.
    @GameTest(environment = LEGS, maxTicks = 80)
    public void aLongFallBreaksTheLegsAndTheyHeal(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.legs.enabled = true);
        try {
            ServerPlayer player = held.join("Faller", GameType.SURVIVAL, AT);
            UUID id = player.getUUID();
            held.onCleanUp(() -> Legs.set(id, false));
            helper.assertFalse(Legs.broken(player), "the legs are broken before the fall");
            int from = TestPlayers.mark(player);
            player.causeFallDamage(9.0, 1.0F, helper.getLevel().damageSources().fall());
            helper.assertTrue(player.getHealth() < player.getMaxHealth(), "the fall did no damage");
            helper.assertTrue(Legs.broken(player), "a 9-block fall left the legs whole");
            helper.assertValueEqual(TestPlayers.actionBars(player, from), List.of("Your legs broke."), "the hotbar");
            List<String> status = TestPlayers.status(helper, player);
            helper.assertTrue(status.contains("Legs broken · 20% slower · healing 0/30"), "/status: " + status);
            helper.runAfterDelay(2, held.step(() -> {
                assertSlow(helper, player, 0.2, "with broken legs");
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(5.0F);
                HardcoreStates.update(id, state -> state.legHealPoints = 29.0);
            }));
            helper.runAfterDelay(47, held.last(() -> {
                helper.assertFalse(Legs.broken(player), "the legs are still broken after the thirtieth point");
                helper.assertTrue(TestPlayers.actionBars(player, from).contains("Your legs have healed."),
                        "the hotbar: " + TestPlayers.actionBars(player, from));
                assertSlow(helper, player, 0.0, "with the legs healed");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // Under 2 hearts the player limps; 5 health is still short of the 3 hearts that end it, and 6 ends it
    @GameTest(environment = LIMP, maxTicks = 30)
    public void aLowHealthPlayerLimpsUntilHealed(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.limp.enabled = true);
        try {
            ServerPlayer player = held.join("Limper", GameType.SURVIVAL, AT);
            player.setHealth(3.0F);
            assertSlow(helper, player, 0.0, "before a tick saw the health");
            helper.runAfterDelay(2, held.step(() -> {
                assertSlow(helper, player, 0.2, "at 3 health");
                helper.assertTrue(Limp.limping(player), "not limping at 3 health");
                // The limp has no line of its own, only its share of the total
                List<String> status = TestPlayers.status(helper, player);
                helper.assertTrue(status.contains("Total 20% slower · cap 50%"), "/status: " + status);
                player.setHealth(5.0F);
            }));
            helper.runAfterDelay(4, held.step(() -> {
                assertSlow(helper, player, 0.2, "healed to 5 health");
                player.setHealth(6.0F);
            }));
            helper.runAfterDelay(6, held.last(() -> {
                assertSlow(helper, player, 0.0, "healed to 6 health");
                helper.assertFalse(Limp.limping(player), "still limping at 6 health");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // One health every 50 ticks from lying down; each check is a few ticks after a heal and well before the next. At
    // noon vanilla wouldn't let anyone lie down: bed rest lets them.
    @GameTest(environment = BED, maxTicks = 140)
    public void bedRestHealsAPlayerInBed(GameTestHelper helper) {
        Held held = new Held(helper, c -> c.bedRest.enabled = true);
        try {
            BlockPos head = placeBed(helper);
            ServerPlayer player = held.join("Rester", GameType.SURVIVAL, NEXT_TO_BED);
            held.onCleanUp(() -> {
                if (player.isSleeping()) {
                    player.stopSleepInBed(true, true);
                }
            });
            helper.assertFalse(helper.getLevel().isDarkOutside(), "it is dark at the batch's noon");
            player.setHealth(10.0F);
            useBed(helper, player, head);
            helper.assertTrue(player.isSleeping(), "the player could not lie down at noon");
            List<String> status = TestPlayers.status(helper, player);
            helper.assertTrue(status.contains("Resting healing in bed"), "/status: " + status);
            helper.runAfterDelay(55, held.step(() ->
                    assertClose(helper, player.getHealth(), 11.0, "the health after 50 ticks in bed")));
            helper.runAfterDelay(105, held.last(() ->
                    assertClose(helper, player.getHealth(), 12.0, "the health after 100 ticks in bed")));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // Vanilla tells everyone in the dimension how many are in bed as someone lies down, and again as a player arrives
    // while someone is: "Sleeping through this night" with one player, "1/2 players sleeping" with two. A nap never
    // counts towards the skip, so at noon either line would promise what can't happen.
    @GameTest(environment = NAP_LINE)
    public void aDaytimeNapSendsNoSleepLine(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.bedRest.enabled = true));
        ServerPlayer napper = null;
        ServerPlayer awake = null;
        try {
            BlockPos head = placeBed(helper);
            napper = TestPlayers.join(helper, "Napper", GameType.SURVIVAL, NEXT_TO_BED);
            helper.assertFalse(helper.getLevel().isDarkOutside(), "it is dark at the batch's noon");
            int from = TestPlayers.mark(napper);
            useBed(helper, napper, head);
            helper.assertTrue(napper.isSleeping(), "the player could not lie down at noon");
            helper.assertTrue(BedRest.napping(napper), "lying down at noon is not a nap");
            helper.assertValueEqual(TestPlayers.actionBars(napper, from), List.of(), "the napper's hotbar as they lay down");

            awake = TestPlayers.join(helper, "Awake", GameType.SURVIVAL, AWAY_FROM_BED);
            helper.assertValueEqual(TestPlayers.actionBars(napper, from), List.of(), "the napper's hotbar as another arrived");
            helper.assertValueEqual(TestPlayers.actionBars(awake, 0), List.of(), "the hotbar of a player who arrived");
        } finally {
            try {
                try {
                    wakeAndLeave(napper);
                } finally {
                    TestPlayers.leave(awake);
                }
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The control: at midnight vanilla's own rule lets the player lie down, which is no nap, so vanilla's line goes
    // out. A test player gets no tick of its own, and only that tick counts how long it has slept, so the night never
    // skips.
    @GameTest(environment = NIGHT_LINE)
    public void aNightsSleepStillSendsTheSleepLine(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.bedRest.enabled = true));
        ServerPlayer sleeper = null;
        try {
            BlockPos head = placeBed(helper);
            sleeper = TestPlayers.join(helper, "Sleeper", GameType.SURVIVAL, NEXT_TO_BED);
            helper.assertTrue(helper.getLevel().isDarkOutside(), "it isn't dark at the batch's midnight");
            int from = TestPlayers.mark(sleeper);
            useBed(helper, sleeper, head);
            helper.assertTrue(sleeper.isSleeping(), "the player could not lie down at midnight");
            helper.assertFalse(BedRest.napping(sleeper), "lying down at midnight is a nap");
            helper.assertValueEqual(TestPlayers.actionBars(sleeper, from), List.of("Sleeping through this night"),
                    "the sleeper's hotbar as they lay down");
        } finally {
            try {
                wakeAndLeave(sleeper);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // A nap ends as the napper gets up, the way the client's Leave Bed button asks. Vanilla takes them out of bed first
    // and counts after, when nobody naps any more, so the count would tell everyone "0/2 players sleeping" at noon.
    @GameTest(environment = NAP_UP)
    public void aNapperGettingUpSendsNoSleepLine(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.bedRest.enabled = true));
        ServerPlayer napper = null;
        ServerPlayer watcher = null;
        try {
            BlockPos head = placeBed(helper);
            napper = TestPlayers.join(helper, "UpNapper", GameType.SURVIVAL, NEXT_TO_BED);
            watcher = TestPlayers.join(helper, "UpWatcher", GameType.SURVIVAL, AWAY_FROM_BED);
            helper.assertFalse(helper.getLevel().isDarkOutside(), "it is dark at the batch's noon");
            int from = TestPlayers.mark(napper);
            useBed(helper, napper, head);
            helper.assertTrue(BedRest.napping(napper), "lying down at noon is not a nap");
            napper.stopSleepInBed(false, true);
            helper.assertFalse(napper.isSleeping(), "the napper is still in bed after getting up");
            helper.assertValueEqual(TestPlayers.actionBars(watcher, 0), List.of(), "the watcher's hotbar through the nap");
            helper.assertValueEqual(TestPlayers.actionBars(napper, from), List.of(), "the napper's hotbar through the nap");
        } finally {
            try {
                try {
                    wakeAndLeave(napper);
                } finally {
                    TestPlayers.leave(watcher);
                }
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // A napper who leaves the game is woken and then let go, and the count after that has nobody napping either
    @GameTest(environment = NAP_LEAVE)
    public void aNapperLeavingTheGameSendsNoSleepLine(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.bedRest.enabled = true));
        ServerPlayer napper = null;
        ServerPlayer watcher = null;
        try {
            BlockPos head = placeBed(helper);
            napper = TestPlayers.join(helper, "GoneNapper", GameType.SURVIVAL, NEXT_TO_BED);
            watcher = TestPlayers.join(helper, "GoneWatcher", GameType.SURVIVAL, AWAY_FROM_BED);
            helper.assertFalse(helper.getLevel().isDarkOutside(), "it is dark at the batch's noon");
            useBed(helper, napper, head);
            helper.assertTrue(BedRest.napping(napper), "lying down at noon is not a nap");
            ServerPlayer leaving = napper;
            napper = null;
            TestPlayers.disconnect(leaving);
            helper.assertFalse(helper.getLevel().players().contains(leaving), "the napper is still in the dimension");
            helper.assertValueEqual(TestPlayers.actionBars(watcher, 0), List.of(), "the watcher's hotbar through the nap");
        } finally {
            try {
                try {
                    wakeAndLeave(napper);
                } finally {
                    TestPlayers.leave(watcher);
                }
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The control: at midnight a sleep is no nap, so vanilla's line reaches the watcher as the sleeper lies down and
    // again as they get up
    @GameTest(environment = NIGHT_UP)
    public void aNightSleeperGettingUpStillSendsTheSleepLine(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.bedRest.enabled = true));
        ServerPlayer sleeper = null;
        ServerPlayer watcher = null;
        try {
            BlockPos head = placeBed(helper);
            sleeper = TestPlayers.join(helper, "UpSleeper", GameType.SURVIVAL, NEXT_TO_BED);
            watcher = TestPlayers.join(helper, "NightWatcher", GameType.SURVIVAL, AWAY_FROM_BED);
            helper.assertTrue(helper.getLevel().isDarkOutside(), "it isn't dark at the batch's midnight");
            useBed(helper, sleeper, head);
            helper.assertTrue(sleeper.isSleeping(), "the player could not lie down at midnight");
            helper.assertFalse(BedRest.napping(sleeper), "lying down at midnight is a nap");
            sleeper.stopSleepInBed(false, true);
            helper.assertValueEqual(TestPlayers.actionBars(watcher, 0), List.of("1/2 players sleeping", "0/2 players sleeping"),
                    "the watcher's hotbar through the night's sleep");
        } finally {
            try {
                try {
                    wakeAndLeave(sleeper);
                } finally {
                    TestPlayers.leave(watcher);
                }
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Out of bed first, so the player doesn't leave as a sleeper. Nothing for a player who never joined.
    private static void wakeAndLeave(@Nullable ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            if (player.isSleeping()) {
                player.stopSleepInBed(true, true);
            }
        } finally {
            TestPlayers.leave(player);
        }
    }

    // Full stacks into the first empty slots
    private static void fill(ServerPlayer player, Item item, int stacks) {
        for (int i = 0; i < stacks; i++) {
            player.getInventory().add(new ItemStack(item, 64));
        }
    }
}
