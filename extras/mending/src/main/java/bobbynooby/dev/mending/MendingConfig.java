package bobbynooby.dev.mending;

import bobbynooby.dev.mending.shared.Key;
import bobbynooby.dev.mending.shared.SettingsFile;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * The config file of the mod on its own, config/yolt-mending.json, and the rule each key's value must pass. How the file
 * is read and written is {@link SettingsFile}'s: key by key, so one bad value only keeps that value as it was.
 */
public final class MendingConfig {
    public static final String FILE = "yolt-mending.json";
    static final String ENABLED = "enabled";
    static final String DURABILITY_PER_XP = "durabilityPerXp";
    // The keys in the order the file lists them
    static final SettingsFile<MendingSettings> FORMAT = new SettingsFile<>(FILE, List.of(
            Key.flag(ENABLED, MendingSettings::enabled),
            Key.number(DURABILITY_PER_XP, MendingSettings::isRate, "0 or more", MendingSettings::durabilityPerXp)),
            values -> new MendingSettings(values.flag(ENABLED), values.number(DURABILITY_PER_XP)));

    private MendingConfig() {}

    /** Reads the file, writing it first with the fallback values if it isn't there. */
    public static SettingsFile.Result<MendingSettings> load(Path file, MendingSettings fallback) {
        return FORMAT.load(file, fallback);
    }

    /** Each key's value, or the fallback's for a key that is missing or refused. */
    static SettingsFile.Parsed<MendingSettings> parse(JsonObject json, MendingSettings fallback) {
        return FORMAT.parse(json, fallback);
    }

    /** The JSON for these settings, every key in the order the file lists them. */
    static String json(MendingSettings settings) {
        return FORMAT.json(settings);
    }
}
