package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
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
}
