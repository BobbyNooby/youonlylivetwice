package bobbynooby.dev.mending;

import bobbynooby.dev.mending.shared.SettingsFile;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MendingConfigTest {
    private static SettingsFile.Parsed<MendingSettings> parse(String json) {
        return MendingConfig.parse(JsonParser.parseString(json).getAsJsonObject(), MendingSettings.DEFAULTS);
    }

    @Test
    void theDefaultsAreOnAtPointFour() {
        assertEquals(new MendingSettings(true, 0.4), MendingSettings.DEFAULTS);
        assertEquals("{\n  \"enabled\": true,\n  \"durabilityPerXp\": 0.4\n}\n", MendingConfig.json(MendingSettings.DEFAULTS));
    }

    @Test
    void aWholeFileIsReadAsWritten() {
        SettingsFile.Parsed<MendingSettings> parsed = parse("{\"enabled\": false, \"durabilityPerXp\": 1.5}");
        assertEquals(new MendingSettings(false, 1.5), parsed.settings());
        assertTrue(parsed.refused().isEmpty() && parsed.missing().isEmpty() && parsed.unknown().isEmpty(), parsed.toString());
    }

    // Text, a flag and a list aren't numbers, and NaN passes no check
    @Test
    void aRateBelowZeroOrNotANumberIsRefused() {
        for (String bad : List.of("-1", "\"0.4\"", "true", "[]", "NaN")) {
            SettingsFile.Parsed<MendingSettings> parsed = parse("{\"durabilityPerXp\": " + bad + "}");
            assertEquals(0.4, parsed.settings().durabilityPerXp(), bad);
            assertEquals(List.of("durabilityPerXp must be 0 or more, kept 0.4"), parsed.refused(), bad);
        }
    }

    // 0 turns the repair off and sends all XP to the bar, which is a setting of its own and not a mistake
    @Test
    void zeroIsAllowed() {
        SettingsFile.Parsed<MendingSettings> parsed = parse("{\"durabilityPerXp\": 0}");
        assertEquals(0.0, parsed.settings().durabilityPerXp());
        assertTrue(parsed.refused().isEmpty(), parsed.refused().toString());
    }

    @Test
    void theDescribeLine() {
        assertEquals("Mending: 0.4 durability per XP point (vanilla 2)", new MendingSettings(true, 0.4).describe());
        assertEquals("Mending: 2 durability per XP point (vanilla 2)", new MendingSettings(true, 2.0).describe());
        assertEquals("The Mending nerf is off", new MendingSettings(false, 0.4).describe());
    }
}
