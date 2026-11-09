package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;

/** What the sleep switches mean; SleepStatusMixin and NoBedRespawnMixin only ask these. */
public final class Sleep {
    /** More sleepers than any server holds, so the night never skips. */
    public static final int SLEEPERS_NEEDED_WHEN_BLOCKED = 727;

    private Sleep() {}

    /** True when the sleeper count must read zero, so nobody can tell who is in bed. */
    public static boolean hidesSleepers() {
        return Settings.on(c -> c.sleep.hideSleepers);
    }

    public static boolean blocksNightSkip() {
        return Settings.on(c -> c.sleep.noNightSkip);
    }

    public static boolean ignoresRespawnPoint() {
        return Settings.on(c -> c.sleep.noBedRespawn);
    }
}
