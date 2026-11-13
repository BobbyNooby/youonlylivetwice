package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

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

        // Rabbit stew: 10 nutrition, half each
        s = at(0.6);
        NutrientMaths.eat(s, 10, Map.of("protein", 0.5, "vitamins", 0.5), GAIN);
        assertEquals(0.75, s.protein, CLOSE);
        assertEquals(0.75, s.vitamins, CLOSE);
        assertEquals(0.6, s.sugar, CLOSE);

        // Raw beef: 3 nutrition at three quarters
        s = at(0.6);
        NutrientMaths.eat(s, 3, Map.of("protein", 0.75), GAIN);
        assertEquals(0.6675, s.protein, CLOSE);
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

    // A steak is 8 nutrition and burns 8 food points
    @Test
    void nut1SteakOnlyDietGoesLow() {
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
}
