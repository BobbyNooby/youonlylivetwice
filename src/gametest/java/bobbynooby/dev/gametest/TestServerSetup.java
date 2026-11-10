package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.skins.Skins;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class TestServerSetup implements ModInitializer {
    @Override
    public void onInitialize() {
        // Before any server starts, so YOLT never makes its Mojang lookup and no test reaches the network
        Skins.useLookupForTesting(FakeSkinLookup.INSTANCE);
        // SERVER_STARTING runs before the border is applied on SERVER_STARTED.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> applyTestSettings());
    }

    // Test structures sit millions of blocks out, so YOLT's border would kill anything living in a test.
    // Only the live settings change, so a test that reloads the config file calls this again.
    public static void applyTestSettings() {
        Settings.get().worldBorder.enabled = false;
        // Every test player's leave is a logout, so bodies stay off unless a body test turns them on
        Settings.get().bodies.enabled = false;
    }
}
