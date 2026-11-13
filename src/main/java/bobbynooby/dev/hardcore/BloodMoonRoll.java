package bobbynooby.dev.hardcore;

import java.util.Random;

/**
 * Whether a night is a blood moon, from the world seed, the day number and the day of the last one alone. Nothing else
 * goes in, so a restart can't roll a night again and who is online changes nothing.
 */
public final class BloodMoonRoll {
    // The 64-bit golden ratio, so days next to each other give unrelated numbers
    private static final long DAY_MIX = 0x9E3779B97F4A7C15L;
    // 26.3's moon timeline: eight phases of one day each, the full moon first
    private static final int MOON_PHASES = 8;

    private BloodMoonRoll() {}

    /** A number from 0 to 1 that is always the same for this seed and day. */
    public static double value(long seed, long day) {
        return new Random(seed ^ (day * DAY_MIX)).nextDouble();
    }

    /** True when this day's night is a blood moon. A last day below 0 means there has been none. */
    public static boolean rolls(long seed, long day, long lastBloodMoonDay, double chance, int minNightsBetween,
                                int graceDays, boolean fullMoonsOnly) {
        if (day < graceDays) {
            return false;
        }
        if (lastBloodMoonDay >= 0 && day - lastBloodMoonDay <= minNightsBetween) {
            return false;
        }
        if (fullMoonsOnly && Math.floorMod(day, MOON_PHASES) != 0) {
            return false;
        }
        return value(seed, day) < chance;
    }
}
