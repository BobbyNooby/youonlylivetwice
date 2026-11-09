package bobbynooby.dev.config;

import bobbynooby.dev.core.Migrations;
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
        return ConfigLoader.load(file, null, true);
    }

    @Test
    void missingFileWritesDefaults() throws IOException {
        Path file = dir.resolve("config.json");
        ConfigLoader.Result result = ConfigLoader.load(file, null, true);
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
        ConfigLoader.Result r = ConfigLoader.load(file, null, false);
        assertTrue(r.unparseable());
        assertNull(r.config());
        assertFalse(r.warnings().isEmpty());
        assertEquals("{not json", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("config.json.bak")));
    }

    // Bytes C3 28 are not valid UTF-8, so reading the file throws
    @Test
    void nonUtf8FileIsTreatedAsInvalid() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] bad = {(byte) 0xC3, 0x28};
        Files.write(file, bad);

        ConfigLoader.Result reload = ConfigLoader.load(file, null, false);
        assertTrue(reload.unparseable());
        assertNull(reload.config());
        assertFalse(reload.warnings().isEmpty());
        assertArrayEquals(bad, Files.readAllBytes(file));

        ConfigLoader.Result startup = ConfigLoader.load(file, null, true);
        assertTrue(startup.unparseable());
        assertEquals(10000, startup.config().worldBorder.diameter);
        assertArrayEquals(bad, Files.readAllBytes(dir.resolve("config.json.bak")));
    }

    @Test
    void badValueOnReloadKeepsLiveValue() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"worldBorder\":{\"diameter\":\"2OOOO\"},\"chat\":{\"radius\":99}}");
        YoltConfig base = new YoltConfig();
        base.worldBorder.diameter = 20000;
        base.chat.mode = YoltConfig.ChatMode.GLOBAL;

        ConfigLoader.Result r = ConfigLoader.load(file, base, false);
        assertEquals(20000, r.config().worldBorder.diameter);
        assertEquals(99, r.config().chat.radius);
        // a key the file leaves out keeps the live value too
        assertEquals(YoltConfig.ChatMode.GLOBAL, r.config().chat.mode);
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("worldBorder.diameter"));
        assertTrue(r.warnings().get(0).endsWith("using the current value"));
    }

    @Test
    void missingFileOnReloadKeepsLiveConfig() {
        Path file = dir.resolve("config.json");
        YoltConfig base = new YoltConfig();
        base.chat.radius = 32;

        ConfigLoader.Result r = ConfigLoader.load(file, base, false);
        assertEquals(32, r.config().chat.radius);
        assertEquals(List.of("config file is missing, nothing changed"), r.warnings());
        assertFalse(r.unparseable());
        assertFalse(Files.exists(file));
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
        ConfigLoader.load(file, null, true);
        assertTrue(Files.readString(file).contains("\"configVersion\": 1"));
    }

    // There is no real upgrade yet, so a step that renames a key stands in for one
    @Test
    void olderConfigIsBackedUpThenUpgraded() throws IOException {
        Path file = dir.resolve("config.json");
        String old = "{\"configVersion\": 1, \"spawnRandomly\": false}";
        Files.writeString(file, old);
        List<Migrations.Step<JsonObject>> steps = List.of(new Migrations.Step<JsonObject>(1, json -> {
            json.add("randomSpawn", json.remove("spawnRandomly"));
            return json;
        }));

        ConfigLoader.Result r = ConfigLoader.load(file, null, true, 2, steps);
        assertEquals(old, Files.readString(dir.resolve("config.json.v1.bak")));
        JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(2, written.get("configVersion").getAsInt());
        assertFalse(written.get("randomSpawn").getAsBoolean());
        assertFalse(written.has("spawnRandomly"));
        assertFalse(r.config().randomSpawn);
    }

    // Only a damaged file has these, so they read as a missing version: no backup and no upgrade
    @Test
    void zeroOrNegativeConfigVersionReadsAsMissing() throws IOException {
        Path file = dir.resolve("config.json");
        for (String version : List.of("0", "-1", "-2147483648")) {
            Files.writeString(file, "{\"configVersion\": " + version + ", \"randomSpawn\": false}");
            ConfigLoader.Result r = ConfigLoader.load(file, null, true);
            assertFalse(r.config().randomSpawn);
            JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals(ConfigLoader.CURRENT_VERSION, written.get("configVersion").getAsInt(), "written for " + version);
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(List.of(file.getFileName()), files.map(Path::getFileName).toList(), "files for " + version);
            }
        }
    }

    @Test
    void newerConfigVersionLeavesFileAlone() throws IOException {
        Path file = dir.resolve("config.json");
        String newer = "{\"configVersion\": 2, \"chat\": {\"radius\": 32}, \"fromTheFuture\": true}";
        Files.writeString(file, newer);

        ConfigLoader.Result r = ConfigLoader.load(file, null, true);
        assertEquals(newer, Files.readString(file));
        assertEquals(32, r.config().chat.radius);
        assertTrue(r.warnings().contains("config is from a newer version, so it was not rewritten"));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file.getFileName()), files.map(Path::getFileName).toList());
        }
    }

    @Test
    void hugeNumberFallsBackForThatKeyOnly() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"diameter\":99999999999,\"endScale\":3.0}}");
        assertEquals(10000, r.config().worldBorder.diameter);
        assertEquals(3.0, r.config().worldBorder.endScale);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("worldBorder.diameter")));
    }

    @Test
    void hoverLinesReadAsAList() throws IOException {
        ConfigLoader.Result r = loadJson("{\"serverList\":{\"hoverLines\":[\"one\",\"two\"]}}");
        assertEquals(List.of("one", "two"), r.config().serverList.hoverLines);
        assertEquals(List.of(), loadJson("{\"serverList\":{\"hoverLines\":[]}}").config().serverList.hoverLines);
    }

    @Test
    void badHoverLinesFallBackToDefault() throws IOException {
        for (String bad : List.of("\"text\"", "[1, 2]", "[\"ok\", null]")) {
            ConfigLoader.Result r = loadJson("{\"serverList\":{\"hoverLines\":" + bad + "}}");
            assertEquals(new YoltConfig().serverList.hoverLines, r.config().serverList.hoverLines, bad);
            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("serverList.hoverLines")), bad);
        }
    }
}
