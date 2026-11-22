package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardcoreLimpTest {
    @Test
    void limp1StartsBelowTwoHeartsStopsAtThree() {
        assertFalse(Limp.next(false, 4.0, 2, 3));
        assertTrue(Limp.next(false, 3.9, 2, 3));
        assertTrue(Limp.next(true, 5.9, 2, 3));
        assertFalse(Limp.next(true, 6.0, 2, 3));
    }

    @Test
    void limp1NoFlickerInBetween() {
        for (double health : new double[] {4.0, 4.5, 5.0, 5.5}) {
            assertTrue(Limp.next(true, health, 2, 3), "stopped limping at " + health);
            assertFalse(Limp.next(false, health, 2, 3), "started limping at " + health);
        }
    }
}
