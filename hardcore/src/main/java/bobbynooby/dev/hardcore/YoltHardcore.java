package bobbynooby.dev.hardcore;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YOLT Hardcore: the hardcore layer as a mod of its own. For now it holds the hardcore settings and their checks, which
 * You Only Live Twice reads its config with, and ships the tiny mods; the features themselves still run inside YOLT.
 */
public final class YoltHardcore implements ModInitializer {
    public static final String MOD_ID = "yolt-hardcore";
    public static final Logger LOGGER = LoggerFactory.getLogger("YOLT Hardcore");

    @Override
    public void onInitialize() {
        // Nothing runs on its own yet
    }
}
