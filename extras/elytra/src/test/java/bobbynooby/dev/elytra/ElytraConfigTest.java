package bobbynooby.dev.elytra;

import bobbynooby.dev.elytra.shared.SettingsFile;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraConfigTest {
    private static SettingsFile.Parsed<ElytraSettings> parse(String json) {
        return ElytraConfig.parse(JsonParser.parseString(json).getAsJsonObject(), ElytraSettings.DEFAULTS);
    }

    @Test
    void theDefaultsAreOn() {
        assertEquals(new ElytraSettings(true, 0.6, 20.0), ElytraSettings.DEFAULTS);
        assertEquals("{\n  \"enabled\": true,\n  \"glideGravityMultiplier\": 0.6,\n  \"rocketCutoffSpeed\": 20.0\n}\n",
                ElytraConfig.json(ElytraSettings.DEFAULTS));
    }

    @Test
    void aWholeFileIsReadAsWritten() {
        SettingsFile.Parsed<ElytraSettings> parsed =
                parse("{\"enabled\": false, \"glideGravityMultiplier\": 0.8, \"rocketCutoffSpeed\": 35}");
        assertEquals(new ElytraSettings(false, 0.8, 35.0), parsed.settings());
        assertTrue(parsed.refused().isEmpty() && parsed.missing().isEmpty() && parsed.unknown().isEmpty(), parsed.toString());
    }

    // Text, a flag and a list aren't numbers, and NaN passes no check
    @Test
    void numbersBelowZeroOrNotNumbersAreRefused() {
        for (String bad : List.of("-1", "\"1\"", "true", "[]", "NaN")) {
            SettingsFile.Parsed<ElytraSettings> gravity = parse("{\"glideGravityMultiplier\": " + bad + "}");
            assertEquals(ElytraSettings.DEFAULTS, gravity.settings(), bad);
            assertEquals(List.of("glideGravityMultiplier must be 0 or more, kept 0.6"), gravity.refused(), bad);

            SettingsFile.Parsed<ElytraSettings> cutoff = parse("{\"rocketCutoffSpeed\": " + bad + "}");
            assertEquals(ElytraSettings.DEFAULTS, cutoff.settings(), bad);
            assertEquals(List.of("rocketCutoffSpeed must be 0 or more, kept 20.0"), cutoff.refused(), bad);
        }
    }

    // A cut-off of 0 never cuts, which is a setting of its own and not a mistake, and 0 is where both rules start
    @Test
    void zeroIsAllowedForBoth() {
        SettingsFile.Parsed<ElytraSettings> parsed = parse("{\"glideGravityMultiplier\": 0, \"rocketCutoffSpeed\": 0}");
        assertEquals(new ElytraSettings(true, 0.0, 0.0), parsed.settings());
        assertTrue(parsed.refused().isEmpty(), parsed.refused().toString());
    }

    @Test
    void theDescribeLine() {
        assertEquals("Elytra: glide gravity 0.6, rocket cut-off 20 m/s", ElytraSettings.DEFAULTS.describe());
        assertEquals("Elytra: glide gravity 0.6, no rocket cut-off", new ElytraSettings(true, 0.6, 0.0).describe());
        assertEquals("The elytra nerf is off", new ElytraSettings(false, 0.6, 20.0).describe());
    }
}
