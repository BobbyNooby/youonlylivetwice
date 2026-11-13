package bobbynooby.dev.hardcore;

/** The chill meter's numbers: how fast it moves, and what it shows and costs. Chill runs from 0 to 1. */
public final class ColdMaths {
    // Vanilla's frozen ticks for a full freeze: the frost overlay is full and the hearts turn blue
    static final int FULLY_FROZEN_TICKS = 140;

    private ColdMaths() {}

    /**
     * How much chill a second adds, or takes away when below 0. Anything that warms, or nothing to gain, warms at
     * warmPerSecond; otherwise the gain is faster when wet and faster still in water, and each leather piece cuts it.
     */
    public static double perSecond(boolean coldHere, boolean sheltered, boolean heldHeat, boolean nearHeat, boolean wet,
                                   boolean inWater, int leatherPieces, double chillPerSecond, double warmPerSecond,
                                   double wetMultiplier, double waterMultiplier, double leatherPerPiece) {
        double gain = 0.0;
        if (coldHere && !sheltered && !heldHeat && !nearHeat) {
            // Being in water soaks you more than rain does, so it wins over wet
            double speed = inWater ? waterMultiplier : wet ? wetMultiplier : 1.0;
            gain = chillPerSecond * speed * Math.max(0.0, 1.0 - leatherPerPiece * leatherPieces);
        }
        return gain > 0.0 ? gain : -warmPerSecond;
    }

    /** The chill a second later, kept between 0 and 1. */
    public static double next(double chill, double perSecond) {
        return clamp(chill + perSecond);
    }

    /** The vanilla frozen ticks that show this chill: none at 0, a full freeze at 1. */
    public static int frozenTicks(double chill) {
        return (int) Math.round(clamp(chill) * FULLY_FROZEN_TICKS);
    }

    /** The share of speed this chill takes. */
    public static double slow(double chill, double maxSlow) {
        return chill * maxSlow;
    }

    /** The chill as the status shows it: never 0% while there is some, nor 100% before it is full. */
    public static long percent(double chill) {
        if (chill >= 1.0) {
            return 100;
        }
        return Math.max(1, Math.min(99, Math.round(chill * 100.0)));
    }

    /** The word for a shown percent, so the two always agree. */
    public static String word(long percent) {
        if (percent >= 100) {
            return "frozen";
        }
        return percent < 50 ? "chilly" : "cold";
    }

    // NaN goes to 0, so a broken value can't stick
    private static double clamp(double chill) {
        return chill > 0.0 ? Math.min(chill, 1.0) : 0.0;
    }
}
