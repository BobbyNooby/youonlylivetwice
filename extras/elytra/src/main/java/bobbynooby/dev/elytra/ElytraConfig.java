package bobbynooby.dev.elytra;

import bobbynooby.dev.elytra.shared.Key;
import bobbynooby.dev.elytra.shared.SettingsFile;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * The config file of the mod on its own, config/yolt-elytra.json, and the rule each key's value must pass. How the file
 * is read and written is {@link SettingsFile}'s: key by key, so one bad value only keeps that value as it was.
 */
public final class ElytraConfig {
    public static final String FILE = "yolt-elytra.json";
    static final String ENABLED = "enabled";
    static final String GLIDE_GRAVITY_MULTIPLIER = "glideGravityMultiplier";
    static final String ROCKET_CUTOFF_SPEED = "rocketCutoffSpeed";
    // The keys in the order the file lists them
    static final SettingsFile<ElytraSettings> FORMAT = new SettingsFile<>(FILE, List.of(
            Key.flag(ENABLED, ElytraSettings::enabled),
            Key.number(GLIDE_GRAVITY_MULTIPLIER, ElytraSettings::isMultiplier, "0 or more",
                    ElytraSettings::glideGravityMultiplier),
            Key.number(ROCKET_CUTOFF_SPEED, ElytraSettings::isSpeed, "0 or more", ElytraSettings::rocketCutoffSpeed)),
            values -> new ElytraSettings(values.flag(ENABLED), values.number(GLIDE_GRAVITY_MULTIPLIER),
                    values.number(ROCKET_CUTOFF_SPEED)));

    private ElytraConfig() {}

    /** Reads the file, writing it first with the fallback values if it isn't there. */
    public static SettingsFile.Result<ElytraSettings> load(Path file, ElytraSettings fallback) {
        return FORMAT.load(file, fallback);
    }

    /** Each key's value, or the fallback's for a key that is missing or refused. */
    static SettingsFile.Parsed<ElytraSettings> parse(JsonObject json, ElytraSettings fallback) {
        return FORMAT.parse(json, fallback);
    }

    /** The JSON for these settings, every key in the order the file lists them. */
    static String json(ElytraSettings settings) {
        return FORMAT.json(settings);
    }
}
