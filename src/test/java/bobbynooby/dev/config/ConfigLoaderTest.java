package bobbynooby.dev.config;

import bobbynooby.dev.core.Migrations;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    // The void search has no first search before it, so it goes down to 1
    @Test
    void graveSearchRadiiOutOfRangeFallBack() throws IOException {
        for (int bad : new int[] {2, 33}) {
            ConfigLoader.Result r = loadJson("{\"graves\":{\"searchRadius\":" + bad + "}}");
            assertEquals(16, r.config().graves.searchRadius, "searchRadius " + bad);
            assertTrue(r.warnings().contains("graves.searchRadius: must be 3 to 32, using default"), r.warnings().toString());
        }
        for (int bad : new int[] {0, 33}) {
            ConfigLoader.Result r = loadJson("{\"graves\":{\"voidSearchRadius\":" + bad + "}}");
            assertEquals(16, r.config().graves.voidSearchRadius, "voidSearchRadius " + bad);
            assertTrue(r.warnings().contains("graves.voidSearchRadius: must be 1 to 32, using default"), r.warnings().toString());
        }
        YoltConfig.GravesSection edges = loadJson("{\"graves\":{\"searchRadius\":3,\"voidSearchRadius\":32}}").config().graves;
        assertEquals(3, edges.searchRadius);
        assertEquals(32, edges.voidSearchRadius);
        edges = loadJson("{\"graves\":{\"searchRadius\":32,\"voidSearchRadius\":1}}").config().graves;
        assertEquals(32, edges.searchRadius);
        assertEquals(1, edges.voidSearchRadius);

        // A reload keeps the live radius
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"graves\":{\"searchRadius\":33}}");
        YoltConfig live = new YoltConfig();
        live.graves.searchRadius = 10;
        ConfigLoader.Result reloaded = ConfigLoader.load(file, live, false);
        assertEquals(10, reloaded.config().graves.searchRadius);
        assertEquals(List.of("graves.searchRadius: must be 3 to 32, using the current value"), reloaded.warnings());
    }

    @Test
    void negativeWhisperRadiusFallsBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"chat\":{\"whisperRadius\":-1}}");
        assertEquals(0, r.config().chat.whisperRadius);
        assertTrue(r.warnings().contains("chat.whisperRadius: must be 0 or more, using default"), r.warnings().toString());
        assertEquals(0, loadJson("{\"chat\":{\"whisperRadius\":0}}").config().chat.whisperRadius);
        assertEquals(10, loadJson("{\"chat\":{\"whisperRadius\":10}}").config().chat.whisperRadius);
    }

    @Test
    void negativeShownMaxPlayersFallsBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"serverList\":{\"shownMaxPlayers\":-1}}");
        assertEquals(1, r.config().serverList.shownMaxPlayers);
        assertTrue(r.warnings().contains("serverList.shownMaxPlayers: must be 0 or more, using default"), r.warnings().toString());
        assertEquals(0, loadJson("{\"serverList\":{\"shownMaxPlayers\":0}}").config().serverList.shownMaxPlayers);
        assertEquals(50, loadJson("{\"serverList\":{\"shownMaxPlayers\":50}}").config().serverList.shownMaxPlayers);
    }

    // On a reload a bad value of each of the five keys Task 30b added keeps the live one, each with its own warning
    @Test
    void newKeysKeepTheCurrentValueOnReload() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"graves\":{\"searchRadius\":33,\"voidSearchRadius\":0},\"chat\":{\"whisperRadius\":-1},"
                + "\"serverList\":{\"shownMaxPlayers\":-1},\"hide\":{\"tabHeaderLines\":\"one line\"}}");
        YoltConfig live = new YoltConfig();
        live.graves.searchRadius = 10;
        live.graves.voidSearchRadius = 5;
        live.chat.whisperRadius = 8;
        live.serverList.shownMaxPlayers = 20;
        live.hide.tabHeaderLines = List.of("the live line");

        ConfigLoader.Result r = ConfigLoader.load(file, live, false);
        assertEquals(10, r.config().graves.searchRadius);
        assertEquals(5, r.config().graves.voidSearchRadius);
        assertEquals(8, r.config().chat.whisperRadius);
        assertEquals(20, r.config().serverList.shownMaxPlayers);
        assertEquals(List.of("the live line"), r.config().hide.tabHeaderLines);
        List<String> expected = List.of("graves.searchRadius: must be 3 to 32, using the current value",
                "graves.voidSearchRadius: must be 1 to 32, using the current value",
                "chat.whisperRadius: must be 0 or more, using the current value",
                "serverList.shownMaxPlayers: must be 0 or more, using the current value",
                "hide.tabHeaderLines: expected a list of strings, using the current value");
        for (String warning : expected) {
            assertTrue(r.warnings().contains(warning), "no \"" + warning + "\" in " + r.warnings());
        }
        assertEquals(expected.size(), r.warnings().size(), r.warnings().toString());
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

    // merge checks every type first, so only a tree it would never make gets here. The whole config then falls back as
    // one bad key does: on a reload to a copy of the live config, not to every default.
    @Test
    void unreadableMergedConfigFallsBackToACopyOfTheFallback() {
        YoltConfig live = new YoltConfig();
        live.chat.radius = 20;
        live.worldBorder.diameter = 20000;
        JsonObject merged = new Gson().toJsonTree(live).getAsJsonObject();
        merged.getAsJsonObject("worldBorder").addProperty("diameter", "big");

        ConfigLoader.Read read = ConfigLoader.read(merged, live);
        assertEquals(20, read.config().chat.radius);
        assertEquals(20000, read.config().worldBorder.diameter);
        assertNotSame(live, read.config());
        assertNotSame(live.chat, read.config().chat);
        assertTrue(read.problem().startsWith("config could not be read ("), read.problem());

        ConfigLoader.Read good = ConfigLoader.read(new Gson().toJsonTree(live).getAsJsonObject(), new YoltConfig());
        assertEquals(20, good.config().chat.radius);
        assertNull(good.problem());
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

    // On a reload a bad diameter keeps the live one, and the End cap works from that kept diameter: half of vanilla's
    // largest border caps endScale at exactly 2. The default diameter would leave 3 alone.
    @Test
    void endCapOnReloadUsesTheKeptLiveDiameter() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"worldBorder\":{\"diameter\":0,\"endScale\":3.0}}");
        YoltConfig live = new YoltConfig();
        live.worldBorder.diameter = ConfigLoader.MAX_BORDER_DIAMETER / 2;
        live.worldBorder.endScale = 1.0;

        ConfigLoader.Result r = ConfigLoader.load(file, live, false);
        assertEquals(ConfigLoader.MAX_BORDER_DIAMETER / 2, r.config().worldBorder.diameter);
        assertEquals(2.0, r.config().worldBorder.endScale);
        assertEquals(List.of("worldBorder.diameter: must be 1 to 59999968, using the current value",
                "worldBorder.endScale: worldBorder.diameter times endScale can't pass 59999968, using 2.0"), r.warnings());
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

    // A directory where the temp file goes makes the rewrite fail, as a read-only config folder does. The file was
    // read, so the start goes on with it and says so.
    @Test
    void startupThatCantRewriteTheFileKeepsWhatItRead() throws IOException {
        Path file = dir.resolve("config.json");
        String text = "{\"worldBorder\":{\"diameter\":5000}}";
        Files.writeString(file, text);
        Path tmp = dir.resolve("config.json.tmp");
        Files.createDirectory(tmp);
        Files.writeString(tmp.resolve("keep"), "so the directory can't be deleted");

        ConfigLoader.Result r = assertDoesNotThrow(() -> ConfigLoader.load(file, null, true));
        assertEquals(5000, r.config().worldBorder.diameter);
        assertFalse(r.rewritten());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).startsWith("The config file could not be rewritten ("), r.warnings().get(0));
        assertTrue(r.warnings().get(0).endsWith("), so it is left as it was."), r.warnings().get(0));
        assertEquals(text, Files.readString(file));
    }

    // A read-only folder, where the copy kept before a rewrite can't be made. Skipped where the folder stays writable
    // (as for root).
    private void inReadOnlyDir(Runnable test) {
        assumeTrue(dir.toFile().setWritable(false) && !Files.isWritable(dir), "the folder can't be made read-only here");
        try {
            test.run();
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    // A bad value needs the copy first, so with no copy the file isn't rewritten: what was typed stays, and the start
    // goes on with what was read
    @Test
    void startupThatCantCopyABadValuesFileKeepsWhatItRead() throws IOException {
        Path file = dir.resolve("config.json");
        String text = "{\"worldBorder\":{\"diameter\":5000},\"chat\":{\"radius\":2.5}}";
        Files.writeString(file, text);
        inReadOnlyDir(() -> {
            ConfigLoader.Result r = assertDoesNotThrow(() -> ConfigLoader.load(file, null, true));
            assertEquals(5000, r.config().worldBorder.diameter);
            assertEquals(48, r.config().chat.radius);
            assertFalse(r.rewritten());
            assertNull(r.backup());
            assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("The config file could not be copied before the rewrite (")
                    && w.endsWith("), so it is left as it was.")), r.warnings().toString());
        });
        assertEquals(text, Files.readString(file));
    }

    // The same for an older file, which is read as upgraded but left as it was
    @Test
    void startupThatCantCopyAnOlderFileKeepsWhatItRead() throws IOException {
        Path file = dir.resolve("config.json");
        String old = "{\"configVersion\": 1, \"spawnRandomly\": false}";
        Files.writeString(file, old);
        List<Migrations.Step<JsonObject>> steps = List.of(new Migrations.Step<JsonObject>(1, json -> {
            json.add("randomSpawn", json.remove("spawnRandomly"));
            return json;
        }));
        inReadOnlyDir(() -> {
            ConfigLoader.Result r = assertDoesNotThrow(() -> ConfigLoader.load(file, null, true, 2, steps));
            assertFalse(r.config().randomSpawn);
            assertFalse(r.rewritten());
            assertTrue(r.warnings().contains("config was v1 and is upgraded to v2"), r.warnings().toString());
            assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("The config file could not be copied before the rewrite (")),
                    r.warnings().toString());
        });
        assertEquals(old, Files.readString(file));
        assertFalse(Files.exists(dir.resolve("config.json.v1.bak")));
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
        assertTrue(Files.readString(file).contains("\"configVersion\": " + ConfigLoader.CURRENT_VERSION));
    }

    // A step of the test's own that renames a key, as a later version's step might
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

    // Only a damaged file has these, so they read as a missing version does, as the current one, with a warning that
    // names the value: no backup and no upgrade
    @Test
    void zeroOrNegativeConfigVersionReadsAsMissing() throws IOException {
        Path file = dir.resolve("config.json");
        for (String version : List.of("0", "-1", "-2147483648")) {
            Files.writeString(file, "{\"configVersion\": " + version + ", \"randomSpawn\": false}");
            ConfigLoader.Result r = ConfigLoader.load(file, null, true);
            assertFalse(r.config().randomSpawn);
            assertEquals(List.of("configVersion: " + version + " is not a version, read as version " + ConfigLoader.CURRENT_VERSION),
                    r.warnings(),
                    "warnings for " + version);
            JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals(ConfigLoader.CURRENT_VERSION, written.get("configVersion").getAsInt(), "written for " + version);
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(List.of(file.getFileName()), files.map(Path::getFileName).toList(), "files for " + version);
            }
        }
    }

    // One past int max can't be a version either, but merge already refuses it as it would any int key: one warning,
    // and like any refused value the typed file is kept
    @Test
    void configVersionAboveIntMaxIsWarnedOnceByMerge() throws IOException {
        Path file = dir.resolve("config.json");
        String typed = "{\"configVersion\": 2147483648, \"randomSpawn\": false}";
        Files.writeString(file, typed);

        ConfigLoader.Result r = ConfigLoader.load(file, null, true);
        assertFalse(r.config().randomSpawn);
        assertEquals(List.of("configVersion: expected a whole number that fits in an int, using default",
                "old file saved as config.json.bak before the rewrite"), r.warnings());
        assertEquals(typed, Files.readString(dir.resolve("config.json.bak")));
        assertEquals(ConfigLoader.CURRENT_VERSION, readJson(file).get("configVersion").getAsInt());
    }

    @Test
    void newerConfigVersionLeavesFileAlone() throws IOException {
        Path file = dir.resolve("config.json");
        String newer = "{\"configVersion\": " + (ConfigLoader.CURRENT_VERSION + 1) + ", \"chat\": {\"radius\": 32}, \"fromTheFuture\": true}";
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

    // The tab header list is cut the same way, with room for more lines, and the 13 lines YOLT ships with fit
    @Test
    void tabHeaderLinesAreCapped() throws IOException {
        List<String> typed = new ArrayList<>();
        for (int i = 0; i < ConfigChecks.MAX_TAB_HEADER_LINES + 2; i++) {
            typed.add(i == 1 ? "x".repeat(300) : "line" + i);
        }
        ConfigLoader.Result r = loadJson("{\"hide\":{\"tabHeaderLines\":" + new Gson().toJson(typed) + "}}");
        List<String> lines = r.config().hide.tabHeaderLines;
        assertEquals(ConfigChecks.MAX_TAB_HEADER_LINES, lines.size());
        assertEquals("line0", lines.get(0));
        assertEquals("x".repeat(ConfigChecks.MAX_TAB_HEADER_LINE_LENGTH), lines.get(1));
        assertEquals(List.of("hide.tabHeaderLines: at most 64 lines of 256 characters each, the rest was dropped"),
                r.warnings().stream().filter(w -> w.startsWith("hide.tabHeaderLines")).toList());

        ConfigLoader.Result shipped = loadJson("{}");
        assertEquals(13, shipped.config().hide.tabHeaderLines.size());
        assertTrue(shipped.warnings().isEmpty(), shipped.warnings().toString());
        // The rewrite keeps an apostrophe as typed
        assertTrue(Files.readString(dir.resolve("config.json"), StandardCharsets.UTF_8).contains("You're not sure"));
        assertEquals(List.of(), loadJson("{\"hide\":{\"tabHeaderLines\":[]}}").config().hide.tabHeaderLines);
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

    @Test
    void badTabHeaderLinesFallBackToDefault() throws IOException {
        for (String bad : List.of("\"text\"", "[1, 2]", "[\"ok\", null]")) {
            ConfigLoader.Result r = loadJson("{\"hide\":{\"tabHeaderLines\":" + bad + "}}");
            assertEquals(new YoltConfig().hide.tabHeaderLines, r.config().hide.tabHeaderLines, bad);
            assertTrue(r.warnings().contains("hide.tabHeaderLines: expected a list of strings, using default"), bad);
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
        assertNull(change.backup());

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
        assertEquals("config.json.bak", change.backup());
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

    // What Settings.set does before it swaps the live config. The file holds 2 for endScale, which is the live 2.0.
    @Test
    void setTextRefusesBadTextAndSkipsTheSameValue() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] before = "{\"chat\": {\"radius\": 20, \"mode\": \"proximity\"}, \"worldBorder\": {\"endScale\": 2}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        YoltConfig live = new YoltConfig();

        ConfigLoader.Change bad = ConfigLoader.setText(file, live, key("chat.radius"), "abc");
        assertEquals(new ConfigLoader.Change(null, "48", "abc", "chat.radius takes a whole number, not 'abc'.", null), bad);
        // The live config and the file both have these already, so nothing is written
        assertEquals(new ConfigLoader.Change(null, "2.0", "2.0", null, null),
                ConfigLoader.setText(file, live, key("worldBorder.endScale"), "2"));
        assertEquals(new ConfigLoader.Change(null, "proximity", "proximity", null, null),
                ConfigLoader.setText(file, live, key("chat.mode"), "proximity"));
        assertArrayEquals(before, Files.readAllBytes(file));

        ConfigLoader.Change set = ConfigLoader.setText(file, live, key("chat.mode"), "global");
        assertNull(set.problem());
        assertEquals("proximity", set.oldValue());
        assertEquals("global", set.newValue());
        assertEquals(YoltConfig.ChatMode.GLOBAL, set.config().chat.mode);
        assertEquals("global", readJson(file).getAsJsonObject("chat").get("mode").getAsString());
        assertEquals(20, readJson(file).getAsJsonObject("chat").get("radius").getAsInt());
    }

    // The live config has 48 already, but the file says 20, which the next load would bring back. So 48 is written, and
    // only once both agree is the same value skipped.
    @Test
    void setTextWritesTheLiveValueWhenTheFileHasAnother() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"chat\": {\"radius\": 20}, \"fromTheFuture\": 1}");
        YoltConfig live = new YoltConfig();

        ConfigLoader.Change written = ConfigLoader.setText(file, live, key("chat.radius"), "48");
        assertNull(written.problem());
        assertNotNull(written.config());
        assertEquals("48", written.oldValue());
        assertEquals("48", written.newValue());
        assertEquals(48, readJson(file).getAsJsonObject("chat").get("radius").getAsInt());
        assertEquals(1, readJson(file).get("fromTheFuture").getAsInt());

        byte[] after = Files.readAllBytes(file);
        assertEquals(new ConfigLoader.Change(null, "48", "48", null, null), ConfigLoader.setText(file, live, key("chat.radius"), "48"));
        assertArrayEquals(after, Files.readAllBytes(file));

        // A key the file leaves out is written too, at the live value
        ConfigLoader.Change missing = ConfigLoader.setText(file, live, key("chat.allowWhispers"), "true");
        assertNotNull(missing.config());
        assertEquals("true", missing.oldValue());
        assertTrue(readJson(file).getAsJsonObject("chat").get("allowWhispers").getAsBoolean());

        // The other way round: the file's 20 isn't live yet, so setting 20 changes the live config
        Files.writeString(file, "{\"chat\": {\"radius\": 20}}");
        ConfigLoader.Change toFileValue = ConfigLoader.setText(file, live, key("chat.radius"), "20");
        assertEquals(20, toFileValue.config().chat.radius);
        assertEquals("48", toFileValue.oldValue());
        assertEquals("20", toFileValue.newValue());
    }

    // A command's own check, such as who a border would leave outside, sees the new config and can refuse it before the
    // file is touched. A value the key's own checks refuse never reaches it.
    @Test
    void vetoRefusesTheChangeBeforeTheWrite() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] before = "{}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        List<Integer> seen = new ArrayList<>();

        ConfigLoader.Change refused = ConfigLoader.setText(file, new YoltConfig(), key("worldBorder.diameter"), "500", next -> {
            seen.add(next.worldBorder.diameter);
            return "too small";
        });
        assertEquals(new ConfigLoader.Change(null, "10000", "500", "too small", null), refused);
        assertEquals(List.of(500), seen);
        assertArrayEquals(before, Files.readAllBytes(file));

        ConfigLoader.Change outOfRange = ConfigLoader.setText(file, new YoltConfig(), key("worldBorder.diameter"), "0",
                next -> fail("the veto saw a value the checks refuse"));
        assertEquals("worldBorder.diameter: must be 1 to 59999968", outOfRange.problem());

        ConfigLoader.Change allowed = ConfigLoader.setText(file, new YoltConfig(), key("worldBorder.diameter"), "500", next -> null);
        assertEquals(500, allowed.config().worldBorder.diameter);
        assertEquals(500, readJson(file).getAsJsonObject("worldBorder").get("diameter").getAsInt());
    }

    private List<Path> timedCopies() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return new ArrayList<>(files.filter(path -> path.getFileName().toString().matches("config\\.json\\.bak-\\d+"))
                    .sorted().toList());
        }
    }

    // A .bak can hold the admin's whole config from an earlier start, so a different file never goes over it: it is kept
    // beside it with the time added, and the same file again makes no new copy
    @Test
    void backupNeverReplacesADifferentCopy() throws IOException {
        Path file = dir.resolve("config.json");
        Path bak = dir.resolve("config.json.bak");
        byte[] first = "{\"chat\": {\"radius\": 2.5}}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"chat\": {\"radius\": 3.5}}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, first);
        ConfigLoader.load(file, null, true);
        assertArrayEquals(first, Files.readAllBytes(bak));

        Files.write(file, second);
        ConfigLoader.Result refused = ConfigLoader.load(file, null, true);
        assertArrayEquals(first, Files.readAllBytes(bak));
        List<Path> timed = timedCopies();
        assertEquals(1, timed.size(), timed.toString());
        assertArrayEquals(second, Files.readAllBytes(timed.get(0)));
        String savedSecond = "old file saved as " + timed.get(0).getFileName() + " before the rewrite";
        assertTrue(refused.warnings().contains(savedSecond), refused.warnings().toString());

        Files.write(file, second);
        assertTrue(ConfigLoader.load(file, null, true).warnings().contains(savedSecond));
        assertEquals(timed, timedCopies());

        // A file that isn't JSON, at startup or under a key being set, keeps its copy the same way
        Files.writeString(file, "{not json");
        ConfigLoader.Result broken = ConfigLoader.load(file, null, true);
        List<Path> brokenCopies = timedCopies();
        brokenCopies.removeAll(timed);
        assertEquals(1, brokenCopies.size(), brokenCopies.toString());
        assertEquals("{not json", Files.readString(brokenCopies.get(0)));
        assertTrue(broken.warnings().get(0).endsWith(", old file saved as " + brokenCopies.get(0).getFileName() + ", using defaults"),
                broken.warnings().toString());

        Files.writeString(file, "{");
        ConfigLoader.Change set = ConfigLoader.setKey(file, new YoltConfig(), key("chat.radius"), new JsonPrimitive(20));
        List<Path> setCopies = timedCopies();
        setCopies.removeAll(timed);
        setCopies.removeAll(brokenCopies);
        assertEquals(1, setCopies.size(), setCopies.toString());
        assertEquals(setCopies.get(0).getFileName().toString(), set.backup());
        assertEquals("{", Files.readString(setCopies.get(0)));
        assertArrayEquals(first, Files.readAllBytes(bak));
    }

    // /yolt reload's held-border reply says where the typo is: still in a newer file, or written over with the copy the
    // startup kept
    @Test
    void resultSaysWhetherTheFileWasRewrittenAndWhereItsCopyIs() throws IOException {
        Path file = dir.resolve("config.json");
        String typo = "{\"worldBorder\": {\"diameter\": \"2OOOO\"}}";
        Files.writeString(file, typo);
        ConfigLoader.Result startup = ConfigLoader.load(file, null, true);
        assertTrue(startup.rewritten());
        assertEquals("config.json.bak", startup.backup());

        Files.writeString(file, typo);
        ConfigLoader.Result reload = ConfigLoader.load(file, startup.config(), false);
        assertTrue(reload.rewritten());
        assertNull(reload.backup());

        String newer = "{\"configVersion\": " + (ConfigLoader.CURRENT_VERSION + 1) + ", \"worldBorder\": {\"diameter\": \"2OOOO\"}}";
        Files.writeString(file, newer);
        ConfigLoader.Result newerStartup = ConfigLoader.load(file, null, true);
        assertNotNull(newerStartup.borderProblem());
        assertFalse(newerStartup.rewritten());
        assertNull(newerStartup.backup());
        assertFalse(ConfigLoader.load(file, newerStartup.config(), false).rewritten());
        assertEquals(newer, Files.readString(file));

        Files.writeString(file, "{not json");
        ConfigLoader.Result broken = ConfigLoader.load(file, null, true);
        assertTrue(broken.rewritten());
        assertNotNull(broken.backup());
        assertEquals("{not json", Files.readString(dir.resolve(broken.backup())));
        Files.writeString(file, "{not json");
        assertFalse(ConfigLoader.load(file, broken.config(), false).rewritten());
        Files.delete(file);
        assertFalse(ConfigLoader.load(file, broken.config(), false).rewritten());
        assertTrue(ConfigLoader.load(file, null, true).rewritten());
    }

    // A misspelt section leaves every border key at its default just as a misspelt key does, so a startup holds the border
    @Test
    void misspeltBorderSectionIsABorderProblem() throws IOException {
        ConfigLoader.Result r = loadJson("{\"worldborder\": {\"diameter\": 20000}}");
        assertEquals("the config has a bad worldborder", r.borderProblem());
        assertEquals(10000, r.config().worldBorder.diameter);
        assertTrue(r.warnings().contains("worldborder: unknown key, ignored"), r.warnings().toString());
        assertEquals("the config has a bad WORLDBORDER", loadJson("{\"WORLDBORDER\": {}}").borderProblem());
        // Only that section's name: another unknown top-level key leaves the border alone
        assertNull(loadJson("{\"worldBorderX\": {}, \"chatt\": {}}").borderProblem());
    }

    // graves.tellLocation is the start of graves.tellLocationDistance, so only a warning naming the whole key refuses it.
    // A live config always passes its checks, so the other key's warning is set up by giving the live one a bad value.
    @Test
    void setKeyIgnoresAWarningForALongerKeyName() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{}");
        YoltConfig live = new YoltConfig();
        live.graves.tellLocationDistance = -1;

        ConfigLoader.Change change = ConfigLoader.setKey(file, live, key("graves.tellLocation"), new JsonPrimitive("always"));
        assertNull(change.problem());
        assertEquals(YoltConfig.TellLocation.ALWAYS, change.config().graves.tellLocation);
        assertEquals("always", readJson(file).getAsJsonObject("graves").get("tellLocation").getAsString());

        ConfigLoader.Change distance = ConfigLoader.setKey(file, live, key("graves.tellLocationDistance"), new JsonPrimitive(-2));
        assertEquals("graves.tellLocationDistance: must be 0 or more", distance.problem());
    }

    // A key name can end a sentence, and only the whole name counts: not one that goes on with more letters or another part
    @Test
    void warningNamesAKeyThatEndsASentence() {
        assertTrue(ConfigLoader.names("set worldBorder.diameter.", "worldBorder.diameter"));
        assertTrue(ConfigLoader.names("worldBorder.diameter: must be 1 to 59999968", "worldBorder.diameter"));
        assertTrue(ConfigLoader.names("worldBorder.endScale: worldBorder.diameter times endScale can't pass 59999968",
                "worldBorder.diameter"));
        assertFalse(ConfigLoader.names("graves.tellLocationDistance: must be 0 or more", "graves.tellLocation"));
        assertFalse(ConfigLoader.names("graves.tellLocation.extra: unknown key, ignored", "graves.tellLocation"));
        assertFalse(ConfigLoader.names("old.graves.tellLocation: unknown key, ignored", "graves.tellLocation"));
    }

    // The copy is made before the write, so when the write then fails the admin is still told where their file went. The
    // write goes through a temp file, which can't be made where a folder is.
    @Test
    void setKeyNamesTheCopyWhenTheRewriteFails() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{");
        Files.createDirectories(dir.resolve("config.json.tmp").resolve("inside"));

        ConfigLoader.Change change = ConfigLoader.setKey(file, new YoltConfig(), key("worldBorder.diameter"), new JsonPrimitive(8000));
        assertNull(change.config());
        assertTrue(change.problem().startsWith("the config file could not be saved ("), change.problem());
        assertEquals("config.json.bak", change.backup());
        assertEquals("{", Files.readString(dir.resolve("config.json.bak")));
        assertEquals("{", Files.readString(file));
    }

    // A reload whose rewrite fails still gives back what it read, and says the file is as it was. The write goes through
    // a temp file, which can't be made where a folder is.
    @Test
    void reloadThatCantRewriteTheFileStillApplies() throws IOException {
        Path file = dir.resolve("config.json");
        String typed = "{\"chat\": {\"radius\": 20}}";
        Files.writeString(file, typed);
        Files.createDirectories(dir.resolve("config.json.tmp").resolve("inside"));

        ConfigLoader.Result r = ConfigLoader.load(file, new YoltConfig(), false);
        assertEquals(20, r.config().chat.radius);
        assertFalse(r.unparseable());
        assertFalse(r.rewritten());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        String warning = r.warnings().get(0);
        assertTrue(warning.startsWith("The config file could not be rewritten (") && warning.endsWith("), so it is left as it was."),
                warning);
        assertEquals(typed, Files.readString(file));
    }

    // A .bak or timed copy that can't be compared, here a folder, counts as different: the startup keeps the typed file in
    // a new timed copy rather than stopping
    @Test
    void backupThatCantBeComparedCountsAsDifferent() throws IOException {
        Path file = dir.resolve("config.json");
        Files.createDirectory(dir.resolve("config.json.bak"));
        Path folderCopy = Files.createDirectory(dir.resolve("config.json.bak-5"));
        byte[] typed = "{\"chat\": {\"radius\": 2.5}}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, typed);

        ConfigLoader.Result r = ConfigLoader.load(file, null, true);
        assertEquals(48, r.config().chat.radius);
        List<Path> copies = timedCopies();
        copies.remove(folderCopy);
        assertEquals(1, copies.size(), copies.toString());
        assertArrayEquals(typed, Files.readAllBytes(copies.get(0)));
        assertEquals(copies.get(0).getFileName().toString(), r.backup());
        assertTrue(Files.isDirectory(dir.resolve("config.json.bak")));
    }

    // The switches that ship on in this version, against 1.0.0's off
    @Test
    void coreSwitchesShipOn() {
        YoltConfig fresh = new YoltConfig();
        assertTrue(fresh.hide.coordinates);
        assertTrue(fresh.chat.allowWhispers);
        assertTrue(fresh.skins.lockAfterFirstChoice);
        assertEquals(2, ConfigLoader.CURRENT_VERSION);
        assertEquals(List.of("hide.coordinates", "skins.lockAfterFirstChoice", "chat.allowWhispers"), ConfigLoader.V1_OFF_NOW_ON);
    }

    private static byte[] shippedV1File() throws IOException {
        try (InputStream in = Objects.requireNonNull(ConfigLoaderTest.class.getResourceAsStream("/config-1.0.0.json"),
                "no config-1.0.0.json in the test resources")) {
            return in.readAllBytes();
        }
    }

    private static String turnedOn(String path) {
        return path + " was false, the old default, and is now true, the new one. To keep it off, run /yolt config " + path
                + " false";
    }

    // The file 1.0.0 wrote, every key at its default but two the owner changed: chat.radius 32 and hide.coordinates on
    @Test
    void shippedV1FileUpgradesWithItsValues() throws IOException {
        Path file = dir.resolve("config.json");
        byte[] old = shippedV1File();
        Files.write(file, old);

        ConfigLoader.Result r = ConfigLoader.load(file, null, true);
        assertEquals(List.of("config was v1 and is upgraded to v2, old file saved as config.json.v1.bak",
                turnedOn("skins.lockAfterFirstChoice"),
                turnedOn("chat.allowWhispers"),
                "the file had no hardcore, resourcePack, so they take their defaults"), r.warnings());
        // Everything else, hardcore and resourcePack included, is this version's default
        YoltConfig expected = new YoltConfig();
        expected.chat.radius = 32;
        assertEquals(new Gson().toJsonTree(expected), new Gson().toJsonTree(r.config()));
        assertTrue(r.config().hide.coordinates);
        // The whole hardcore section arrives at its defaults, so an upgraded 1.0.0 server gets the curse too
        assertTrue(r.config().hardcore.curse.enabled);

        assertArrayEquals(old, Files.readAllBytes(dir.resolve("config.json.v1.bak")));
        assertEquals(ConfigLoader.CURRENT_VERSION, readJson(file).get("configVersion").getAsInt());
        assertEquals(32, readJson(file).getAsJsonObject("chat").get("radius").getAsInt());
        assertTrue(r.rewritten());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("config.json", "config.json.v1.bak"), files.map(f -> f.getFileName().toString()).sorted().toList());
        }
    }

    // Only a JSON false is the old default. true stays, a missing key takes the new default, and a wrong type is the
    // merge's to warn about, as in any other file.
    @Test
    void v1StepFlipsOnlyUntouchedDefaults() throws IOException {
        Path file = dir.resolve("config.json");
        for (String path : ConfigLoader.V1_OFF_NOW_ON) {
            String[] parts = path.split("\\.");
            ConfigKey key = key(path);
            for (String typed : List.of("false", "true", "absent", "\"yes\"")) {
                String section = typed.equals("absent") ? "{}" : "{\"" + parts[1] + "\": " + typed + "}";
                Files.writeString(file, "{\"configVersion\": 1, \"" + parts[0] + "\": " + section + "}");
                ConfigLoader.Result r = ConfigLoader.load(file, null, true);
                String what = path + " " + typed;
                assertEquals("true", key.show(r.config()), what);
                assertEquals(typed.equals("false"), r.warnings().contains(turnedOn(path)), what + ": " + r.warnings());
                assertEquals(typed.equals("\"yes\""), r.warnings().contains(path + ": expected true or false, using default"),
                        what + ": " + r.warnings());
                // The step only ever names its own key
                assertEquals(typed.equals("false") ? 1 : 0, r.warnings().stream().filter(w -> w.contains("To keep it off")).count(),
                        what + ": " + r.warnings());
                assertTrue(readJson(file).getAsJsonObject(parts[0]).get(parts[1]).getAsBoolean(), what + " in the file");
            }
        }
    }

    // As 1.0.0 reads this version's file: a newer version, so it is left exactly as it is
    @Test
    void olderBuildLeavesAV2FileAlone() throws IOException {
        Path file = dir.resolve("config.json");
        String v2 = "{\"configVersion\": 2, \"chat\": {\"radius\": 32}, \"hardcore\": {\"curse\": {\"enabled\": true}}}";
        Files.writeString(file, v2);

        ConfigLoader.Result r = ConfigLoader.load(file, null, true, 1, List.of());
        assertEquals(32, r.config().chat.radius);
        assertFalse(r.rewritten());
        assertNull(r.backup());
        assertTrue(r.warnings().contains("config is from a newer version, so it was not rewritten"), r.warnings().toString());
        assertEquals(v2, Files.readString(file));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file.getFileName()), files.map(Path::getFileName).toList());
        }
    }

    // Gson reads an empty or blank file as JSON null, which is no object, and a cut-off file fails to parse
    @Test
    void emptyBlankAndTruncatedFilesAreBackedUp() throws IOException {
        Path file = dir.resolve("config.json");
        Path bak = dir.resolve("config.json.bak");
        for (String typed : List.of("", "  \n", "{\"chat\":{\"radius\":")) {
            Files.writeString(file, typed);
            ConfigLoader.Result reload = ConfigLoader.load(file, null, false);
            assertTrue(reload.unparseable(), "reload of '" + typed + "'");
            assertNull(reload.config(), "reload of '" + typed + "'");
            assertEquals(typed, Files.readString(file), "reload of '" + typed + "'");
            assertFalse(Files.exists(bak), "reload of '" + typed + "'");

            ConfigLoader.Result startup = ConfigLoader.load(file, null, true);
            assertTrue(startup.unparseable(), "startup with '" + typed + "'");
            assertNotNull(startup.borderProblem(), "startup with '" + typed + "'");
            assertEquals(typed, Files.readString(bak), "startup with '" + typed + "'");
            assertEquals(new Gson().toJsonTree(new YoltConfig()), new Gson().toJsonTree(startup.config()), "startup with '" + typed + "'");
            assertEquals(10000, readJson(file).getAsJsonObject("worldBorder").get("diameter").getAsInt(), "startup with '" + typed + "'");
            Files.delete(bak);
        }
    }

    // A JSON null is the wrong type wherever it stands: that key alone falls back, and the rest of the file still counts
    @Test
    void nullValuesFallBackPerKey() throws IOException {
        ConfigLoader.Result r = assertDoesNotThrow(() -> loadJson("{\"chat\":{\"radius\":null,\"mode\":\"global\"},"
                + "\"hide\":{\"tabHeaderLines\":null},\"resourcePack\":null,\"hardcore\":{\"weight\":{\"groups\":null}},"
                + "\"randomSpawn\":false}"));
        YoltConfig fresh = new YoltConfig();
        assertEquals(48, r.config().chat.radius);
        assertEquals(YoltConfig.ChatMode.GLOBAL, r.config().chat.mode);
        assertEquals(fresh.hide.tabHeaderLines, r.config().hide.tabHeaderLines);
        assertEquals(fresh.resourcePack.reviveModel, r.config().resourcePack.reviveModel);
        assertEquals(fresh.hardcore.weight.groups, r.config().hardcore.weight.groups);
        assertFalse(r.config().randomSpawn);
        for (String warning : List.of("chat.radius: wrong type, using default",
                "hide.tabHeaderLines: expected a list of strings, using default",
                "resourcePack: expected an object, using default",
                "hardcore.weight.groups: expected an object of numbers, using default")) {
            assertTrue(r.warnings().contains(warning), "no \"" + warning + "\" in " + r.warnings());
        }
    }

    // A first start, or a start with a file that can't be read, in a folder it can't write runs on the defaults
    @Test
    void startupInAReadOnlyFolderWithNoFileDoesNotThrow() throws IOException {
        Path file = dir.resolve("config.json");
        inReadOnlyDir(() -> {
            ConfigLoader.Result r = assertDoesNotThrow(() -> ConfigLoader.load(file, null, true));
            assertEquals(new Gson().toJsonTree(new YoltConfig()), new Gson().toJsonTree(r.config()));
            assertFalse(r.rewritten());
            assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("The config file could not be written (")), r.warnings().toString());
        });
        assertFalse(Files.exists(file));

        Files.writeString(file, "{not json");
        inReadOnlyDir(() -> {
            ConfigLoader.Result r = assertDoesNotThrow(() -> ConfigLoader.load(file, null, true));
            assertTrue(r.unparseable());
            assertNotNull(r.borderProblem());
            assertEquals(new Gson().toJsonTree(new YoltConfig()), new Gson().toJsonTree(r.config()));
            assertFalse(r.rewritten());
            assertNull(r.backup());
            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("could not be copied (")), r.warnings().toString());
        });
        assertEquals("{not json", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("config.json.bak")));
    }

    // A map in the file replaces the default whole, so an entry a later version adds to the default never reaches it.
    // Whoever adds one has to write a step for it.
    @Test
    void fileMapOmitsNewDefaultEntries() throws IOException {
        Map<String, Map<String, Double>> foods = new LinkedHashMap<>(new YoltConfig().hardcore.nutrients.foods);
        assertNotNull(foods.remove("minecraft:apple"));
        ConfigLoader.Result r = loadJson("{\"hardcore\":{\"nutrients\":{\"foods\":" + new Gson().toJson(foods) + "}}}");
        assertFalse(r.config().hardcore.nutrients.foods.containsKey("minecraft:apple"));
        assertEquals(foods, r.config().hardcore.nutrients.foods);
        assertEquals(List.of(), r.warnings());
    }
}
