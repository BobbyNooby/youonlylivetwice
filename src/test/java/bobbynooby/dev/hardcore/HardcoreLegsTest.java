package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreLegsTest {
    private static final double CLOSE = 1e-9;

    @Test
    void legs5BreakPointScalesWithLoad() {
        assertEquals(6.0, LegHealing.breakAt(6, 4, false, 1.0), CLOSE);
        assertEquals(6.0, LegHealing.breakAt(6, 4, true, 0.0), CLOSE);
        assertEquals(5.0, LegHealing.breakAt(6, 4, true, 0.5), CLOSE);
        assertEquals(4.0, LegHealing.breakAt(6, 4, true, 1.0), CLOSE);
    }

    @Test
    void legs3HealPace() {
        HardcoreConfig.LegsSection cfg = new HardcoreConfig.LegsSection();
        assertEquals(40, LegHealing.ticksPerPoint(20, 5.0F, true, cfg));
        assertEquals(80, LegHealing.ticksPerPoint(18, 0.0F, true, cfg));
        assertEquals(0, LegHealing.ticksPerPoint(17, 0.0F, true, cfg));
        assertEquals(1200, LegHealing.ticksPerPoint(20, 5.0F, false, cfg));
        cfg.healTicksNoRegenRule = 0;
        assertEquals(0, LegHealing.ticksPerPoint(20, 5.0F, false, cfg));
    }

    // Vanilla's pace: 50 ticks at level I, halved for each level above, never below 1. A level past 31 would wrap the
    // shift round to a slow pace, and a level below I counts as I.
    @Test
    void legs3RegenerationPacePerLevel() {
        assertEquals(50, LegHealing.regenerationTicksPerPoint(0));
        assertEquals(25, LegHealing.regenerationTicksPerPoint(1));
        assertEquals(12, LegHealing.regenerationTicksPerPoint(2));
        assertEquals(1, LegHealing.regenerationTicksPerPoint(5));
        assertEquals(1, LegHealing.regenerationTicksPerPoint(31));
        assertEquals(1, LegHealing.regenerationTicksPerPoint(32));
        assertEquals(50, LegHealing.regenerationTicksPerPoint(-1));
        assertEquals(1, LegHealing.regenerationTicksPerPoint(Integer.MAX_VALUE));
    }

    // Full food with no saturation, or saturation under 20 food, is the plain pace; a pace set below 0 is no healing;
    // the load share stops at both ends
    @Test
    void legs3PaceEdges() {
        HardcoreConfig.LegsSection cfg = new HardcoreConfig.LegsSection();
        assertEquals(80, LegHealing.ticksPerPoint(20, 0.0F, true, cfg));
        assertEquals(80, LegHealing.ticksPerPoint(19, 5.0F, true, cfg));
        cfg.healTicks = -5;
        assertEquals(0, LegHealing.ticksPerPoint(19, 5.0F, true, cfg));
        assertEquals(4.0, LegHealing.breakAt(6, 4, true, 1.5), CLOSE);
        assertEquals(6.0, LegHealing.breakAt(6, 4, true, -0.5), CLOSE);
    }
}
