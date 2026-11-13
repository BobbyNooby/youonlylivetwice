package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreMendingTest {
    private static final double RATE = 0.4;
    private static final double CLOSE = 1e-9;

    @Test
    void mend1SmallOrbsAddUp() {
        assertResult(MendingMaths.repair(1, RATE, 100, 0.0), 0, 1, 0.4);
        assertResult(MendingMaths.repair(1, RATE, 100, 0.4), 0, 1, 0.8);
        assertResult(MendingMaths.repair(1, RATE, 100, 0.8), 1, 1, 0.2);
    }

    @Test
    void mend1ABottleRepairsAboutThree() {
        assertResult(MendingMaths.repair(7, RATE, 100, 0.0), 2, 7, 0.8);
    }

    // 3 XP close the 1 point of damage, and the other 7 are left for the bar
    @Test
    void mend1OnlyTheXpNeededIsUsed() {
        assertResult(MendingMaths.repair(10, RATE, 1, 0.0), 1, 3, 0.2);
    }

    @Test
    void mend1ZeroTurnsMendingOff() {
        assertResult(MendingMaths.repair(10, 0.0, 50, 0.3), 0, 0, 0.3);
    }

    private static void assertResult(MendingMaths.Result result, int repaired, int xpUsed, double carried) {
        assertEquals(repaired, result.repaired(), "repaired");
        assertEquals(xpUsed, result.xpUsed(), "XP used");
        assertEquals(carried, result.carried(), CLOSE, "carried");
    }
}
