package bobbynooby.dev.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir
    Path dir;

    private ConfigLoader.Result loadJson(String json) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return ConfigLoader.load(file);
    }

    @Test
    void missingFileWritesDefaults() throws IOException {
        Path file = dir.resolve("config.json");
        ConfigLoader.Result result = ConfigLoader.load(file);
        assertEquals(10000, result.config().worldBorder.diameter);
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("\"cooldownDays\": 7"));
    }

    @Test
    void missingKeyKeepsOtherKeys() throws IOException {
        YoltConfig c = loadJson("{\"worldBorder\":{\"diameter\":5000}}").config();
        assertEquals(5000, c.worldBorder.diameter);
        assertEquals(2.0, c.worldBorder.endScale);
        assertEquals(7, c.deathLockout.cooldownDays);
    }

    @Test
    void endScaleKeepsDecimals() throws IOException {
        assertEquals(2.5, loadJson("{\"worldBorder\":{\"endScale\":2.5}}").config().worldBorder.endScale);
    }

    @Test
    void wrongTypeFallsBackForThatKeyOnly() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"diameter\":\"big\",\"endScale\":3.0}}");
        assertEquals(10000, r.config().worldBorder.diameter);
        assertEquals(3.0, r.config().worldBorder.endScale);
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("worldBorder.diameter"));
        assertFalse(r.unparseable());
    }

    @Test
    void unknownEnumFallsBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"chat\":{\"mode\":\"shout\"}}");
        assertEquals(YoltConfig.ChatMode.PROXIMITY, r.config().chat.mode);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("chat.mode")));
    }

    @Test
    void enumsReadLowercase() throws IOException {
        YoltConfig c = loadJson("{\"chat\":{\"mode\":\"self_only\"},\"graves\":{\"enderChestOnDeath\":\"wipe\"}}").config();
        assertEquals(YoltConfig.ChatMode.SELF_ONLY, c.chat.mode);
        assertEquals(YoltConfig.EnderChestMode.WIPE, c.graves.enderChestOnDeath);
    }

    @Test
    void durationAcceptsOfflineOrMinutes() throws IOException {
        assertEquals(30, loadJson("{\"bodies\":{\"duration\":30}}").config().bodies.duration.minutes());
        assertTrue(loadJson("{\"bodies\":{\"duration\":\"offline\"}}").config().bodies.duration.isOffline());

        ConfigLoader.Result zero = loadJson("{\"bodies\":{\"duration\":0}}");
        assertTrue(zero.config().bodies.duration.isOffline());
        assertTrue(zero.warnings().stream().anyMatch(w -> w.contains("bodies.duration")));
    }

    @Test
    void negativeCooldownFallsBackToDefault() throws IOException {
        assertEquals(7, loadJson("{\"deathLockout\":{\"cooldownDays\":-1}}").config().deathLockout.cooldownDays);
    }

    @Test
    void fileRewrittenWithEveryKey() throws IOException {
        loadJson("{\"randomSpawn\":false}");
        JsonObject written = JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject();
        assertTrue(written.get("bodies").isJsonObject());
        assertTrue(written.get("sleep").isJsonObject());
        assertTrue(written.get("hide").isJsonObject());
        assertFalse(written.get("randomSpawn").getAsBoolean());
    }

    @Test
    void unparseableFileIsBackedUpBeforeDefaultsWritten() throws IOException {
        ConfigLoader.Result r = loadJson("{not json");
        assertEquals("{not json", Files.readString(dir.resolve("config.json.bak")));
        assertEquals(10000, r.config().worldBorder.diameter);
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.unparseable());
    }

    // The reload path: the running server keeps its config, so the file must stay for the admin to fix
    @Test
    void unparseableFileLeftAloneWithoutDefaults() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{not json");
        ConfigLoader.Result r = ConfigLoader.load(file, false);
        assertTrue(r.unparseable());
        assertNull(r.config());
        assertFalse(r.warnings().isEmpty());
        assertEquals("{not json", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("config.json.bak")));
    }

    @Test
    void writeReplacesFileAndLeavesNoTempFile() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "old contents");
        YoltConfig config = new YoltConfig();
        config.randomSpawn = false;
        ConfigLoader.write(file, config);
        assertFalse(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("randomSpawn").getAsBoolean());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file.getFileName()), files.map(Path::getFileName).toList());
        }
    }

    @Test
    void fileHasConfigVersion() throws IOException {
        Path file = dir.resolve("config.json");
        ConfigLoader.load(file);
        assertTrue(Files.readString(file).contains("\"configVersion\": 1"));
    }

    @Test
    void hugeNumberFallsBackForThatKeyOnly() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"diameter\":99999999999,\"endScale\":3.0}}");
        assertEquals(10000, r.config().worldBorder.diameter);
        assertEquals(3.0, r.config().worldBorder.endScale);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("worldBorder.diameter")));
    }
}
