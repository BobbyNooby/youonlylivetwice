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

    // A cap of 0 is a server's way to have no slowdown at all, whatever the step and however much the parts add up to
    @Test
    void slow1CapZeroTakesNothing() {
        assertEquals(0.0, SlowBudget.total(0.0, 0.05, 0.2, 0.2, 0.5), CLOSE);
        assertEquals(0.0, SlowBudget.total(0.0, 1.0, 0.9), CLOSE);
    }

    // Half a step goes up. 0.075 is 1.4999999999999998 steps of 0.05 in doubles, and 0.15 the same of 0.1, which plain
    // rounding would take down a step.
    @Test
    void slow1HalfAStepRoundsUp() {
        assertEquals(0.05, SlowBudget.total(0.5, 0.05, 0.025), CLOSE);
        assertEquals(0.1, SlowBudget.total(0.5, 0.05, 0.075), CLOSE);
        assertEquals(0.1, SlowBudget.total(0.5, 0.05, 0.05, 0.025), CLOSE);
        assertEquals(0.2, SlowBudget.total(0.5, 0.1, 0.15), CLOSE);
        // Just under half a step still goes down
        assertEquals(0.05, SlowBudget.total(0.5, 0.05, 0.0749), CLOSE);
    }

    // A cap that isn't a whole number of steps is the most there is, even where rounding would pass it; a cap below 0
    // means no slowdown at all
    @Test
    void slow1ACapOffTheStepGridIsNeverPassed() {
        assertEquals(0.48, SlowBudget.total(0.48, 0.05, 0.6), CLOSE);
        assertEquals(0.45, SlowBudget.total(0.47, 0.05, 0.6), CLOSE);
        assertEquals(0.0, SlowBudget.total(-0.1, 0.05, 0.3), CLOSE);
    }

    // A broken part counts as nothing; a step of 0 or NaN leaves the total as it adds up, capped
    @Test
    void slow1NaNPartAndNoStep() {
        assertEquals(0.1, SlowBudget.total(0.5, 0.05, Double.NaN, 0.1), CLOSE);
        assertEquals(0.17, SlowBudget.total(0.5, 0.0, 0.17), CLOSE);
        assertEquals(0.2, SlowBudget.total(0.5, Double.NaN, 0.2), CLOSE);
        assertEquals(0.5, SlowBudget.total(0.5, 0.0, 0.7), CLOSE);
    }
}
