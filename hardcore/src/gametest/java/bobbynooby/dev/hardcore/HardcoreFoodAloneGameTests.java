package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.List;

/**
 * Nutrients and the hunger tax with no YOLT on the server. Each test swaps its settings in and puts them back within
 * its one tick, so no other test sees them, and runs vanilla's food tick itself, since a test player gets none.
 */
public class HardcoreFoodAloneGameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final double CLOSE = 1e-9;
    private static final float FLOAT_CLOSE = 1e-5F;

    // Vanilla takes 4.0 of exhaustion off at a time: the first burns the one point of saturation, which drains nothing,
    // the second a food point, which drains each group by 0.01. A cooked beef then puts all its 8 points on the bar:
    // 8 × 0.03 to protein and a quarter of that to vitamins.
    @GameTest
    public void nutrientsFallAsFoodBurnsAndRiseWithFood(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.nutrients.enabled = true));
        ServerPlayer player = null;
        try {
            helper.assertFalse(helper.getLevel().getDifficulty() == Difficulty.PEACEFUL,
                    "the test world is on Peaceful, where the food bar never drops");
            player = TestPlayers.join(helper, "NutBurner", GameType.SURVIVAL, AT);
            levels(player, 0.6);
            FoodData food = player.getFoodData();
            food.setFoodLevel(10);
            food.setSaturation(1.0F);
            helper.assertValueEqual(exhaustion(player), 0.0F, "a new player's exhaustion");
            player.causeFoodExhaustion(4.5F);
            food.tick(player);
            helper.assertValueEqual(food.getFoodLevel(), 10, "food after burning saturation");
            assertLevels(helper, player, 0.6, 0.6, 0.6, "after saturation burned");

            player.causeFoodExhaustion(4.0F);
            food.tick(player);
            helper.assertValueEqual(food.getFoodLevel(), 9, "food after burning with no saturation");
            assertLevels(helper, player, 0.59, 0.59, 0.59, "after a food point burned");

            new ItemStack(Items.COOKED_BEEF).finishUsingItem(helper.getLevel(), player);
            helper.assertValueEqual(food.getFoodLevel(), 17, "food after a cooked beef");
            assertLevels(helper, player, 0.83, 0.65, 0.59, "after a cooked beef");
            List<String> status = TestPlayers.status(helper, player);
            helper.assertTrue(status.contains("Nutrients protein 83% · vitamins 65% · sugar 59%"), "/status: " + status);
        } finally {
            try {
                TestPlayers.leave(player);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // An action's own hunger, as vanilla charges it, at ×2 and then at vanilla's ×1
    @GameTest
    public void theHungerTaxScalesWhatActionsCost(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.hungerTax.multiplier = 2.0));
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "HungerTaxed", GameType.SURVIVAL, AT);
            float start = exhaustion(player);
            player.causeFoodExhaustion(0.1F);
            assertClose(helper, exhaustion(player) - start, 0.2F, "an action's 0.1 at ×2");
            List<String> status = TestPlayers.status(helper, player);
            helper.assertTrue(status.contains("Server rules") && status.contains("  hunger ×2"), "/status: " + status);

            YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
            start = exhaustion(player);
            player.causeFoodExhaustion(0.1F);
            assertClose(helper, exhaustion(player) - start, 0.1F, "an action's 0.1 at ×1");
            List<String> vanilla = TestPlayers.status(helper, player);
            helper.assertTrue(vanilla.stream().noneMatch(line -> line.contains("hunger")), "/status at ×1: " + vanilla);
        } finally {
            try {
                TestPlayers.leave(player);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Hunger spent but not yet taken off the food bar: vanilla keeps it to itself, but writes it in the player's save
    private static float exhaustion(ServerPlayer player) {
        TagValueOutput save = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        player.getFoodData().addAdditionalSaveData(save);
        return save.buildResult().getFloatOr("foodExhaustionLevel", Float.NaN);
    }

    private static void levels(ServerPlayer player, double level) {
        HardcoreStates.update(player.getUUID(), s -> {
            s.protein = level;
            s.vitamins = level;
            s.sugar = level;
        });
    }

    private static void assertLevels(GameTestHelper helper, ServerPlayer player, double protein, double vitamins, double sugar,
                                     String when) {
        HardcoreState state = HardcoreStates.read(player.getUUID());
        helper.assertTrue(Math.abs(state.protein - protein) < CLOSE && Math.abs(state.vitamins - vitamins) < CLOSE
                        && Math.abs(state.sugar - sugar) < CLOSE,
                "protein, vitamins and sugar " + state.protein + ", " + state.vitamins + ", " + state.sugar + " " + when
                        + ", not " + protein + ", " + vitamins + ", " + sugar);
    }

    private static void assertClose(GameTestHelper helper, float actual, float expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < FLOAT_CLOSE, what + " cost " + actual + ", not " + expected);
    }
}
