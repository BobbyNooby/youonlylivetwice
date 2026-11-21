package bobbynooby.dev.anvils;

import bobbynooby.dev.anvils.shared.Key;
import bobbynooby.dev.anvils.shared.SettingsFile;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * The config file of the mod on its own, config/yolt-anvils.json, and the rule each key's value must pass. How the file
 * is read and written is {@link SettingsFile}'s: key by key, so one bad value only keeps that value as it was.
 */
public final class AnvilConfig {
    public static final String FILE = "yolt-anvils.json";
    static final String ENABLED = "enabled";
    static final String ENCHANT_COST = "enchantCost";
    static final String REPAIR_COST = "repairCost";
    static final String RENAME_COST = "renameCost";
    static final String BREAK_CHANCE = "breakChance";
    // The keys in the order the file lists them
    static final SettingsFile<AnvilSettings> FORMAT = new SettingsFile<>(FILE, List.of(
            Key.flag(ENABLED, AnvilSettings::enabled),
            Key.whole(ENCHANT_COST, 1, AnvilSettings.MAX_PRICE, AnvilSettings::enchantCost),
            Key.whole(REPAIR_COST, 1, AnvilSettings.MAX_PRICE, AnvilSettings::repairCost),
            Key.whole(RENAME_COST, 1, AnvilSettings.MAX_PRICE, AnvilSettings::renameCost),
            Key.number(BREAK_CHANCE, AnvilSettings::isChance, "a number from 0 to 1", AnvilSettings::breakChance)),
            values -> new AnvilSettings(values.flag(ENABLED), values.whole(ENCHANT_COST), values.whole(REPAIR_COST),
                    values.whole(RENAME_COST), values.number(BREAK_CHANCE)));

    private AnvilConfig() {}

    /** Reads the file, writing it first with the fallback values if it isn't there. */
    public static SettingsFile.Result<AnvilSettings> load(Path file, AnvilSettings fallback) {
        return FORMAT.load(file, fallback);
    }

    /** Each key's value, or the fallback's for a key that is missing or refused. */
    static SettingsFile.Parsed<AnvilSettings> parse(JsonObject json, AnvilSettings fallback) {
        return FORMAT.parse(json, fallback);
    }

    /** The JSON for these settings, every key in the order the file lists them. */
    static String json(AnvilSettings settings) {
        return FORMAT.json(settings);
    }
}
