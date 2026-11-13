package bobbynooby.dev.hardcore;

/** How the slowdowns add up: one share of speed taken, capped and moved in whole steps so the speed doesn't flicker. */
public final class SlowBudget {
    // Keeps a value that sits on a half step, like 0.025 of 0.05, from rounding down through float error
    private static final double EPSILON = 1e-9;

    private SlowBudget() {}

    /** The parts' sum, capped at cap and rounded to the nearest step, half up. A part below 0 counts as 0. */
    public static double total(double cap, double step, double... parts) {
        double sum = 0.0;
        for (double part : parts) {
            // Also skips NaN, so one broken part can't take the speed with it
            if (part > 0.0) {
                sum += part;
            }
        }
        double limit = Math.max(cap, 0.0);
        double capped = Math.min(sum, limit);
        if (!(step > 0.0)) {
            return capped;
        }
        double stepped = Math.floor(capped / step + 0.5 + EPSILON) * step;
        // A cap that isn't a whole number of steps could round past itself
        return Math.min(stepped, limit);
    }
}
