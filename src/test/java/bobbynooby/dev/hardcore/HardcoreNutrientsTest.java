package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardcoreNutrientsTest {
    private static final double GAIN = 0.03;
    private static final double CLOSE = 1e-9;

    private static HardcoreState at(double level) {
        HardcoreState s = new HardcoreState();
        NutrientMaths.start(s, level);
        return s;
    }

    @Test
    void nut1GainIsSplitByShare() {
        HardcoreState s = at(0.6);
        NutrientMaths.eat(s, 8, Map.of("protein", 1.0), GAIN);
        assertEquals(0.84, s.protein, CLOSE);
        assertEquals(0.6, s.vitamins, CLOSE);
        assertEquals(0.6, s.sugar, CLOSE);

        s = at(0.6);
        NutrientMaths.eat(s, 4, Map.of("vitamins", 1.0), GAIN);
        assertEquals(0.72, s.vitamins, CLOSE);
        assertEquals(0.6, s.protein, CLOSE);

        // 10 nutrition, half to each of two groups
        s = at(0.6);
        NutrientMaths.eat(s, 10, Map.of("protein", 0.5, "vitamins", 0.5), GAIN);
        assertEquals(0.75, s.protein, CLOSE);
        assertEquals(0.75, s.vitamins, CLOSE);
        assertEquals(0.6, s.sugar, CLOSE);

        // 3 nutrition at three quarters
        s = at(0.6);
        NutrientMaths.eat(s, 3, Map.of("protein", 0.75), GAIN);
        assertEquals(0.6675, s.protein, CLOSE);
    }

    // Only what fits below a full bar of 20 counts
    @Test
    void nut1OnlyThePointsTheBarTakesCount() {
        assertEquals(8, NutrientMaths.pointsTaken(8, 0));
        assertEquals(8, NutrientMaths.pointsTaken(8, 12));
        assertEquals(4, NutrientMaths.pointsTaken(8, 16));
        assertEquals(1, NutrientMaths.pointsTaken(8, 19));
        assertEquals(0, NutrientMaths.pointsTaken(8, 20));
        assertEquals(0, NutrientMaths.pointsTaken(0, 10));

        // Cooked beef at 19: one point's worth of its protein and vitamins, not eight
        Map<String, Double> cookedBeef = new HardcoreConfig.NutrientsSection().foods.get("minecraft:cooked_beef");
        HardcoreState s = at(0.6);
        NutrientMaths.eat(s, NutrientMaths.pointsTaken(8, 19), cookedBeef, GAIN);
        assertEquals(0.63, s.protein, CLOSE);
        assertEquals(0.6075, s.vitamins, CLOSE);
        assertEquals(0.6, s.sugar, CLOSE);
        s = at(0.6);
        NutrientMaths.eat(s, NutrientMaths.pointsTaken(8, 20), cookedBeef, GAIN);
        assertEquals(0.6, s.protein, CLOSE);
        assertEquals(0.6, s.vitamins, CLOSE);
    }

    @Test
    void nut1GainStopsAtFull() {
        HardcoreState s = at(0.6);
        s.protein = 0.9;
        NutrientMaths.eat(s, 8, Map.of("protein", 1.0), GAIN);
        assertEquals(1.0, s.protein, CLOSE);
    }

    @Test
    void nut1EachFoodPointDrainsAllThree() {
        HardcoreState s = at(0.6);
        NutrientMaths.burnFoodPoint(s, 0.01);
        assertEquals(0.59, s.protein, CLOSE);
        assertEquals(0.59, s.vitamins, CLOSE);
        assertEquals(0.59, s.sugar, CLOSE);

        s = at(0.005);
        NutrientMaths.burnFoodPoint(s, 0.01);
        assertEquals(0.0, s.protein);
        assertEquals(0.0, s.vitamins);
        assertEquals(0.0, s.sugar);
    }

    @Test
    void nut1StartLevelAndLow() {
        HardcoreState s = new HardcoreState();
        assertTrue(NutrientMaths.unset(s));
        NutrientMaths.start(s, 0.6);
        assertFalse(NutrientMaths.unset(s));
        assertEquals(0.6, s.protein);
        assertEquals(0.6, s.vitamins);
        assertEquals(0.6, s.sugar);
        assertTrue(NutrientMaths.low(0.2499, 0.25));
        assertFalse(NutrientMaths.low(0.25, 0.25));
    }

    // A food of 8 nutrition that is all protein, each one burned off before the next
    @Test
    void nut1OneGroupDietGoesLow() {
        HardcoreState s = at(0.6);
        for (int steak = 0; steak < 5; steak++) {
            NutrientMaths.eat(s, 8, Map.of("protein", 1.0), GAIN);
            // From the second steak on, protein is full again after each one
            if (steak > 0) {
                assertEquals(1.0, s.protein, CLOSE);
            }
            for (int point = 0; point < 8; point++) {
                NutrientMaths.burnFoodPoint(s, 0.01);
            }
        }
        assertEquals(0.20, s.vitamins, CLOSE);
        assertEquals(0.20, s.sugar, CLOSE);
        assertTrue(NutrientMaths.low(s.vitamins, 0.25));
        assertTrue(NutrientMaths.low(s.sugar, 0.25));
        // The last steak's 8 points come off the full level
        assertEquals(0.92, s.protein, CLOSE);
        assertFalse(NutrientMaths.low(s.protein, 0.25));
    }

    // The balance sheet's headline diets on the default table: each round eats the foods in turn from an empty bar,
    // then burns every point off again, as a player who keeps the bar topped up does. Nutrition is vanilla's.
    @Test
    void nut1BalanceSheetDiets() {
        // Steak and bread: vitamins run low, the rest hold
        assertDiet(List.of("cooked_beef", "bread"), List.of(8, 5), false, true, false);
        // Steak, bread and carrots: nothing runs low
        assertDiet(List.of("cooked_beef", "bread", "carrot"), List.of(8, 5, 3), false, false, false);
        // Rabbit stew on its own: nothing runs low
        assertDiet(List.of("rabbit_stew"), List.of(10), false, false, false);
    }

    private static void assertDiet(List<String> foods, List<Integer> nutrition, boolean proteinLow, boolean vitaminsLow,
                                   boolean sugarLow) {
        HardcoreConfig.NutrientsSection cfg = new HardcoreConfig.NutrientsSection();
        HardcoreState s = at(cfg.startLevel);
        for (int round = 0; round < 100; round++) {
            int bar = 0;
            for (int i = 0; i < foods.size(); i++) {
                int taken = NutrientMaths.pointsTaken(nutrition.get(i), bar);
                NutrientMaths.eat(s, taken, cfg.foods.get("minecraft:" + foods.get(i)), cfg.gainPerPoint);
                bar += taken;
            }
            for (; bar > 0; bar--) {
                NutrientMaths.burnFoodPoint(s, cfg.decayPerPoint);
            }
        }
        String diet = String.join(" and ", foods);
        assertEquals(proteinLow, NutrientMaths.low(s.protein, cfg.deficientBelow), diet + ": protein " + s.protein);
        assertEquals(vitaminsLow, NutrientMaths.low(s.vitamins, cfg.deficientBelow), diet + ": vitamins " + s.vitamins);
        assertEquals(sugarLow, NutrientMaths.low(s.sugar, cfg.deficientBelow), diet + ": sugar " + s.sugar);
    }
}
