package bobbynooby.dev.config;

import bobbynooby.dev.core.Migrations;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
        // The refused value is the first warning; the second says the typed file was kept before the rewrite
        assertEquals(2, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("worldBorder.diameter"));
        assertEquals("old file saved as config.json.bak before the rewrite", r.warnings().get(1));
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

    // The timeout has to stay well inside vanilla's 30 second login limit
    @Test
    void skinKeysOutOfRangeFallBack() throws IOException {
        ConfigLoader.Result bad = loadJson("{\"skins\":{\"mode\":\"sometimes\",\"lookupTimeoutSeconds\":60,\"refreshHours\":-1}}");
        YoltConfig.SkinsSection skins = bad.config().skins;
        assertEquals(YoltConfig.SkinMode.AUTO, skins.mode);
        assertEquals(5, skins.lookupTimeoutSeconds);
        assertEquals(24, skins.refreshHours);
        for (String key : List.of("skins.mode", "skins.lookupTimeoutSeconds", "skins.refreshHours")) {
            assertTrue(bad.warnings().stream().anyMatch(w -> w.startsWith(key + ":")), "no warning for " + key);
        }
        assertEquals(5, loadJson("{\"skins\":{\"lookupTimeoutSeconds\":0}}").config().skins.lookupTimeoutSeconds);
        assertEquals(5, loadJson("{\"skins\":{\"lookupTimeoutSeconds\":11}}").config().skins.lookupTimeoutSeconds);

        YoltConfig.SkinsSection edges = loadJson("{\"skins\":{\"mode\":\"off\",\"lookupTimeoutSeconds\":10,\"refreshHours\":0}}")
                .config().skins;
        assertEquals(YoltConfig.SkinMode.OFF, edges.mode);
        assertEquals(10, edges.lookupTimeoutSeconds);
        assertEquals(0, edges.refreshHours);
    }

    @Test
    void graveKeysOutOfRangeFallBack() throws IOException {
        ConfigLoader.Result bad = loadJson("{\"graves\":{\"recheckSeconds\":-1,\"tellLocation\":\"sometimes\",\"tellLocationDistance\":-1}}");
        YoltConfig.GravesSection graves = bad.config().graves;
        assertEquals(5, graves.recheckSeconds);
        assertEquals(YoltConfig.TellLocation.WHEN_MOVED, graves.tellLocation);
        assertEquals(16, graves.tellLocationDistance);
        for (String key : List.of("graves.recheckSeconds", "graves.tellLocation", "graves.tellLocationDistance")) {
            assertTrue(bad.warnings().stream().anyMatch(w -> w.startsWith(key + ":")), "no warning for " + key);
        }

        // 0 turns the re-check off, and a distance of 0 tells of any move at all
        YoltConfig.GravesSection edges = loadJson("{\"graves\":{\"recheckSeconds\":0,\"tellLocation\":\"never\",\"tellLocationDistance\":0}}")
                .config().graves;
        assertEquals(0, edges.recheckSeconds);
        assertEquals(YoltConfig.TellLocation.NEVER, edges.tellLocation);
        assertEquals(0, edges.tellLocationDistance);
        assertEquals(YoltConfig.TellLocation.ALWAYS, loadJson("{\"graves\":{\"tellLocation\":\"always\"}}").config().graves.tellLocation);
        assertEquals(YoltConfig.TellLocation.WHEN_MOVED,
                loadJson("{\"graves\":{\"tellLocation\":\"whenMoved\"}}").config().graves.tellLocation);
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

        // As Settings.reload calls it, with the live config
        ConfigLoader.Result reload = ConfigLoader.load(file, new YoltConfig(), false);
        assertTrue(reload.unparseable());
        assertNull(reload.config());
        assertEquals(List.of("config could not be read (not UTF-8 text)"), reload.warnings());
        assertArrayEquals(bad, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve("config.json.bak")));

        ConfigLoader.Result startup = ConfigLoader.load(file, null, true);
        assertTrue(startup.unparseable());
        assertEquals(10000, startup.config().worldBorder.diameter);
        assertArrayEquals(bad, Files.readAllBytes(dir.resolve("config.json.bak")));
        assertEquals(List.of("config could not be read (not UTF-8 text), old file saved as config.json.bak, using defaults"),
                startup.warnings());
        assertEquals(10000, JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonObject("worldBorder").get("diameter").getAsInt());
    }

    @Test
    void badValueOnReloadKeepsLiveValue() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"worldBorder\":{\"diameter\":\"2OOOO\"},\"chat\":{\"radius\":99},\"deathLockout\":{\"cooldownDays\":-1}}");
        YoltConfig base = new YoltConfig();
        base.worldBorder.diameter = 20000;
        base.chat.mode = YoltConfig.ChatMode.GLOBAL;
        base.deathLockout.cooldownDays = 3;

        ConfigLoader.Result r = ConfigLoader.load(file, base, false);
        assertEquals(20000, r.config().worldBorder.diameter);
        assertEquals(99, r.config().chat.radius);
        // a key the file leaves out keeps the live value too
        assertEquals(YoltConfig.ChatMode.GLOBAL, r.config().chat.mode);
        // a value of the right type that fails its range check is kept the same way
        assertEquals(3, r.config().deathLockout.cooldownDays);
        assertEquals(2, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("worldBorder.diameter"));
        assertEquals("deathLockout.cooldownDays: must be 0 or more, using the current value", r.warnings().get(1));
        assertTrue(r.warnings().stream().allMatch(w -> w.endsWith("using the current value")));
    }

    @Test
    void missingFileOnReloadKeepsLiveConfig() {
        Path file = dir.resolve("config.json");
        YoltConfig base = new YoltConfig();
        base.chat.radius = 32;

        ConfigLoader.Result r = ConfigLoader.load(file, base, false);
        assertEquals(32, r.config().chat.radius);
        // /yolt reload tells "nothing was read" by getting the live config itself back
        assertSame(base, r.config());
        assertEquals(List.of("config file is missing, nothing changed"), r.warnings());
        assertFalse(r.unparseable());
        assertFalse(Files.exists(file));
    }

    @Test
    void infiniteEndScaleFallsBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"endScale\":1e400},\"chat\":{\"radius\":30}}");
        assertEquals(2.0, r.config().worldBorder.endScale);
        assertEquals(30, r.config().chat.radius);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("worldBorder.endScale")));
        // the rewrite would have thrown on an infinity
        assertTrue(Files.readString(dir.resolve("config.json")).contains("\"endScale\": 2.0"));
    }

    @Test
    void endBorderIsCappedAtVanillaMax() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"diameter\":59999968,\"endScale\":2.0}}");
        assertEquals(1.0, r.config().worldBorder.endScale);
        assertEquals(59999968, r.config().worldBorder.diameter);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("worldBorder.endScale") && w.contains("worldBorder.diameter")
                && w.contains(String.valueOf(ConfigLoader.MAX_BORDER_DIAMETER))));

        // 59999968 / 11 rounds up, so the plain quotient times 11 lands just past the maximum
        YoltConfig.WorldBorderSection odd = loadJson("{\"worldBorder\":{\"diameter\":11,\"endScale\":1e7}}").config().worldBorder;
        assertTrue(odd.diameter * odd.endScale <= ConfigLoader.MAX_BORDER_DIAMETER, "end border " + odd.diameter * odd.endScale);
        assertTrue(odd.diameter * odd.endScale > ConfigLoader.MAX_BORDER_DIAMETER - 1, "end border " + odd.diameter * odd.endScale);
    }

    // A startup leaves the world's own border alone when its border values aren't what the admin wrote
    @Test
    void borderProblemOnlyWhenBorderValuesFellBack() throws IOException {
        Path file = dir.resolve("config.json");
        Files.write(file, new byte[] {(byte) 0xC3, 0x28});
        assertEquals("config could not be read (not UTF-8 text)", ConfigLoader.load(file, null, true).borderProblem());
        assertTrue(loadJson("{not json").borderProblem().startsWith("config is not valid JSON ("));

        assertEquals("the config has a bad worldBorder.diameter", loadJson("{\"worldBorder\":{\"diameter\":\"2OOOO\"}}").borderProblem());
        assertEquals("the config has a bad worldBorder.endScale", loadJson("{\"worldBorder\":{\"endScale\":0}}").borderProblem());
        assertEquals("the config has a bad worldBorder.diameter and worldBorder.endScale",
                loadJson("{\"worldBorder\":{\"diameter\":\"x\",\"endScale\":-1}}").borderProblem());
        assertEquals("the config has a bad worldBorder", loadJson("{\"worldBorder\":5}").borderProblem());
        // A misspelt key leaves the real one at its default, so it counts too; one outside worldBorder doesn't
        assertEquals("the config has a bad worldBorder.diamter", loadJson("{\"worldBorder\":{\"diamter\":20000}}").borderProblem());
        assertNull(loadJson("{\"chat\":{\"radus\":30}}").borderProblem());

        // A bad key elsewhere, the End cap, a missing key and a first start with no file all keep the file's own border
        assertNull(loadJson("{\"chat\":{\"radius\":\"x\"}}").borderProblem());
        assertNull(loadJson("{\"worldBorder\":{\"diameter\":59999968,\"endScale\":2.0}}").borderProblem());
        assertNull(loadJson("{\"randomSpawn\":false}").borderProblem());
        Files.delete(file);
        assertNull(ConfigLoader.load(file, null, true).borderProblem());
    }

    @Test
    void endScaleZeroOrNegativeFallsBack() throws IOException {
        for (String bad : List.of("0", "-1.5")) {
            ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"endScale\":" + bad + "}}");
            assertEquals(2.0, r.config().worldBorder.endScale, bad);
            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("worldBorder.endScale")), bad);
        }
    }

    @Test
    void radiusBelowOneFallsBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"chat\":{\"radius\":0}}");
        assertEquals(48, r.config().chat.radius);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("chat.radius")));
    }

    @Test
    void diameterOutOfRangeFallsBack() throws IOException {
        for (String bad : List.of("0", "59999969")) {
            ConfigLoader.Result r = loadJson("{\"worldBorder\":{\"diameter\":" + bad + "}}");
            assertEquals(10000, r.config().worldBorder.diameter, bad);
            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("worldBorder.diameter")
                    && w.contains(String.valueOf(ConfigLoader.MAX_BORDER_DIAMETER))), bad);
        }
    }

    @Test
    void unknownKeyIsWarned() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldborder\":{},\"chat\":{\"colour\":1}}");
        assertTrue(r.warnings().contains("worldborder: unknown key, ignored"));
        assertTrue(r.warnings().contains("chat.colour: unknown key, ignored"));
    }

    @Test
    void nonObjectSectionFallsBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"chat\":5,\"graves\":{\"enabled\":false}}");
        assertEquals(48, r.config().chat.radius);
        assertFalse(r.config().graves.enabled);
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("chat: expected an object")));
    }

    // The startup rewrite leaves the refused 2.5 out, so the file as typed is kept first; a reload keeps no copy
    @Test
    void refusedValueIsBackedUpBeforeTheRewrite() throws IOException {
        Path file = dir.resolve("config.json");
        Path bak = dir.resolve("config.json.bak");
        byte[] typed = "{\"chat\": {\"radius\": 2.5}}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, typed);

        ConfigLoader.Result r = ConfigLoader.load(file, null, true);
        assertEquals(48, r.config().chat.radius);
        assertArrayEquals(typed, Files.readAllBytes(bak));
        assertFalse(Files.readString(file).contains("2.5"));
        assertTrue(r.warnings().contains("old file saved as config.json.bak before the rewrite"), r.warnings().toString());

        // The same file at the next start leaves one copy, still the typed bytes
        Files.write(file, typed);
        ConfigLoader.load(file, null, true);
        assertArrayEquals(typed, Files.readAllBytes(bak));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("config.json", "config.json.bak"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }

        Files.delete(bak);
        Files.write(file, typed);
        ConfigLoader.load(file, new YoltConfig(), false);
        assertFalse(Files.exists(bak));
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

    // Gson's own int reading turned 2.5 into 2 without a word, and threw on 1e-99999, which reset the whole config
    @Test
    void wholeNumberKeysRefuseFractionsAndNumbersGsonCantRead() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"chat\":{\"radius\":1e-99999},\"deathLockout\":{\"cooldownDays\":2.5},\"randomSpawn\":false}");
        YoltConfig base = new YoltConfig();
        base.chat.radius = 32;
        base.deathLockout.cooldownDays = 3;
        base.worldBorder.diameter = 20000;

        ConfigLoader.Result r = ConfigLoader.load(file, base, false);
        assertEquals(32, r.config().chat.radius);
        assertEquals(3, r.config().deathLockout.cooldownDays);
        assertFalse(r.config().randomSpawn);
        assertEquals(20000, r.config().worldBorder.diameter);
        assertEquals(List.of("deathLockout.cooldownDays: expected a whole number that fits in an int, using the current value",
                "chat.radius: expected a whole number that fits in an int, using the current value"), r.warnings());
    }

    @Test
    void hoverLinesReadAsAList() throws IOException {
        ConfigLoader.Result r = loadJson("{\"serverList\":{\"hoverLines\":[\"one\",\"two\"]}}");
        assertEquals(List.of("one", "two"), r.config().serverList.hoverLines);
        assertEquals(List.of(), loadJson("{\"serverList\":{\"hoverLines\":[]}}").config().serverList.hoverLines);
    }

    // One warning however much is cut, and a list that fits is left alone
    @Test
    void hoverLinesAreCapped() throws IOException {
        List<String> typed = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            typed.add(i == 1 ? "x".repeat(300) : "line" + i);
        }
        ConfigLoader.Result r = loadJson("{\"serverList\":{\"hoverLines\":" + new Gson().toJson(typed) + "}}");
        List<String> lines = r.config().serverList.hoverLines;
        assertEquals(ConfigChecks.MAX_HOVER_LINES, lines.size());
        assertEquals("line0", lines.get(0));
        assertEquals("x".repeat(ConfigChecks.MAX_HOVER_LINE_LENGTH), lines.get(1));
        assertEquals("line11", lines.get(11));
        assertEquals(1, r.warnings().stream().filter(w -> w.startsWith("serverList.hoverLines")).count(), r.warnings().toString());

        List<String> fits = Collections.nCopies(ConfigChecks.MAX_HOVER_LINES, "y".repeat(ConfigChecks.MAX_HOVER_LINE_LENGTH));
        ConfigLoader.Result fitting = loadJson("{\"serverList\":{\"hoverLines\":" + new Gson().toJson(fits) + "}}");
        assertEquals(fits, fitting.config().serverList.hoverLines);
        assertTrue(fitting.warnings().isEmpty(), fitting.warnings().toString());
    }

    // A cut never leaves half of a character such as an emoji
    @Test
    void hoverLineCutKeepsWholeCharacters() throws IOException {
        String line = "a".repeat(ConfigChecks.MAX_HOVER_LINE_LENGTH - 1) + "\uD83D\uDE00tail";
        ConfigLoader.Result r = loadJson("{\"serverList\":{\"hoverLines\":" + new Gson().toJson(List.of(line)) + "}}");
        assertEquals("a".repeat(ConfigChecks.MAX_HOVER_LINE_LENGTH - 1), r.config().serverList.hoverLines.get(0));
    }

    @Test
    void badHoverLinesFallBackToDefault() throws IOException {
        for (String bad : List.of("\"text\"", "[1, 2]", "[\"ok\", null]")) {
            ConfigLoader.Result r = loadJson("{\"serverList\":{\"hoverLines\":" + bad + "}}");
            assertEquals(new YoltConfig().serverList.hoverLines, r.config().serverList.hoverLines, bad);
            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("serverList.hoverLines")), bad);
        }
    }

    private static ConfigKey key(String path) {
        return ConfigKeys.find(path).orElseThrow(() -> new AssertionError("no key " + path));
    }

    private static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    // The file has an edit not yet reloaded and a key this build doesn't know; setting another key keeps both
    @Test
    void setKeyChangesOnlyThatKeyInFile() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"chat\": {\"radius\": 20}, \"worldBorder\": {\"diameter\": 10000}, \"fromTheFuture\": 1}");
        YoltConfig live = new YoltConfig();

        ConfigLoader.Change change = ConfigLoader.setKey(file, live, key("worldBorder.diameter"), new JsonPrimitive(8000));
        assertNull(change.problem());
        assertEquals("10000", change.oldValue());
        assertEquals("8000", change.newValue());
        assertEquals(48, change.config().chat.radius);
        assertEquals(8000, change.config().worldBorder.diameter);
        assertEquals(10000, live.worldBorder.diameter, "the live config was changed in place");

        JsonObject written = readJson(file);
        assertEquals(20, written.getAsJsonObject("chat").get("radius").getAsInt());
        assertEquals(8000, written.getAsJsonObject("worldBorder").get("diameter").getAsInt());
        assertEquals(1, written.get("fromTheFuture").getAsInt());
        // Nothing the file left out is filled in
        assertFalse(written.has("hide"));
    }

    @Test
    void setKeyRefusesOutOfRange() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] before = "{\"worldBorder\": {\"diameter\": 10000}}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);

        ConfigLoader.Change change = ConfigLoader.setKey(file, new YoltConfig(), key("worldBorder.diameter"), new JsonPrimitive(0));
        assertEquals("worldBorder.diameter: must be 1 to 59999968", change.problem());
        assertNull(change.config());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void setKeyOnUnparseableFileWritesWholeConfig() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{");

        ConfigLoader.Change change = ConfigLoader.setKey(file, new YoltConfig(), key("worldBorder.diameter"), new JsonPrimitive(8000));
        assertNull(change.problem());
        assertEquals("{", Files.readString(dir.resolve("config.json.bak")));
        assertEquals(new Gson().toJsonTree(change.config()), readJson(file));
        assertEquals(8000, readJson(file).getAsJsonObject("worldBorder").get("diameter").getAsInt());
    }

    // The End cap's warning starts with endScale but names the diameter too, so either change that hits it is refused
    // rather than quietly moving the End border
    @Test
    void setKeyRefusesWhatTheEndCapWouldChange() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] before = "{}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        String capped = "worldBorder.endScale: worldBorder.diameter times endScale can't pass 59999968";

        ConfigLoader.Change diameter = ConfigLoader.setKey(file, new YoltConfig(), key("worldBorder.diameter"),
                new JsonPrimitive(ConfigLoader.MAX_BORDER_DIAMETER));
        assertEquals(capped, diameter.problem());
        assertNull(diameter.config());
        ConfigLoader.Change endScale = ConfigLoader.setKey(file, new YoltConfig(), key("worldBorder.endScale"), new JsonPrimitive(1e7));
        assertEquals(capped, endScale.problem());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    // What Settings.set does before it swaps the live config
    @Test
    void setTextRefusesBadTextAndSkipsTheSameValue() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] before = "{\"chat\": {\"radius\": 20}}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        YoltConfig live = new YoltConfig();

        ConfigLoader.Change bad = ConfigLoader.setText(file, live, key("chat.radius"), "abc");
        assertEquals(new ConfigLoader.Change(null, "48", "abc", "chat.radius takes a whole number, not 'abc'."), bad);
        // 48 is the live value, whatever the file says, so nothing is written
        assertEquals(new ConfigLoader.Change(null, "48", "48", null), ConfigLoader.setText(file, live, key("chat.radius"), "48"));
        assertEquals(new ConfigLoader.Change(null, "2.0", "2.0", null),
                ConfigLoader.setText(file, live, key("worldBorder.endScale"), "2"));
        assertArrayEquals(before, Files.readAllBytes(file));

        ConfigLoader.Change set = ConfigLoader.setText(file, live, key("chat.mode"), "global");
        assertNull(set.problem());
        assertEquals("proximity", set.oldValue());
        assertEquals("global", set.newValue());
        assertEquals(YoltConfig.ChatMode.GLOBAL, set.config().chat.mode);
        assertEquals("global", readJson(file).getAsJsonObject("chat").get("mode").getAsString());
        assertEquals(20, readJson(file).getAsJsonObject("chat").get("radius").getAsInt());
    }
}
