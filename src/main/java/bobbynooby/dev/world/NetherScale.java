package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;

/** The coordinate scale between the overworld and the nether. */
public final class NetherScale {
    /** How far the Overworld side looks for a portal to link to at 1:1, the same as vanilla's Nether side. */
    public static final int OVERWORLD_PORTAL_SEARCH = 16;

    private NetherScale() {}

    /** One to one with the switch on, otherwise whatever vanilla says. */
    public static double scale(double vanilla) {
        return Settings.on(c -> c.netherScaleOneToOne) ? 1 : vanilla;
    }

    /**
     * Vanilla looks 128 blocks around the exit for an Overworld portal, sized for 8:1. At 1:1 that would link to someone
     * else's portal far off and show where it is, so the switch narrows it to the Nether side's 16.
     */
    public static int overworldPortalSearch(int vanilla) {
        return Settings.on(c -> c.netherScaleOneToOne) ? OVERWORLD_PORTAL_SEARCH : vanilla;
    }
}
