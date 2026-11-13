package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.BloodMoon;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;

/** What the sleep switches mean; the sleep and bed-respawn hooks only ask these. */
public final class Sleep {
    /** Far more sleepers than a real server has in bed at once, so in practice the night never skips. */
    public static final int SLEEPERS_NEEDED_WHEN_BLOCKED = 727;

    private Sleep() {}

    // Fabric's event wraps only the bed's own call, so /spawnpoint still sets a point. Skipping it also skips "Respawn
    // point set". Anchors have no event, so a mixin keeps those.
    public static void init() {
        EntitySleepEvents.ALLOW_SETTING_SPAWN.register((player, sleepingPos) -> !bedsSetNoRespawnPoint());
    }

    /** True when the sleeper count must read zero, so nobody can tell who is in bed. */
    public static boolean hidesSleepers() {
        return Settings.on(c -> c.sleep.hideSleepers);
    }

    /** True when sleeping can't skip the night: always with the switch on, and during a blood moon unless it allows it. */
    public static boolean blocksNightSkip() {
        return Settings.on(c -> c.sleep.noNightSkip) || BloodMoon.blocksSkip();
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
