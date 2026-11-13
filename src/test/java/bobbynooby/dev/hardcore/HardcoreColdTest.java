package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The defaults: 0.01 a second exposed, 0.03 back, twice as fast wet, four times in water, 25% off per leather piece
class HardcoreColdTest {
    private static final double CLOSE = 1e-12;

    // Cold here, nothing warming, and the given weather, water and leather
    private static double exposed(boolean wet, boolean inWater, int leather) {
        return ColdMaths.perSecond(true, false, false, false, wet, inWater, leather, 0.01, 0.03, 2, 4, 0.25);
    }

    @Test
    void cold1ExposureWetAndWater() {
        assertEquals(0.01, exposed(false, false, 0), CLOSE);
        assertEquals(0.02, exposed(true, false, 0), CLOSE);
        assertEquals(0.04, exposed(false, true, 0), CLOSE);
        // Rained on while in water is still the water's rate
        assertEquals(0.04, exposed(true, true, 0), CLOSE);
    }

    @Test
    void cold1LeatherPerPiece() {
        assertEquals(0.0075, exposed(false, false, 1), CLOSE);
        assertEquals(0.005, exposed(false, false, 2), CLOSE);
        // A full set leaves nothing to gain, so it warms
        assertEquals(-0.03, exposed(false, false, 4), CLOSE);
    }

    @Test
    void cold1HeatShelterAndWarmPlaces() {
        assertEquals(-0.03, ColdMaths.perSecond(true, false, true, false, false, false, 0, 0.01, 0.03, 2, 4, 0.25), CLOSE);
        assertEquals(-0.03, ColdMaths.perSecond(true, false, false, true, false, false, 0, 0.01, 0.03, 2, 4, 0.25), CLOSE);
        assertEquals(-0.03, ColdMaths.perSecond(true, true, false, false, false, false, 0, 0.01, 0.03, 2, 4, 0.25), CLOSE);
        assertEquals(-0.03, ColdMaths.perSecond(false, false, false, false, false, false, 0, 0.01, 0.03, 2, 4, 0.25), CLOSE);
        // A held torch keeps you warm in the water too
        assertEquals(-0.03, ColdMaths.perSecond(true, false, true, false, true, true, 0, 0.01, 0.03, 2, 4, 0.25), CLOSE);
    }

    @Test
    void cold1ChillMovesAndClamps() {
        assertEquals(1.0, ColdMaths.next(0.995, 0.01));
        assertEquals(0.0, ColdMaths.next(0.02, -0.03));
        assertEquals(0.53, ColdMaths.next(0.5, 0.03), CLOSE);
    }

    @Test
    void cold1FrostAndSlow() {
        assertEquals(70, ColdMaths.frozenTicks(0.5));
        assertEquals(140, ColdMaths.frozenTicks(1.0));
        assertEquals(0, ColdMaths.frozenTicks(0.0));
        assertEquals(0.1, ColdMaths.slow(0.5, 0.2), CLOSE);
        assertEquals(0.2, ColdMaths.slow(1.0, 0.2), CLOSE);
    }

    @Test
    void cold1StatusWordFollowsThePercentShown() {
        assertEquals(1, ColdMaths.percent(0.001));
        assertEquals(49, ColdMaths.percent(0.4949));
        assertEquals("chilly", ColdMaths.word(ColdMaths.percent(0.4949)));
        // Shown as 50%, so it reads cold, not chilly
        assertEquals(50, ColdMaths.percent(0.4951));
        assertEquals("cold", ColdMaths.word(ColdMaths.percent(0.4951)));
        assertEquals(99, ColdMaths.percent(0.999));
        assertEquals("cold", ColdMaths.word(ColdMaths.percent(0.999)));
        assertEquals(100, ColdMaths.percent(1.0));
        assertEquals("frozen", ColdMaths.word(ColdMaths.percent(1.0)));
    }
}
