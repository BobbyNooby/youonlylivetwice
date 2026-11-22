package bobbynooby.dev.gametest;

import bobbynooby.dev.anvils.AnvilConfig;
import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.coords.CoordinateConfig;
import bobbynooby.dev.coords.YoltCoordinates;
import bobbynooby.dev.elytra.ElytraConfig;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.mending.MendingConfig;
import bobbynooby.dev.mending.YoltMending;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

// Each mod YOLT ships takes YOLT's config, so it writes no file of its own and has no command. The config folder
// outlives a run, so a file there may have been left by an older one: only a file changed since this server's JVM
// started is this start's doing. A leftover is removed, then the mod is reloaded, which a mod on its own answers by
// writing the missing file.
public class HostedModsGameTests {
    @GameTest
    public void hostedModsHaveNoFileAndNoCommand(GameTestHelper helper) throws IOException {
        hasNoFileAndNoCommand(helper, YoltAnvils.MOD_ID, AnvilConfig.FILE, YoltAnvils.hosted(), YoltAnvils::reload);
        hasNoFileAndNoCommand(helper, YoltMending.MOD_ID, MendingConfig.FILE, YoltMending.hosted(), YoltMending::reload);
        hasNoFileAndNoCommand(helper, YoltElytra.MOD_ID, ElytraConfig.FILE, YoltElytra.hosted(), YoltElytra::reload);
        hasNoFileAndNoCommand(helper, YoltCoordinates.MOD_ID, CoordinateConfig.FILE, YoltCoordinates.hosted(),
                YoltCoordinates::reload);
        helper.succeed();
    }

    private static void hasNoFileAndNoCommand(GameTestHelper helper, String id, String fileName, boolean hosted,
            Runnable reload) throws IOException {
        helper.assertTrue(hosted, id + " isn't hosted");
        Path file = FabricLoader.getInstance().getConfigDir().resolve(fileName);
        if (Files.exists(file)) {
            long started = ManagementFactory.getRuntimeMXBean().getStartTime();
            long changed = Files.getLastModifiedTime(file).toMillis();
            helper.assertTrue(changed < started, "this server start wrote " + file);
            Files.delete(file);
        }
        reload.run();
        helper.assertFalse(Files.exists(file), "a reload wrote " + file);
        helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild(id) == null,
                "/" + id + " is there while hosted");
    }
}
