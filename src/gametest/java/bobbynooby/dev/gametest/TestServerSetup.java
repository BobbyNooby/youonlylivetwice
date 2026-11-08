package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class TestServerSetup implements ModInitializer {
    @Override
    public void onInitialize() {
        // Test structures sit millions of blocks out, so YOLT's border would kill anything living in a test.
        // SERVER_STARTING runs before the border is applied on SERVER_STARTED.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> Settings.get().worldBorder.enabled = false);
    }
}
