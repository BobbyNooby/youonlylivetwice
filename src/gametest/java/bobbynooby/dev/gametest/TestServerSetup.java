package bobbynooby.dev.gametest;

import bobbynooby.dev.commands.SkinCommands;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.skins.Skins;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.level.gamerules.GameRules;

import java.time.Duration;

public class TestServerSetup implements ModInitializer {
    @Override
    public void onInitialize() {
        ConfigEnvironment.register();
        // Before any server starts, so YOLT never makes its Mojang lookup and no test reaches the network
        Skins.useLookupForTesting(FakeSkinLookup.INSTANCE);
        // The skin tests pick one name after another; the wait's own test puts it back for its tick
        SkinCommands.useCooldownForTesting(Duration.ZERO);
        // SERVER_STARTING runs before the border is applied on SERVER_STARTED.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> applyTestSettings());
        // A monster that wanders near a bed makes vanilla refuse to sleep there, so none spawn on their own. Tests that
        // need one spawn it.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server instanceof GameTestServer) {
                server.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
            }
        });
    }

    // Test structures sit millions of blocks out, so YOLT's border would kill anything living in a test.
    // Only the live settings change, so a test that reloads the config file calls this again.
    public static void applyTestSettings() {
        Settings.get().worldBorder.enabled = false;
        // Every test player's leave is a logout, so bodies stay off unless a body test turns them on
        Settings.get().bodies.enabled = false;
        // Tests take grave heads away on purpose and check what happens next, which the re-check would undo under them.
        // The re-check's own tests turn it on, in batches of their own.
        Settings.get().graves.recheckSeconds = 0;
        pinOldDefaults();
    }

    // These switches ship on, but most tests were written for a world with them off: an offset on every join, whispers
    // and /status in every non-op's command tree, a flat anvil price and YOLT's Mending. So the shared world keeps them
    // off, and a test that needs one on sets it itself or comes from an environment that does. ShippedDefaultsGameTests
    // turns them back on to check the defaults themselves.
    private static void pinOldDefaults() {
        Settings.get().hide.coordinates = false;
        Settings.get().chat.allowWhispers = false;
        Settings.get().skins.lockAfterFirstChoice = false;
        Settings.get().hardcore.anvil.enabled = false;
        Settings.get().hardcore.mending.enabled = false;
    }
}
