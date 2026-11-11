package bobbynooby.dev.config;

import bobbynooby.dev.core.Yolt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Predicate;

/** Holds the live config that every feature reads while the server runs. */
public final class Settings {
    // volatile because the status ping reads these from network threads
    private static volatile YoltConfig config = new YoltConfig();
    private static volatile boolean active;
    // Set when the startup's border values fell back to defaults, which could strand players: the world then keeps the
    // border it saved until a reload or a border command applies one checked for who it leaves outside
    private static volatile boolean borderHeld;
    private static volatile @Nullable String startupBackup;

    private Settings() {}

    public static YoltConfig get() {
        return config;
    }

    /**
     * Reads the file at mod start and logs anything that fell back to a default. A broken file is replaced with
     * defaults, and border values that fell back are held rather than applied.
     */
    public static void load() {
        ConfigLoader.Result result = ConfigLoader.load(path(), null, true);
        config = result.config();
        for (String warning : result.warnings()) {
            Yolt.LOGGER.warn("Config: {}", warning);
        }
        borderHeld = result.borderProblem() != null;
        startupBackup = result.backup();
        if (borderHeld) {
            Yolt.LOGGER.warn("Config: {}, so YOLT left the world border as the world saved it. Fix the config, then run /yolt reload.",
                    result.borderProblem());
        }
    }

    /** Re-reads the file. A bad value keeps the live one, and a file that isn't valid JSON changes nothing, so one typo can't reset a running server. */
    public static ConfigLoader.Result reload() {
        ConfigLoader.Result result = ConfigLoader.load(path(), config, false);
        if (!result.unparseable()) {
            config = result.config();
        }
        return result;
    }

    /**
     * Sets one key in the file and the live config from what an admin typed. A text the key can't take, or a value its
     * range check refuses, comes back as the problem and changes nothing; so does the value the key has already, with
     * no problem. A file that couldn't be read is kept as change.backup before it is rewritten. The caller then applies
     * the new config with LiveSettings.applyAll.
     */
    public static ConfigLoader.Change set(ConfigKey key, String text) {
        return set(key, text, next -> null);
    }

    /**
     * The same, and veto sees the config a good value would give before anything is saved: a reason it returns refuses
     * the change as the problem, and null lets it through.
     */
    public static ConfigLoader.Change set(ConfigKey key, String text, Function<YoltConfig, @Nullable String> veto) {
        ConfigLoader.Change change = ConfigLoader.setText(path(), config, key, text, veto);
        if (change.config() != null) {
            config = change.config();
        }
        if (change.backup() != null) {
            Yolt.LOGGER.warn("Config: the file could not be read when {} was set, so it is saved as {}{}", key.path(), change.backup(),
                    change.config() != null ? " and rewritten" : ", but " + change.problem());
        }
        return change;
    }

    /** True while the border values in the config are startup fallbacks, so YOLT leaves the world's own border alone. */
    public static boolean borderHeld() {
        return borderHeld;
    }

    /** The copy the startup load kept of the admin's file before writing over it, or null if it wrote over nothing. */
    public static @Nullable String startupBackup() {
        return startupBackup;
    }

    public static void releaseBorder() {
        borderHeld = false;
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
