package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;

/** The mod on its own, with You Only Live Twice nowhere on the server. */
public class HardcoreAloneGameTests {
    @GameTest
    public void theModRunsWithoutYolt(GameTestHelper helper) {
        FabricLoader loader = FabricLoader.getInstance();
        helper.assertTrue(loader.isModLoaded("yolt-hardcore"), "yolt-hardcore isn't loaded");
        helper.assertFalse(loader.isModLoaded("you-only-live-twice"), "you-only-live-twice is loaded");
        // Shipped inside this mod's jar, so a server with only it has them too
        for (String tinyMod : List.of("yolt-anvils", "yolt-mending", "yolt-elytra")) {
            helper.assertTrue(loader.isModLoaded(tinyMod), tinyMod + " isn't loaded");
        }
        helper.succeed();
    }
}
