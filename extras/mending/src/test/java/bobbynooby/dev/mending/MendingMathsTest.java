package bobbynooby.dev.mending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MendingMathsTest {
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

    // Five times 0.4 sums to a hair off 2, which must still repair 2 with nothing carried; nothing to repair uses no XP;
    // a rate that isn't a number repairs nothing and keeps what was carried
    @Test
    void mend1FiveXpRepairsExactlyTwo() {
        assertResult(MendingMaths.repair(5, RATE, 100, 0.0), 2, 5, 0.0);
        assertResult(MendingMaths.repair(3, RATE, 0, 0.0), 0, 0, 0.0);
        assertResult(MendingMaths.repair(10, Double.NaN, 5, 0.2), 0, 0, 0.2);
    }

    // Above 1 a point, what is kept can be more than a whole point: 1 XP at 2.5 on 1 point of damage keeps 1.5. The next
    // item's point then comes out of that with no XP spent, and an item with more damage spends the XP on top.
    @Test
    void mend1ARateAboveOneCanKeepMoreThanAPoint() {
        assertResult(MendingMaths.repair(1, 2.5, 1, 0.0), 1, 1, 1.5);
        assertResult(MendingMaths.repair(1, 2.5, 1, 1.5), 1, 0, 0.5);
        assertResult(MendingMaths.repair(1, 2.5, 100, 1.5), 4, 1, 0.0);
    }

    private static void assertResult(MendingMaths.Result result, int repaired, int xpUsed, double carried) {
        assertEquals(repaired, result.repaired(), "repaired");
        assertEquals(xpUsed, result.xpUsed(), "XP used");
        assertEquals(carried, result.carried(), CLOSE, "carried");
    }
}
