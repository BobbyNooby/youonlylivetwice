package bobbynooby.dev.config;

import bobbynooby.dev.core.Yolt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.function.Predicate;

/** Holds the live config that every feature reads while the server runs. */
public final class Settings {
    // volatile because the status ping reads these from network threads
    private static volatile YoltConfig config = new YoltConfig();
    private static volatile boolean active;

    private Settings() {}

    public static YoltConfig get() {
        return config;
    }

    /** Reads the file at mod start and logs anything that fell back to a default. A broken file is replaced with defaults. */
    public static void load() {
        ConfigLoader.Result result = ConfigLoader.load(path(), true);
        config = result.config();
        for (String warning : result.warnings()) {
            Yolt.LOGGER.warn("Config: {}", warning);
        }
    }

    /** Re-reads the file. A file that isn't valid JSON changes nothing, so one typo can't reset a running server. */
    public static ConfigLoader.Result reload() {
        ConfigLoader.Result result = ConfigLoader.load(path(), false);
        if (!result.unparseable()) {
            config = result.config();
        }
        return result;
    }

    public static void save() {
        ConfigLoader.write(path(), config);
    }

    /** True while a server with no single-player owner runs, so single-player worlds stay vanilla. */
    public static boolean active() {
        return active;
    }

    /** True when the server is active and this switch is on; reads the live config at the moment of the call. */
    public static boolean on(Predicate<YoltConfig> switchOn) {
        return active && switchOn.test(config);
    }

    public static void onServerStarting(MinecraftServer server) {
        active = server.getSingleplayerProfile() == null;
    }

    public static void onServerStopped(MinecraftServer server) {
        active = false;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("you-only-live-twice.json");
    }
}
