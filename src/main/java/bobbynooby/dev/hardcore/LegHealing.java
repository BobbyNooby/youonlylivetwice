package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;

/** The sums behind broken legs, apart from the game so they can be checked on their own. */
public final class LegHealing {
    // Vanilla's Regeneration heals every 50 ticks at level I, twice as often per level above
    private static final int REGENERATION_TICKS = 50;

    private LegHealing() {}

    /**
     * The fall damage that breaks legs: breakAtDamage, or with heavy landings scaled down towards the full-load value as
     * the load goes from the free load (share 0) to the full one (share 1).
     */
    public static double breakAt(double breakAtDamage, double breakAtDamageFullLoad, boolean heavyLandings, double loadShare) {
        if (!heavyLandings) {
            return breakAtDamage;
        }
        double share = Math.max(0.0, Math.min(loadShare, 1.0));
        return breakAtDamage - (breakAtDamage - breakAtDamageFullLoad) * share;
    }

    /**
     * Ticks per heal point from food, as vanilla's own regen paces itself: faster when food is full and saturated, slower
     * at 18 or more, none below. With the natural regeneration rule off it is a fixed pace that uses no food. 0 means
     * no healing now.
     */
    public static int ticksPerPoint(int foodLevel, float saturation, boolean regenRule, HardcoreConfig.LegsSection cfg) {
        if (!regenRule) {
            return Math.max(cfg.healTicksNoRegenRule, 0);
        }
        if (saturation > 0.0F && foodLevel >= 20) {
            return Math.max(cfg.healTicksSaturated, 0);
        }
        if (foodLevel >= 18) {
            return Math.max(cfg.healTicks, 0);
        }
        return 0;
    }

    /** Ticks per heal point under a Regeneration effect of this amplifier (0 is level I), at vanilla's own pace. */
    public static int regenerationTicksPerPoint(int amplifier) {
        // Java shifts by the amount modulo 32, so a huge level would wrap round to a slow pace
        if (amplifier >= Integer.SIZE - 1) {
            return 1;
        }
        return Math.max(REGENERATION_TICKS >> Math.max(amplifier, 0), 1);
    }
}
