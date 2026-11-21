package bobbynooby.dev.elytra;

import java.math.BigDecimal;

/**
 * What the elytra nerf is set to: gravity while gliding is glideGravityMultiplier times vanilla's, and a rocket stops
 * pushing a glider faster than rocketCutoffSpeed, in horizontal metres per second. A cut-off of 0 never cuts.
 */
public record ElytraSettings(boolean enabled, double glideGravityMultiplier, double rocketCutoffSpeed) {
    public static final ElytraSettings DEFAULTS = new ElytraSettings(true, 0.6, 20.0);

    // 0 or more. NaN fails the test, so a multiplier that isn't a number is refused.
    public static boolean isMultiplier(double value) {
        return value >= 0;
    }

    // 0 or more. NaN fails the test, so a speed that isn't a number is refused.
    public static boolean isSpeed(double value) {
        return value >= 0;
    }

    /** The line /yolt-elytra shows, and /yolt-elytra reload after it reads the file. */
    public String describe() {
        if (!enabled) {
            return "The elytra nerf is off";
        }
        return "Elytra: " + summary();
    }

    // The describe line without its "Elytra: " start, which the start-up line says after "on" or "off"
    String summary() {
        String cutoff = rocketCutoffSpeed > 0 ? "rocket cut-off " + number(rocketCutoffSpeed) + " m/s" : "no rocket cut-off";
        return "glide gravity " + number(glideGravityMultiplier) + ", " + cutoff;
    }

    // A number as the command shows it: 0.6 as it is, and 20 rather than 20.0
    static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
