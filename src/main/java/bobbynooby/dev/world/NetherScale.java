package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;

/** The coordinate scale between the overworld and the nether. */
public final class NetherScale {
    private NetherScale() {}

    /** One to one with the switch on, otherwise whatever vanilla says. */
    public static double scale(double vanilla) {
        return Settings.on(c -> c.netherScaleOneToOne) ? 1 : vanilla;
    }
}
