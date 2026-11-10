package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;

/**
 * The trail a creaking heart draws to its hurt creaking. Vanilla forces it out to 512 blocks with exact positions, so
 * it shows where someone is fighting far beyond sight.
 */
public final class CreakingTrail {
    private CreakingTrail() {}

    /** Vanilla's force flag, dropped while far players are hidden so the trail only reaches the normal 32 blocks. */
    public static boolean overrideLimiter(boolean vanilla) {
        return vanilla && !Settings.on(c -> c.hide.distantPlayers);
    }
}
