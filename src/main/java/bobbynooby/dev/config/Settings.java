package bobbynooby.dev.config;

import bobbynooby.dev.YouOnlyLiveTwice;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;

/** Holds the live config that every feature reads while the server runs. */
public final class Settings {
    // volatile because the status ping reads these from network threads
    private static volatile YoltConfig config = new YoltConfig();
    private static volatile boolean active;

    private Settings() {}

    public static YoltConfig get() {
        return config;
    }

    /** Reads the file at mod start and logs anything that fell back to a default. */
    public static void load() {
        for (String warning : reload()) {
            YouOnlyLiveTwice.LOGGER.warn("Config: {}", warning);
        }
    }

    public static List<String> reload() {
        ConfigLoader.Result result = ConfigLoader.load(path());
        config = result.config();
        return result.warnings();
    }

    public static void save() {
        ConfigLoader.write(path(), config);
    }

    /** True while a server with no single-player owner runs, so single-player worlds stay vanilla. */
    public static boolean active() {
        return active;
    }

    public static void onServerStarting(MinecraftServer server) {
        active = server.getSingleplayerProfile() == null;
    }

    public static void onServerStopped(MinecraftServer server) {
        active = false;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("you-only-live-twice.json");
    }
}
