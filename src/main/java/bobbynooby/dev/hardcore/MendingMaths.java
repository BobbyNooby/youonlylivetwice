package bobbynooby.dev.hardcore;

/**
 * Mending's repair at a rate that isn't a whole number of points per XP. What a pickup can't repair yet is carried to
 * the next one, and once the item is whole, only the XP it needed is used, so no XP is ever lost.
 */
public final class MendingMaths {
    // Sums of 0.4 drift a hair below the whole number they should reach
    private static final double EPSILON = 1e-9;

    public record Result(int repaired, int xpUsed, double carried) {}

    private MendingMaths() {}

    /** Repairs up to {@code damage} points with {@code xp} points at {@code rate} per point, on top of what was carried. */
    public static Result repair(int xp, double rate, int damage, double carried) {
        if (!(rate > 0)) {
            return new Result(0, 0, carried);
        }
        double total = carried + xp * rate;
        int whole = (int) Math.floor(total + EPSILON);
        if (whole >= damage) {
            double needed = Math.ceil(Math.max(0.0, damage - carried) / rate - EPSILON);
            int xpUsed = (int) Math.min(xp, needed);
            return new Result(damage, xpUsed, Math.max(0.0, carried + xpUsed * rate - damage));
        }
        return new Result(whole, xp, Math.max(0.0, total - whole));
    }
}
