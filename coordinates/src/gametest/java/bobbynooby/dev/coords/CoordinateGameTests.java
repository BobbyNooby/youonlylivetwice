package bobbynooby.dev.coords;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The mod on its own, with You Only Live Twice nowhere on the server. YOLT's GameTests cover the same coordinate offset
 * hosted inside YOLT.
 */
public class CoordinateGameTests {
    @GameTest
    public void theModRunsWithoutYolt(GameTestHelper helper) {
        FabricLoader loader = FabricLoader.getInstance();
        helper.assertTrue(loader.isModLoaded("yolt-coordinates"), "yolt-coordinates isn't loaded");
        helper.assertFalse(loader.isModLoaded("you-only-live-twice"), "you-only-live-twice is loaded");
        helper.assertFalse(YoltCoordinates.hosted(), "the mod is hosted, but nothing here hosts it");
        helper.succeed();
    }
}
