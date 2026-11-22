package bobbynooby.dev.hardcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardcoreFileTest {
    private static final Gson GSON = new Gson();
    private static final String DEFAULT = ", using default";
    private static final String CURRENT = ", using the current value";
    private static final String COPIED = "old file saved as yolt-hardcore.json.bak before the rewrite";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(HardcoreFile.NAME);
    }

    private HardcoreFile.Result start(String json) throws IOException {
        Files.writeString(file(), json, StandardCharsets.UTF_8);
        return HardcoreFile.start(file());
    }

    private static String json(HardcoreFile.Loaded loaded) {
        JsonObject tree = new JsonObject();
        tree.add("hardcore", GSON.toJsonTree(loaded.hardcore()));
        tree.add("texts", GSON.toJsonTree(loaded.texts()));
        return tree.toString();
    }

    // In use before a reload, with values that differ from the defaults, so a value kept from it is told from a default
    private static HardcoreFile.Loaded inUse() {
        HardcoreConfig.HardcoreSection hardcore = new HardcoreConfig.HardcoreSection();
        hardcore.weight.enabled = true;
        hardcore.weight.freeLoad = 20.0;
        hardcore.weight.containerDepth = 5;
        hardcore.weight.groups = new LinkedHashMap<>(Map.of("c:ores", 4.0));
        hardcore.tougherMobs.exclude = new ArrayList<>(List.of("minecraft:creeper"));
        hardcore.bloodMoon.barColour = HardcoreConfig.BarColour.BLUE;
        hardcore.slow.cap = 0.4;
        BloodMoonTexts texts = new BloodMoonTexts("Title", "Warning", "Risen", "Up", "Set", "Bar");
        return new HardcoreFile.Loaded(hardcore, texts);
    }

    /**
     * What a file gives at a start and on a reload with the defaults in use: the same notes, each ending ", using default"
     * at the start and ", using the current value" on the reload, and at the start the copy kept before the rewrite. Each
     * in a folder of its own, so no copy from an earlier call is there. Returns the start's settings.
     */
    private HardcoreFile.Loaded assertNotes(String json, String... startNotes) throws IOException {
        Path folder = Files.createTempDirectory(dir, "case");
        Path file = folder.resolve(HardcoreFile.NAME);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        HardcoreFile.Result started = HardcoreFile.start(file);
        List<String> expected = new ArrayList<>(List.of(startNotes));
        expected.add(COPIED);
        assertEquals(expected, started.notes());
        assertFalse(started.failed());
        assertEquals(HardcoreFile.NAME + ".bak", started.backup());

        Files.writeString(file, json, StandardCharsets.UTF_8);
        HardcoreFile.Result reloaded = HardcoreFile.reload(file, HardcoreFile.Loaded.defaults());
        List<String> onReload = Stream.of(startNotes)
                .map(note -> note.endsWith(DEFAULT) ? note.substring(0, note.length() - DEFAULT.length()) + CURRENT : note)
                .toList();
        assertEquals(onReload, reloaded.notes());
        assertFalse(reloaded.failed());
        assertNull(reloaded.backup());
        assertEquals(json(started.loaded()), json(reloaded.loaded()));
        return started.loaded();
    }

    @Test
    void aMissingFileIsWrittenWithTheDefaultsWhichReadBackEqual() throws IOException {
        HardcoreFile.Result started = HardcoreFile.start(file());
        assertEquals(List.of(), started.notes());
        assertFalse(started.failed());
        assertNull(started.backup());
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(started.loaded()));
        assertEquals(HardcoreFile.json(HardcoreFile.Loaded.defaults()), Files.readString(file()));

        JsonObject written = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        assertEquals(List.of("configVersion", "hardcore", "messages"), List.copyOf(written.keySet()));
        assertEquals(1, written.get("configVersion").getAsInt());
        assertEquals(List.of("bloodMoonWarningTitle", "bloodMoonWarningLine", "bloodMoonRisenLine", "bloodMoonUpLine",
                "bloodMoonSetLine", "bloodMoonBarTitle"), List.copyOf(written.getAsJsonObject("messages").keySet()));

        HardcoreFile.Result read = HardcoreFile.reload(file(), inUse());
        assertEquals(List.of(), read.notes());
        assertFalse(read.failed());
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(read.loaded()));
        assertEquals(BloodMoonTexts.DEFAULTS, read.loaded().texts());
        // Written again at the next start, with nothing to copy first
        HardcoreFile.Result again = HardcoreFile.start(file());
        assertEquals(List.of(), again.notes());
        assertFalse(Files.exists(dir.resolve(HardcoreFile.NAME + ".bak")));
    }

    // The hardcore block of YOLT's default config is a default HardcoreSection written by a Gson with these settings;
    // YOLT's HardcoreFileParityTest copies it from a file YOLT's own loader wrote
    @Test
    void aHardcoreBlockCopiedFromYoltsDefaultConfigReadsWithNoNotes() throws IOException {
        Gson yoltsGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String block = yoltsGson.toJson(new HardcoreConfig.HardcoreSection());
        HardcoreFile.Result started = start("{\"configVersion\": 1, \"hardcore\": " + block + "}");
        assertEquals(List.of(), started.notes());
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(started.loaded()));
        assertNull(started.backup());
    }

    // As YOLT's loader: a newer file is read but never written over, since the rewrite would drop what only the newer
    // version knows and label the rest as this version's
    @Test
    void aFileFromANewerVersionIsReadButNotRewritten() throws IOException {
        String typed = """
                {"configVersion": 2, "hardcore": {"weight": {"enabled": true, "freeLoad": "lots"}, "future": {}}}""";
        HardcoreFile.Result started = start(typed);
        assertEquals(List.of("hardcore.weight.freeLoad: expected a number" + DEFAULT, "hardcore.future: unknown key, ignored",
                "config is from a newer version, so it was not rewritten"), started.notes());
        assertFalse(started.failed());
        assertNull(started.backup());
        assertTrue(started.loaded().hardcore().weight.enabled);
        assertEquals(16.0, started.loaded().hardcore().weight.freeLoad);
        assertEquals(typed, Files.readString(file()));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file()), files.toList());
        }

        HardcoreFile.Result reloaded = HardcoreFile.reload(file(), inUse());
        assertEquals(List.of("hardcore.weight.freeLoad: expected a number" + CURRENT, "hardcore.future: unknown key, ignored",
                "config is from a newer version, so it was not rewritten"), reloaded.notes());
        assertEquals(20.0, reloaded.loaded().hardcore().weight.freeLoad);
    }

    // Zero or below can only be a damaged file, so it reads as this version, said as YOLT says it. As in YOLT, the
    // version alone keeps no copy before the rewrite, which writes this version.
    @Test
    void aVersionBelowOneIsReadAsThisVersion() throws IOException {
        for (String version : List.of("0", "-5")) {
            HardcoreFile.Result started = start("{\"configVersion\": " + version + ", \"hardcore\": {\"weight\": {\"enabled\": true}}}");
            String note = "configVersion: " + version + " is not a version, read as version 1";
            assertEquals(List.of(note), started.notes());
            assertNull(started.backup());
            assertTrue(started.loaded().hardcore().weight.enabled);
            assertEquals(HardcoreFile.json(started.loaded()), Files.readString(file()));
            assertFalse(Files.exists(dir.resolve(HardcoreFile.NAME + ".bak")));

            Files.writeString(file(), "{\"configVersion\": " + version + "}");
            assertEquals(List.of(note), HardcoreFile.reload(file(), inUse()).notes());
        }
    }

    // One that isn't a whole number is named as any other key is, and the start keeps a copy before writing this version
    @Test
    void aVersionThatIsNotANumberIsNamedAsAnyKeyIs() throws IOException {
        String typed = "{\"configVersion\": \"one\", \"hardcore\": {\"weight\": {\"enabled\": true}}}";
        HardcoreFile.Result started = start(typed);
        assertEquals(List.of("configVersion: expected a number" + DEFAULT, COPIED), started.notes());
        assertEquals(typed, Files.readString(dir.resolve(HardcoreFile.NAME + ".bak")));
        assertEquals(HardcoreFile.json(started.loaded()), Files.readString(file()));
        assertTrue(started.loaded().hardcore().weight.enabled);
    }

    @Test
    void aMissingKeyTakesTheDefaultAndTheStartWritesItIn() throws IOException {
        HardcoreFile.Result started = start("{\"hardcore\": {\"weight\": {\"enabled\": true}}}");
        assertEquals(List.of(), started.notes());
        assertNull(started.backup());
        assertTrue(started.loaded().hardcore().weight.enabled);
        HardcoreFile.Loaded expected = HardcoreFile.Loaded.defaults();
        expected.hardcore().weight.enabled = true;
        assertEquals(json(expected), json(started.loaded()));
        // Every key, and nothing to keep a copy of, since nothing typed was lost
        assertEquals(HardcoreFile.json(started.loaded()), Files.readString(file()));
        assertFalse(Files.exists(dir.resolve(HardcoreFile.NAME + ".bak")));
    }

    @Test
    void aMissingKeyKeepsTheValueInUseOnAReload() throws IOException {
        Files.writeString(file(), "{\"hardcore\": {\"weight\": {\"enabled\": false}}}");
        HardcoreFile.Result reloaded = HardcoreFile.reload(file(), inUse());
        assertEquals(List.of(), reloaded.notes());
        HardcoreFile.Loaded expected = inUse();
        expected.hardcore().weight.enabled = false;
        assertEquals(json(expected), json(reloaded.loaded()));
    }

    @Test
    void anUnknownKeyIsIgnored() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"weight": {"enabeld": true}, "colds": {}},
                 "messages": {"lockoutDied": "You died"},
                 "worldBorder": {"enabled": true}}""",
                "hardcore.weight.enabeld: unknown key, ignored",
                "hardcore.colds: unknown key, ignored",
                "messages.lockoutDied: unknown key, ignored",
                "worldBorder: unknown key, ignored");
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(loaded));
    }

    @Test
    void aSwitchMustBeTrueOrFalse() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("{\"hardcore\": {\"curse\": {\"enabled\": \"no\"}}}",
                "hardcore.curse.enabled: expected true or false" + DEFAULT);
        assertTrue(loaded.hardcore().curse.enabled);
    }

    @Test
    void aNumberMustBeANumber() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("{\"configVersion\": \"one\", \"hardcore\": {\"weight\": {\"freeLoad\": \"10\"}}}",
                "configVersion: expected a number" + DEFAULT,
                "hardcore.weight.freeLoad: expected a number" + DEFAULT);
        assertEquals(16.0, loaded.hardcore().weight.freeLoad);
    }

    // 1e999 reads as infinity, which can't be written back
    @Test
    void aNumberMustBeFinite() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("{\"hardcore\": {\"weight\": {\"freeLoad\": 1e999, \"fullLoad\": -1e999}}}",
                "hardcore.weight.freeLoad: expected a finite number" + DEFAULT,
                "hardcore.weight.fullLoad: expected a finite number" + DEFAULT);
        assertEquals(16.0, loaded.hardcore().weight.freeLoad);
        assertEquals(144.0, loaded.hardcore().weight.fullLoad);
    }

    // 1e-99999 reads as 0 as a double, but Gson can't make an int of it
    @Test
    void aWholeNumberMustFitInAnInt() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"weight": {"containerDepth": 2.5},
                              "legs": {"healPoints": 3000000000, "healTicks": 1e-99999, "healTicksSaturated": 1e1}}}""",
                "hardcore.weight.containerDepth: expected a whole number that fits in an int" + DEFAULT,
                "hardcore.legs.healPoints: expected a whole number that fits in an int" + DEFAULT,
                "hardcore.legs.healTicks: expected a whole number that fits in an int" + DEFAULT);
        assertEquals(3, loaded.hardcore().weight.containerDepth);
        assertEquals(30, loaded.hardcore().legs.healPoints);
        assertEquals(80, loaded.hardcore().legs.healTicks);
        assertEquals(10, loaded.hardcore().legs.healTicksSaturated);
    }

    @Test
    void aTextOrAnEnumMustBeAString() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"bloodMoon": {"barColour": 2}}, "messages": {"bloodMoonSetLine": true}}""",
                "hardcore.bloodMoon.barColour: expected a string" + DEFAULT,
                "messages.bloodMoonSetLine: expected a string" + DEFAULT);
        assertEquals(HardcoreConfig.BarColour.RED, loaded.hardcore().bloodMoon.barColour);
        assertEquals(BloodMoonTexts.DEFAULTS.setLine(), loaded.texts().setLine());
    }

    @Test
    void aListOrAnObjectWhereAValueGoesIsTheWrongType() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"slow": {"cap": {"value": 0.5}}, "weight": {"enabled": null, "freeLoad": [16]}},
                 "messages": {"bloodMoonBarTitle": ["Blood", "Moon"]}}""",
                "hardcore.slow.cap: wrong type" + DEFAULT,
                "hardcore.weight.enabled: wrong type" + DEFAULT,
                "hardcore.weight.freeLoad: wrong type" + DEFAULT,
                "messages.bloodMoonBarTitle: wrong type" + DEFAULT);
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(loaded));
    }

    @Test
    void aSectionMustBeAnObject() throws IOException {
        assertNotes("{\"hardcore\": {\"weight\": 5, \"nutrients\": {\"penalties\": []}}, \"messages\": \"none\"}",
                "hardcore.weight: expected an object" + DEFAULT,
                "hardcore.nutrients.penalties: expected an object" + DEFAULT,
                "messages: expected an object" + DEFAULT);
        HardcoreFile.Loaded loaded = assertNotes("{\"hardcore\": true}", "hardcore: expected an object" + DEFAULT);
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(loaded));
    }

    @Test
    void aListMustHoldOnlyStrings() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"tougherMobs": {"exclude": "minecraft:wither"},
                              "relentlessMobs": {"exclude": ["minecraft:warden", 1]}}}""",
                "hardcore.tougherMobs.exclude: expected a list of strings" + DEFAULT,
                "hardcore.relentlessMobs.exclude: expected a list of strings" + DEFAULT);
        assertEquals(new HardcoreConfig.TougherMobsSection().exclude, loaded.hardcore().tougherMobs.exclude);
        assertEquals(List.of("minecraft:warden"), loaded.hardcore().relentlessMobs.exclude);
    }

    @Test
    void aWeightMapMustHoldOnlyNumbers() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"weight": {"groups": {"c:ores": "heavy"}, "items": [], "itemsEach": 3,
                                         "armourFactors": {"iron": 1e999}}}}""",
                "hardcore.weight.groups: expected an object of numbers" + DEFAULT,
                "hardcore.weight.items: expected an object of numbers" + DEFAULT,
                "hardcore.weight.itemsEach: expected an object of numbers" + DEFAULT,
                "hardcore.weight.armourFactors: expected an object of numbers" + DEFAULT);
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(loaded));
    }

    @Test
    void aMapOfMapsMustHoldOnlyObjectsOfNumbers() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"nutrients": {"foods": {"minecraft:apple": 1}},
                              "tougherMobs": {"overrides": {"minecraft:ghast": {"health": "double"}}}}}""",
                "hardcore.nutrients.foods: expected an object of objects of numbers" + DEFAULT,
                "hardcore.tougherMobs.overrides: expected an object of objects of numbers" + DEFAULT);
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(loaded));
    }

    // The file's names are never unknown keys, and a name it leaves out is gone
    @Test
    void aMapIsReadWhole() throws IOException {
        HardcoreFile.Result started = start("""
                {"hardcore": {"weight": {"groups": {"c:ores": 5, "mymod:metals": 2.5}, "armourFactors": {}},
                              "nutrients": {"foods": {"minecraft:bread": {"sugar": 1}}},
                              "tougherMobs": {"overrides": {}}}}""");
        assertEquals(List.of(), started.notes());
        HardcoreConfig.HardcoreSection hardcore = started.loaded().hardcore();
        assertEquals(Map.of("c:ores", 5.0, "mymod:metals", 2.5), hardcore.weight.groups);
        assertEquals(List.of("c:ores", "mymod:metals"), List.copyOf(hardcore.weight.groups.keySet()));
        assertEquals(Map.of(), hardcore.weight.armourFactors);
        assertEquals(Map.of("minecraft:bread", Map.of("sugar", 1.0)), hardcore.nutrients.foods);
        assertEquals(Map.of(), hardcore.tougherMobs.overrides);
        assertEquals(new HardcoreConfig.WeightSection().items, hardcore.weight.items);
    }

    // Gson would also take an enum's Java name, which the docs never give; that and any other text are refused by the
    // checks as an unknown value
    @Test
    void anEnumIsReadByItsNameInTheFileOnly() throws IOException {
        HardcoreFile.Result started = start("""
                {"hardcore": {"bloodMoon": {"barColour": "purple"}, "tougherMobs": {"damageAppliesTo": "all"},
                              "nutrients": {"unknownFood": "even"}}}""");
        assertEquals(List.of(), started.notes());
        assertEquals(HardcoreConfig.BarColour.PURPLE, started.loaded().hardcore().bloodMoon.barColour);
        assertEquals(HardcoreConfig.DamageAppliesTo.ALL, started.loaded().hardcore().tougherMobs.damageAppliesTo);
        assertEquals(HardcoreConfig.UnknownFood.EVEN, started.loaded().hardcore().nutrients.unknownFood);

        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"bloodMoon": {"barColour": "PURPLE"}, "tougherMobs": {"damageAppliesTo": "ALL"},
                              "nutrients": {"unknownFood": "sometimes"}}}""",
                "hardcore.nutrients.unknownFood: unknown value" + DEFAULT,
                "hardcore.tougherMobs.damageAppliesTo: unknown value" + DEFAULT,
                "hardcore.bloodMoon.barColour: unknown value" + DEFAULT);
        assertEquals(HardcoreConfig.BarColour.RED, loaded.hardcore().bloodMoon.barColour);
        assertEquals(HardcoreConfig.DamageAppliesTo.PLAYERS, loaded.hardcore().tougherMobs.damageAppliesTo);
        assertEquals(HardcoreConfig.UnknownFood.NONE, loaded.hardcore().nutrients.unknownFood);
    }

    // HardcoreChecks' rules, after every note the reading gave
    @Test
    void aValueTheChecksRefuseFallsBack() throws IOException {
        HardcoreFile.Loaded loaded = assertNotes("""
                {"hardcore": {"slow": {"cap": 1.5}, "weight": {"groups": {"c:ores": -1}, "freeLoad": true},
                              "limp": {"belowHearts": 4}}}""",
                "hardcore.weight.freeLoad: expected a number" + DEFAULT,
                "hardcore.slow.cap: must be 0 to 1" + DEFAULT,
                "hardcore.weight.groups: every weight must be 0 or more" + DEFAULT,
                "hardcore.limp.belowHearts: must be less than hardcore.limp.recoverAtHearts" + DEFAULT,
                "hardcore.limp.recoverAtHearts: must be more than hardcore.limp.belowHearts" + DEFAULT);
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(loaded));
    }

    // YOLT's limit for these texts; YOLT's HardcoreFileParityTest holds the two limits together
    @Test
    void aTextMayBeAtMostTheLengthYoltAllows() throws IOException {
        String tooLong = "x".repeat(HardcoreFile.MAX_TEXT_LENGTH + 1);
        StringBuilder messages = new StringBuilder();
        List<String> notes = new ArrayList<>();
        for (String key : List.of("bloodMoonWarningTitle", "bloodMoonWarningLine", "bloodMoonRisenLine", "bloodMoonUpLine",
                "bloodMoonSetLine", "bloodMoonBarTitle")) {
            messages.append(messages.isEmpty() ? "" : ", ").append('"').append(key).append("\": \"").append(tooLong).append('"');
            notes.add("messages." + key + ": must be at most " + HardcoreFile.MAX_TEXT_LENGTH + " characters" + DEFAULT);
        }
        HardcoreFile.Loaded loaded = assertNotes("{\"messages\": {" + messages + "}}", notes.toArray(String[]::new));
        assertEquals(BloodMoonTexts.DEFAULTS, loaded.texts());

        String longest = "y".repeat(HardcoreFile.MAX_TEXT_LENGTH);
        HardcoreFile.Result started = start("{\"messages\": {\"bloodMoonSetLine\": \"" + longest + "\", \"bloodMoonUpLine\": \"\"}}");
        assertEquals(List.of(), started.notes());
        assertEquals(longest, started.loaded().texts().setLine());
        assertEquals("", started.loaded().texts().upLine());
    }

    @Test
    void aReloadKeepsTheValueInUseForEachMistake() throws IOException {
        Files.writeString(file(), """
                {"hardcore": {"slow": {"cap": 2}, "weight": {"freeLoad": "10", "containerDepth": 1.5, "groups": [],
                                                             "enabeld": true},
                              "tougherMobs": {"exclude": 3}, "bloodMoon": {"barColour": "GREEN"}},
                 "messages": {"bloodMoonSetLine": 4}}""");
        HardcoreFile.Result reloaded = HardcoreFile.reload(file(), inUse());
        assertEquals(List.of(
                "hardcore.weight.freeLoad: expected a number" + CURRENT,
                "hardcore.weight.groups: expected an object of numbers" + CURRENT,
                "hardcore.weight.containerDepth: expected a whole number that fits in an int" + CURRENT,
                "hardcore.weight.enabeld: unknown key, ignored",
                "hardcore.tougherMobs.exclude: expected a list of strings" + CURRENT,
                "messages.bloodMoonSetLine: expected a string" + CURRENT,
                "hardcore.slow.cap: must be 0 to 1" + CURRENT,
                "hardcore.bloodMoon.barColour: unknown value" + CURRENT), reloaded.notes());
        assertFalse(reloaded.failed());
        assertEquals(json(inUse()), json(reloaded.loaded()));
    }

    @Test
    void aReloadNeverWrites() throws IOException {
        String typed = "{\"hardcore\": {\"weight\": {\"enabled\": \"yes\", \"enabeld\": true}}}";
        Files.writeString(file(), typed);
        HardcoreFile.Result reloaded = HardcoreFile.reload(file(), inUse());
        assertEquals(2, reloaded.notes().size());
        assertNull(reloaded.backup());
        assertEquals(typed, Files.readString(file()));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file()), files.toList());
        }
    }

    @Test
    void aReloadOfAMissingFileChangesNothing() {
        HardcoreFile.Loaded inUse = inUse();
        HardcoreFile.Result reloaded = HardcoreFile.reload(file(), inUse);
        assertTrue(reloaded.failed());
        assertSame(inUse, reloaded.loaded());
        assertEquals(List.of("config file is missing, nothing changed"), reloaded.notes());
        assertFalse(Files.exists(file()));
    }

    @Test
    void aReloadOfAFileThatIsNotJsonChangesNothing() throws IOException {
        Files.writeString(file(), "{\"hardcore\": ");
        HardcoreFile.Loaded inUse = inUse();
        HardcoreFile.Result reloaded = HardcoreFile.reload(file(), inUse);
        assertTrue(reloaded.failed());
        assertSame(inUse, reloaded.loaded());
        assertEquals(1, reloaded.notes().size());
        assertTrue(reloaded.notes().getFirst().startsWith("config is not valid JSON ("), reloaded.notes().getFirst());
        assertEquals("{\"hardcore\": ", Files.readString(file()));
        assertFalse(Files.exists(dir.resolve(HardcoreFile.NAME + ".bak")));

        Files.writeString(file(), "[1]");
        assertEquals(List.of("config is not valid JSON (root is not an object)"), HardcoreFile.reload(file(), inUse).notes());
    }

    @Test
    void aStartKeepsAFileThatIsNotJsonAsBakAndWritesTheDefaults() throws IOException {
        String typed = "{\"hardcore\": {\"weight\": ";
        HardcoreFile.Result started = start(typed);
        assertTrue(started.failed());
        assertEquals(HardcoreFile.NAME + ".bak", started.backup());
        assertEquals(1, started.notes().size());
        String note = started.notes().getFirst();
        assertTrue(note.startsWith("config is not valid JSON ("), note);
        assertTrue(note.endsWith("), old file saved as yolt-hardcore.json.bak, using defaults"), note);
        assertEquals(typed, Files.readString(dir.resolve(HardcoreFile.NAME + ".bak")));
        assertEquals(HardcoreFile.json(HardcoreFile.Loaded.defaults()), Files.readString(file()));
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(started.loaded()));
    }

    @Test
    void aStartKeepsAFileThatIsNotUtf8AsBak() throws IOException {
        byte[] typed = {'{', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        Files.write(file(), typed);
        HardcoreFile.Result started = HardcoreFile.start(file());
        assertTrue(started.failed());
        assertEquals(List.of("config could not be read (not UTF-8 text), old file saved as yolt-hardcore.json.bak, using defaults"),
                started.notes());
        assertArrayEquals(typed, Files.readAllBytes(dir.resolve(HardcoreFile.NAME + ".bak")));
    }

    // The .bak may be the admin's whole file from an earlier start, so a different one goes beside it with the time
    // added, and one with the same bytes is used as it is
    @Test
    void theBakIsNeverWrittenOver() throws IOException {
        Path bak = dir.resolve(HardcoreFile.NAME + ".bak");
        Files.writeString(bak, "an earlier file");
        String typed = "{\"hardcore\": {\"slow\": {\"cap\": 3}}}";
        HardcoreFile.Result started = start(typed);
        String backup = started.backup();
        assertTrue(backup != null && backup.startsWith(HardcoreFile.NAME + ".bak-"), String.valueOf(backup));
        assertEquals(List.of("hardcore.slow.cap: must be 0 to 1" + DEFAULT, "old file saved as " + backup + " before the rewrite"),
                started.notes());
        assertEquals("an earlier file", Files.readString(bak));
        assertEquals(typed, Files.readString(dir.resolve(backup)));

        // The same mistake again: its copy is there already
        HardcoreFile.Result again = start(typed);
        assertEquals(backup, again.backup());
        Files.writeString(bak, typed);
        Files.delete(dir.resolve(backup));
        assertEquals(HardcoreFile.NAME + ".bak", start(typed).backup());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void aStartRewritesTheFileWithEveryKeyAfterTheCopy() throws IOException {
        String typed = "{\"hardcore\": {\"weight\": {\"enabled\": true, \"freeLoad\": \"lots\"}}}";
        HardcoreFile.Result started = start(typed);
        assertEquals(List.of("hardcore.weight.freeLoad: expected a number" + DEFAULT, COPIED), started.notes());
        assertEquals(typed, Files.readString(dir.resolve(HardcoreFile.NAME + ".bak")));
        assertEquals(HardcoreFile.json(started.loaded()), Files.readString(file()));
        assertTrue(started.loaded().hardcore().weight.enabled);
        // What was written reads back as it was loaded, with nothing to say
        HardcoreFile.Result read = HardcoreFile.reload(file(), HardcoreFile.Loaded.defaults());
        assertEquals(List.of(), read.notes());
        assertEquals(json(started.loaded()), json(read.loaded()));
    }

    // The settings in use are the file's or the defaults all the same, and the reason is in the notes
    @Test
    void aFolderThatCantBeWrittenNeverStopsTheStart() throws IOException {
        Path notAFolder = dir.resolve("plain");
        Files.writeString(notAFolder, "a file where the config folder would be");
        HardcoreFile.Result fresh = HardcoreFile.start(notAFolder.resolve(HardcoreFile.NAME));
        assertFalse(fresh.failed());
        assertEquals(1, fresh.notes().size());
        assertTrue(fresh.notes().getFirst().startsWith("The config file could not be written ("), fresh.notes().getFirst());
        assertTrue(fresh.notes().getFirst().endsWith("), so YOLT Hardcore runs on the defaults."), fresh.notes().getFirst());
        assertEquals(json(HardcoreFile.Loaded.defaults()), json(fresh.loaded()));

        // A folder where the write's temp file goes keeps the rewrite from happening
        Files.createDirectory(dir.resolve(HardcoreFile.NAME + ".tmp"));
        String typed = "{\"hardcore\": {\"weight\": {\"enabled\": true}}}";
        HardcoreFile.Result started = start(typed);
        assertFalse(started.failed());
        assertEquals(1, started.notes().size());
        assertTrue(started.notes().getFirst().startsWith("The config file could not be rewritten ("), started.notes().getFirst());
        assertTrue(started.notes().getFirst().endsWith("), so it is left as it was."), started.notes().getFirst());
        assertTrue(started.loaded().hardcore().weight.enabled);
        assertEquals(typed, Files.readString(file()));
    }
}
