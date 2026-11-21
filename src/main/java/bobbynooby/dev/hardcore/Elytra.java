package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.elytra.YoltElytra;

/**
 * The elytra nerf is the YOLT Elytra mod, shipped inside YOLT's jar. YOLT hosts it: it reads hardcore.elytra from
 * YOLT's own config, live, so a reload or /yolt config change applies at the next tick, follows YOLT's single-player
 * switch, and leaves out stand-ins such as player bodies, and the players every hardcore feature leaves out. The mod
 * ticks its own players and takes its gravity change off anyone it leaves out, so it needs no hardcore tick.
 */
public final class Elytra {
    private Elytra() {}

    static void init() {
        YoltElytra.hostedBy("You Only Live Twice", Elytra::settings,
                player -> StandIn.is(player) || Hardcore.exempt(player));
    }

    private static ElytraSettings settings() {
        HardcoreConfig.ElytraSection elytra = Settings.get().hardcore.elytra;
        return new ElytraSettings(Settings.on(c -> c.hardcore.elytra.enabled), elytra.glideGravityMultiplier,
                elytra.rocketCutoffSpeed);
    }
}
