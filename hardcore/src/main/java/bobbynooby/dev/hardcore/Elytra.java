package bobbynooby.dev.hardcore;

import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.elytra.YoltElytra;

/**
 * The elytra nerf is the YOLT Elytra mod, shipped inside YOLT Hardcore's jar. YOLT Hardcore hosts it: it reads
 * hardcore.elytra from the settings in use, live, so under YOLT a reload or /yolt config change applies at the next
 * tick, follows the host's switch, such as YOLT's for single-player, and leaves out stand-ins such as player bodies,
 * and the players every hardcore feature leaves out. Its start-up line names the mod those settings come from. The mod
 * ticks its own players and takes its gravity change off anyone it leaves out, so it needs no hardcore tick.
 */
public final class Elytra {
    private Elytra() {}

    static void init() {
        YoltElytra.hostedBy(Host.name(), Elytra::settings,
                player -> Host.isStandIn(player) || Hardcore.exempt(player));
    }

    private static ElytraSettings settings() {
        HardcoreConfig.ElytraSection elytra = Host.settings().elytra;
        return new ElytraSettings(Host.on(c -> c.elytra.enabled), elytra.glideGravityMultiplier,
                elytra.rocketCutoffSpeed);
    }
}
