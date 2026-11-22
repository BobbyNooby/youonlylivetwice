package bobbynooby.dev.hardcore;

import net.minecraft.world.BossEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardcoreBloodMoonTest {
    private static final long SEED = 42L;
    private static final long NONE_YET = -1L;

    // A restart rolls the same night the same way, and a neighbouring day or another world rolls it afresh
    @Test
    void blood1SameSeedAndDaySameAnswer() {
        assertEquals(BloodMoonRoll.value(SEED, 100), BloodMoonRoll.value(SEED, 100));
        assertNotEquals(BloodMoonRoll.value(SEED, 100), BloodMoonRoll.value(SEED, 101));
        assertNotEquals(BloodMoonRoll.value(SEED, 100), BloodMoonRoll.value(43L, 100));
    }

    @Test
    void blood1ChanceGraceAndBreak() {
        assertFalse(BloodMoonRoll.rolls(SEED, 7, NONE_YET, 1.0, 5, 8, false), "day 7, inside the grace days");
        assertTrue(BloodMoonRoll.rolls(SEED, 8, NONE_YET, 1.0, 5, 8, false), "day 8, the first after them");
        assertFalse(BloodMoonRoll.rolls(SEED, 15, 10, 1.0, 5, 8, false), "day 15, five nights after day 10");
        assertTrue(BloodMoonRoll.rolls(SEED, 16, 10, 1.0, 5, 8, false), "day 16, six nights after day 10");
        for (long day = 0; day < 1000; day++) {
            assertFalse(BloodMoonRoll.rolls(SEED, day, NONE_YET, 0.0, 5, 8, false), "day " + day + " at chance 0");
        }
    }

    // 26.3's moon timeline puts the full moon on every eighth day, starting with day 0
    @Test
    void blood1FullMoonsOnly() {
        assertTrue(BloodMoonRoll.rolls(SEED, 16, NONE_YET, 1.0, 5, 8, true), "day 16, a full moon");
        assertFalse(BloodMoonRoll.rolls(SEED, 17, NONE_YET, 1.0, 5, 8, true), "day 17, the night after one");
    }

    // A night never follows a blood moon on the same day, even with no break asked for. The full-moon check counts back
    // through day 0 the same way (day -8 is a full moon, day -1 isn't), and grace days of 0 let day 0 roll.
    @Test
    void blood1SameDayNeverTwiceAndNegativeDays() {
        assertFalse(BloodMoonRoll.rolls(SEED, 10, 10, 1.0, 0, 0, false), "day 10 again after a blood moon on day 10");
        assertTrue(BloodMoonRoll.rolls(SEED, 11, 10, 1.0, 0, 0, false), "day 11 after day 10, with no break asked for");
        assertTrue(BloodMoonRoll.rolls(SEED, -8, NONE_YET, 1.0, 0, Integer.MIN_VALUE, true), "day -8, a full moon");
        assertFalse(BloodMoonRoll.rolls(SEED, -1, NONE_YET, 1.0, 0, Integer.MIN_VALUE, true), "day -1, the night before day 0");
        assertTrue(BloodMoonRoll.rolls(SEED, 0, NONE_YET, 1.0, 5, 0, false), "day 0 with no grace days");
    }

    @Test
    void blood1ChanceIsRoughlyTheShare() {
        int rolled = 0;
        for (long day = 0; day < 10000; day++) {
            if (BloodMoonRoll.rolls(SEED, day, NONE_YET, 0.1, 0, 0, false)) {
                rolled++;
            }
        }
        assertTrue(rolled >= 800 && rolled <= 1200, rolled + " of 10000 nights rolled");
    }

    // The config's colours are vanilla's, one for one, so any of them makes a bar, and red is the one it always had
    @Test
    void barColoursAreVanillasOwn() {
        assertEquals(BossEvent.BossBarColor.values().length, HardcoreConfig.BarColour.values().length);
        for (HardcoreConfig.BarColour colour : HardcoreConfig.BarColour.values()) {
            assertEquals(colour.name(), BloodMoon.barColour(colour).name());
        }
        assertEquals(BossEvent.BossBarColor.RED, BloodMoon.barColour(new HardcoreConfig.BloodMoonSection().barColour));
    }
}
