package bobbynooby.dev.hardcore;

import java.util.Map;

/** The nutrient sums, on a stored state and nothing else, so they can be checked without a server. */
public final class NutrientMaths {
    public static final String PROTEIN = "protein";
    public static final String VITAMINS = "vitamins";
    public static final String SUGAR = "sugar";

    // Vanilla's full food bar
    static final int FULL_BAR = 20;

    private NutrientMaths() {}

    /**
     * The food points a food actually puts on the bar, read before it is eaten: what fits below full. Food eaten at a full
     * bar counts for nothing, so gain and drain are both per point the bar really holds.
     */
    public static int pointsTaken(int nutrition, int foodLevelBefore) {
        return Math.max(0, Math.min(nutrition, FULL_BAR - foodLevelBefore));
    }

    /** A food eaten: each group it names gains nutrition × gainPerPoint × its share, up to full. */
    public static void eat(HardcoreState s, int nutrition, Map<String, Double> shares, double gainPerPoint) {
        double gain = nutrition * gainPerPoint;
        s.protein = gained(s.protein, gain, shares.get(PROTEIN));
        s.vitamins = gained(s.vitamins, gain, shares.get(VITAMINS));
        s.sugar = gained(s.sugar, gain, shares.get(SUGAR));
    }

    /** One point off the food bar: every group loses the same, down to empty. */
    public static void burnFoodPoint(HardcoreState s, double decayPerPoint) {
        s.protein = Math.max(0.0, s.protein - decayPerPoint);
        s.vitamins = Math.max(0.0, s.vitamins - decayPerPoint);
        s.sugar = Math.max(0.0, s.sugar - decayPerPoint);
    }

    /** The levels of a new life, and of a player nutrients has never seen. */
    public static void start(HardcoreState s, double startLevel) {
        s.protein = startLevel;
        s.vitamins = startLevel;
        s.sugar = startLevel;
    }

    /** True once any group hasn't been given its starting level. */
    public static boolean unset(HardcoreState s) {
        return s.protein < 0.0 || s.vitamins < 0.0 || s.sugar < 0.0;
    }

    public static boolean low(double level, double deficientBelow) {
        return level < deficientBelow;
    }

    // A group the food doesn't name is left as it is
    private static double gained(double level, double gain, Double share) {
        if (share == null || !(share > 0.0)) {
            return level;
        }
        return Math.min(1.0, level + gain * share);
    }
}
