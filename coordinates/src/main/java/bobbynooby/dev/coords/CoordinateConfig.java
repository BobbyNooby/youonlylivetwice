package bobbynooby.dev.coords;

import bobbynooby.dev.coords.shared.Key;
import bobbynooby.dev.coords.shared.SettingsFile;

import java.util.List;

/**
 * The config file of the mod on its own, config/yolt-coordinates.json. How the file is read and written is
 * {@link SettingsFile}'s: key by key, so a bad value only keeps that value as it was.
 */
public final class CoordinateConfig {
    public static final String FILE = "yolt-coordinates.json";
    static final String ENABLED = "enabled";
    static final SettingsFile<CoordinateSettings> FORMAT = new SettingsFile<>(FILE, List.of(
            Key.flag(ENABLED, CoordinateSettings::enabled)),
            values -> new CoordinateSettings(values.flag(ENABLED)));

    private CoordinateConfig() {}
}
