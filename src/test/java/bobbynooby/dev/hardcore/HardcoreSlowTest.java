package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreSlowTest {
    private static final double CLOSE = 1e-9;

    @Test
    void slow1AddsUpAndCaps() {
        assertEquals(0.5, SlowBudget.total(0.5, 0.05, 0.2, 0.2, 0.12), CLOSE);
    }

    @Test
    void slow1MovesInSteps() {
        assertEquals(0.20, SlowBudget.total(0.5, 0.05, 0.19), CLOSE);
        assertEquals(0.0, SlowBudget.total(0.5, 0.05, 0.02), CLOSE);
        assertEquals(0.05, SlowBudget.total(0.5, 0.05, 0.03), CLOSE);
    }

    @Test
    void slow1NothingGivesNothing() {
        assertEquals(0.0, SlowBudget.total(0.5, 0.05), CLOSE);
        assertEquals(0.0, SlowBudget.total(0.5, 0.05, -0.3), CLOSE);
        // A negative part takes nothing off the others
        assertEquals(0.1, SlowBudget.total(0.5, 0.05, -0.3, 0.1), CLOSE);
    }
}
