package bobbynooby.dev.mending;

import java.math.BigDecimal;

/**
 * What the Mending nerf is set to: each XP point a player picks up repairs durabilityPerXp points of their gear instead
 * of vanilla's 2. 0 repairs nothing and sends all XP to the bar.
 */
public record MendingSettings(boolean enabled, double durabilityPerXp) {
    public static final double VANILLA_RATE = 2.0;
    public static final MendingSettings DEFAULTS = new MendingSettings(true, 0.4);

    // 0 or more. NaN fails the test, so a rate that isn't a number is refused.
    public static boolean isRate(double rate) {
        return rate >= 0;
    }

    /** A rate as /status and the command show it: 0.4 as it is, and 2 rather than 2.0. */
    public static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** The line /yolt-mending shows, and /yolt-mending reload after it reads the file. */
    public String describe() {
        if (!enabled) {
            return "The Mending nerf is off";
        }
        return "Mending: " + number(durabilityPerXp) + " durability per XP point (vanilla " + number(VANILLA_RATE) + ")";
    }
}
