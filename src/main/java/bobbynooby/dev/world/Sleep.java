package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;

/** What the sleep switches mean; the sleep and bed-respawn mixins only ask these. */
public final class Sleep {
    /** Far more sleepers than a real server has in bed at once, so in practice the night never skips. */
    public static final int SLEEPERS_NEEDED_WHEN_BLOCKED = 727;

    private Sleep() {}

    /** True when the sleeper count must read zero, so nobody can tell who is in bed. */
    public static boolean hidesSleepers() {
        return Settings.on(c -> c.sleep.hideSleepers);
    }

    public static boolean blocksNightSkip() {
        return Settings.on(c -> c.sleep.noNightSkip);
    }

    /** True when nobody gets the "players sleeping" line; with the night blocked its count could never be reached. */
    public static boolean hidesSleepStatus() {
        return hidesSleepers() || blocksNightSkip();
    }

    /** True when beds and respawn anchors set no respawn point; a point set by /spawnpoint still works. */
    public static boolean bedsSetNoRespawnPoint() {
        return Settings.on(c -> c.sleep.noBedRespawn);
    }

    /**
     * True when a stored point must not be used. Beds and anchors store unforced points, and older builds stored
     * them even with the switch on; /spawnpoint's are forced and always count.
     */
    public static boolean ignoresStoredPoint(boolean forced) {
        return !forced && bedsSetNoRespawnPoint();
    }
}
