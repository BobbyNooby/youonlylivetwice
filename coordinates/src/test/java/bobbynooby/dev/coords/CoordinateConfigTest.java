package bobbynooby.dev.coords;

import bobbynooby.dev.coords.shared.SettingsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A reload reads the file with the settings in use as the fallback, and a server start with the defaults
class CoordinateConfigTest {
    private static final CoordinateSettings OFF = new CoordinateSettings(false);

    @TempDir
    Path folder;

    private Path file(String text) throws IOException {
        Path file = folder.resolve(CoordinateConfig.FILE);
        Files.writeString(file, text);
        return file;
    }

    @Test
    void aMissingFileIsWrittenOn() throws IOException {
        Path file = folder.resolve("config").resolve(CoordinateConfig.FILE);
        SettingsFile.Result<CoordinateSettings> result = CoordinateConfig.FORMAT.load(file, CoordinateSettings.DEFAULTS);
        assertFalse(result.failed(), result.notes().toString());
        assertEquals(CoordinateSettings.DEFAULTS, result.settings());
        assertEquals("{\n  \"enabled\": true\n}\n", Files.readString(file));
    }

    @Test
    void enabledFalseLoadsAsOff() throws IOException {
        SettingsFile.Result<CoordinateSettings> result =
                CoordinateConfig.FORMAT.load(file("{\"enabled\": false}"), CoordinateSettings.DEFAULTS);
        assertFalse(result.failed(), result.notes().toString());
        assertEquals(OFF, result.settings());
        assertTrue(result.notes().isEmpty(), result.notes().toString());
    }

    // Text is not a flag, so the value in use stays, whichever it is, and the file is left as typed
    @Test
    void textForEnabledKeepsTheValueInUseAndNamesIt() throws IOException {
        String typed = "{\"enabled\": \"yes\"}";
        for (CoordinateSettings inUse : List.of(CoordinateSettings.DEFAULTS, OFF)) {
            Path file = file(typed);
            SettingsFile.Result<CoordinateSettings> result = CoordinateConfig.FORMAT.load(file, inUse);
            assertFalse(result.failed(), result.notes().toString());
            assertEquals(inUse, result.settings());
            assertEquals(List.of("enabled must be true or false, kept " + inUse.enabled()), result.notes());
            assertEquals(typed, Files.readString(file), "the file was written over");
        }
    }

    @Test
    void aFileThatIsntJsonFailsAndAReloadChangesNothing() throws IOException {
        for (String bad : List.of("{ \"enabled\": ", "enabled: false", "[false]", "")) {
            Path file = file(bad);
            SettingsFile.Result<CoordinateSettings> result = CoordinateConfig.FORMAT.load(file, OFF);
            assertTrue(result.failed(), "'" + bad + "' was read");
            assertEquals(OFF, result.settings(), bad);
            assertTrue(result.notes().stream().anyMatch(note -> note.startsWith(CoordinateConfig.FILE)),
                    bad + ": " + result.notes());
            assertEquals(bad, Files.readString(file), "'" + bad + "' was written over");
        }
    }
}
