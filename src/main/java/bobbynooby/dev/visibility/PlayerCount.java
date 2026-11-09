package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;

/** The player counts the server list shows. */
public final class PlayerCount {
    // Clients show "0/1" for a server that has a slot but nobody on it, which tells nothing about the real numbers
    public static final int SHOWN_MAX = 1;
    public static final int SHOWN_ONLINE = 0;

    private PlayerCount() {}

    /** True when the server list must show the fake counts and no names. */
    public static boolean hides() {
        return Settings.on(c -> c.hide.serverPlayerCount);
    }
}
