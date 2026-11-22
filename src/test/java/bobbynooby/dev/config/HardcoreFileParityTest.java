package bobbynooby.dev.config;

import bobbynooby.dev.hardcore.BloodMoonTexts;
import bobbynooby.dev.hardcore.HardcoreConfig;
import bobbynooby.dev.hardcore.HardcoreFile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * YOLT's loader and YOLT Hardcore's own file read the same hardcore block and the same six blood moon texts, with rules
 * written twice, since the mod can't use YOLT's classes. For each file here, YOLT's warnings about those are the mod's
 * notes, in the same order and words, at a start and on a reload, and the two read the same values.
 */
class HardcoreFileParityTest {
    private static final Gson GSON = new Gson();
    private static final List<String> TEXT_KEYS = List.of("bloodMoonWarningTitle", "bloodMoonWarningLine",
            "bloodMoonRisenLine", "bloodMoonUpLine", "bloodMoonSetLine", "bloodMoonBarTitle");

    // One kind of mistake each, then several at once, then files with none
    private static final List<String> FILES = List.of(
            "{\"hardcore\": {\"curse\": {\"enabled\": \"no\", \"countAfk\": 0}}}",
            "{\"hardcore\": {\"weight\": {\"freeLoad\": \"10\"}, \"bloodMoon\": {\"chance\": false}}}",
            "{\"hardcore\": {\"weight\": {\"freeLoad\": 1e999, \"fullLoad\": -1e999}, \"cold\": {\"maxSlow\": 1e400}}}",
            """
                    {"hardcore": {"weight": {"containerDepth": 2.5},
                                  "legs": {"healPoints": 3000000000, "healTicks": 1e-99999, "healTicksSaturated": 1e1,
                                           "healTicksNoRegenRule": -2147483649}}}""",
            "{\"hardcore\": {\"bloodMoon\": {\"barColour\": 2}, \"nutrients\": {\"unknownFood\": true}}}",
            """
                    {"hardcore": {"slow": {"cap": {"value": 0.5}}, "weight": {"enabled": null, "freeLoad": [16]},
                                  "anvil": {"enchantCost": [6]}}}""",
            """
                    {"hardcore": {"weight": {"enabeld": true}, "colds": {},
                                  "nutrients": {"penalties": {"fat": {"healingMultiplier": 1}}}}}""",
            "{\"hardcore\": {\"weight\": 5, \"nutrients\": {\"penalties\": []}, \"cold\": \"on\"}}",
            "{\"hardcore\": true}",
            "{\"hardcore\": []}",
            """
                    {"hardcore": {"tougherMobs": {"exclude": "minecraft:wither"},
                                  "relentlessMobs": {"exclude": ["minecraft:warden", 1]}}}""",
            "{\"hardcore\": {\"tougherMobs\": {\"exclude\": [1e999]}, \"relentlessMobs\": {\"exclude\": {}}}}",
            """
                    {"hardcore": {"weight": {"groups": {"c:ores": "heavy"}, "items": [], "itemsEach": 3,
                                             "armourFactors": {"iron": 1e999}}}}""",
            """
                    {"hardcore": {"nutrients": {"foods": {"minecraft:apple": 1}},
                                  "tougherMobs": {"overrides": {"minecraft:ghast": {"health": "double"}}}}}""",
            "{\"hardcore\": {\"nutrients\": {\"foods\": []}, \"tougherMobs\": {\"overrides\": {\"minecraft:ghast\": null}}}}",
            """
                    {"hardcore": {"bloodMoon": {"barColour": "PURPLE"}, "tougherMobs": {"damageAppliesTo": "ALL"},
                                  "nutrients": {"unknownFood": "sometimes"}}}""",
            "{\"hardcore\": {\"bloodMoon\": {\"barColour\": \"Red\"}}}",
            // Review Focus 4: a wrong type, an unknown key, a number out of range, 1e999, a map with a text in it and
            // an enum's Java name, each losing only its own value
            """
                    {"hardcore": {"weight": {"enabled": 1, "freeLoad": -5, "fullLoad": 1e999, "groups": {"c:ores": "x"},
                                             "enabeld": true, "maxSlow": 0.75},
                                  "bloodMoon": {"barColour": "RED", "enabled": true}}}""",
            // Notes from the reading and from the checks, interleaved by key in the file but not in the notes
            """
                    {"hardcore": {"slow": {"cap": 2}, "weight": {"freeLoad": "10", "containerDepth": 1.5, "groups": [],
                                                                 "enabeld": true},
                                  "tougherMobs": {"exclude": 3}, "bloodMoon": {"barColour": "GREEN"}},
                     "messages": {"bloodMoonSetLine": 4}}""",
            // One value for each check on a single key
            """
                    {"hardcore": {
                      "slow": {"cap": 1.5, "step": -1},
                      "weight": {"freeLoad": -1, "fullLoad": -1, "maxSlow": 2, "defaultWeight": -1, "unstackableWeight": -1,
                                 "toolWeight": -1, "groups": {"c:ores": -1}, "items": {"minecraft:paper": -1},
                                 "itemsEach": {"minecraft:elytra": -1}, "armourFactors": {"iron": -1},
                                 "containerContents": -1, "containerDepth": 9, "heavyJumpExhaustion": -1},
                      "legs": {"breakAtDamage": -1, "breakAtDamageFullLoad": -1, "slow": 2, "healPoints": -1, "healTicks": -1,
                               "healTicksSaturated": -1, "healTicksNoRegenRule": -1, "exhaustionPerPoint": -1},
                      "limp": {"belowHearts": 0, "recoverAtHearts": 0, "slow": 2},
                      "bedRest": {"healEveryTicks": -1, "exhaustionPerHeal": -1, "legHealSpeed": -1},
                      "curse": {"heartsPerDeath": -1, "heartsPerTotem": -1, "maxLostHearts": -1, "healHearts": -1,
                                "healEveryDays": 0, "afkMinutes": 0, "reviveRefundHearts": -1},
                      "nutrients": {"gainPerPoint": -1, "decayPerPoint": -1, "startLevel": 2, "deficientBelow": 2,
                                    "penalties": {"protein": {"healingMultiplier": -1}, "sugar": {"sprintSlow": 2},
                                                  "vitamins": {"miningSlow": 2}},
                                    "foods": {"minecraft:apple": {"protien": 1.0}}, "unknownFood": "sometimes"},
                      "hungerTax": {"multiplier": -1},
                      "mending": {"durabilityPerXp": -1},
                      "anvil": {"enchantCost": 0, "repairCost": 40, "renameCost": -1, "breakChance": 2},
                      "elytra": {"glideGravityMultiplier": -1, "rocketCutoffSpeed": -1},
                      "tougherMobs": {"health": 0, "damage": -1, "speed": -1, "damageAppliesTo": "villagers",
                                      "overrides": {"minecraft:ghast": {"helth": 2.0}}},
                      "relentlessMobs": {"followRange": 0, "doorBreakerChance": 2},
                      "bloodMoon": {"chance": 2, "minNightsBetween": -1, "graceDays": -1, "health": 0, "damage": -1,
                                    "speed": -1, "spawnCap": -1, "armourChance": 2, "enchantChance": 2, "xp": -1,
                                    "extraLootRolls": -1, "chargedCreeperChance": 2, "barColour": "maroon"},
                      "cold": {"chillPerSecond": -1, "warmPerSecond": -1, "shelteredBelowSkyLight": 16, "heatRadius": 9,
                               "leatherPerPiece": 2, "wetMultiplier": -1, "waterMultiplier": -1, "maxSlow": 2,
                               "damageEverySeconds": 0, "shiverExhaustionPerSecond": -1}
                    }}""",
            // Each pair fine on its own but wrong together
            """
                    {"hardcore": {"slow": {"cap": 0.1, "step": 0.2}, "weight": {"freeLoad": 60, "fullLoad": 56},
                                  "legs": {"breakAtDamage": 3, "breakAtDamageFullLoad": 5},
                                  "limp": {"belowHearts": 4, "recoverAtHearts": 3}, "bloodMoon": {"warnTick": 13000}}}""",
            """
                    {"messages": {"bloodMoonSetLine": 5, "bloodMoonBarTitle": ["Blood"], "bloodMoonUpLine": null,
                                  "bloodMoonWarningTitle": {"text": "Red"}}}""",
            "{\"messages\": \"none\"}",
            // The checks of the texts come after the hardcore block's
            "{\"messages\": {\"bloodMoonSetLine\": \"" + "x".repeat(Messages.MAX_LENGTH + 1) + "\"},"
                    + " \"hardcore\": {\"slow\": {\"cap\": 2}, \"cold\": {\"heatRadius\": 9}}}",
            // Good values, edges included
            """
                    {"hardcore": {"weight": {"enabled": true, "freeLoad": 0, "fullLoad": 1, "groups": {"c:ores": 5, "a:b": 0},
                                             "armourFactors": {}, "containerDepth": 8},
                                  "curse": {"maxLostHearts": 0}, "hungerTax": {"multiplier": 0},
                                  "anvil": {"enchantCost": 1, "repairCost": 39},
                                  "nutrients": {"foods": {"minecraft:bread": {"sugar": 1}}, "unknownFood": "even"},
                                  "tougherMobs": {"exclude": [], "overrides": {}, "damageAppliesTo": "all"},
                                  "relentlessMobs": {"exclude": ["a", "b"]},
                                  "bloodMoon": {"barColour": "purple", "warnTick": 0, "endTick": 23999},
                                  "cold": {"enabled": true, "heatRadius": 8, "shelteredBelowSkyLight": 0}},
                     "messages": {"bloodMoonSetLine": "", "bloodMoonBarTitle": "Night of \\u00a7cBlood"}}""",
            "{}");

    @TempDir
    Path dir;

    @Test
    void eachFileReadsTheSameAtAStart() throws IOException {
        for (String json : FILES) {
            assertSameAtStart(json);
        }
    }

    @Test
    void eachFileReadsTheSameOnAReload() throws IOException {
        for (String json : FILES) {
            assertSameOnReload(json);
        }
    }

    // A limit in the mod above YOLT's lets the longer text through, and one below it refuses YOLT's longest
    @Test
    void theTextsHaveYoltsLengthLimit() throws IOException {
        for (int length : List.of(Messages.MAX_LENGTH, Messages.MAX_LENGTH + 1)) {
            StringBuilder messages = new StringBuilder();
            for (String key : TEXT_KEYS) {
                messages.append(messages.isEmpty() ? "" : ", ").append('"').append(key).append("\": \"")
                        .append("x".repeat(length)).append('"');
            }
            String json = "{\"messages\": {" + messages + "}}";
            assertSameAtStart(json);
            assertSameOnReload(json);
        }
    }

    // A block copied from a file YOLT wrote into the mod's file, and back
    @Test
    void aBlockCopiedFromOneFileToTheOtherReadsWithNoNotes() throws IOException {
        Path yoltFile = dir.resolve("you-only-live-twice.json");
        ConfigLoader.write(yoltFile, new YoltConfig());
        JsonObject yolt = JsonParser.parseString(Files.readString(yoltFile)).getAsJsonObject();
        JsonObject copied = new JsonObject();
        copied.addProperty("configVersion", 1);
        copied.add("hardcore", yolt.get("hardcore"));
        JsonObject texts = new JsonObject();
        for (String key : TEXT_KEYS) {
            texts.add(key, yolt.getAsJsonObject("messages").get(key));
        }
        copied.add("messages", texts);
        Path modFile = dir.resolve(HardcoreFile.NAME);
        Files.writeString(modFile, copied.toString());
        HardcoreFile.Result mod = HardcoreFile.start(modFile);
        assertEquals(List.of(), mod.notes());
        assertEquals(GSON.toJsonTree(new HardcoreConfig.HardcoreSection()), GSON.toJsonTree(mod.loaded().hardcore()));
        assertEquals(BloodMoonTexts.DEFAULTS, mod.loaded().texts());

        // The mod has written its own file by now, which goes into YOLT's
        JsonObject written = JsonParser.parseString(Files.readString(modFile)).getAsJsonObject();
        Path back = dir.resolve("back.json");
        Files.writeString(back, "{\"hardcore\": " + written.get("hardcore") + ", \"messages\": " + written.get("messages") + "}");
        ConfigLoader.Result read = ConfigLoader.load(back, null, true);
        assertEquals(List.of(), concerning(read.warnings()));
        assertEquals(GSON.toJsonTree(new HardcoreConfig.HardcoreSection()), GSON.toJsonTree(read.config().hardcore));
        assertEquals(BloodMoonTexts.DEFAULTS, texts(read.config()));
    }

    private void assertSameAtStart(String json) throws IOException {
        Path folder = Files.createTempDirectory(dir, "start");
        Path yoltFile = folder.resolve("you-only-live-twice.json");
        Path modFile = folder.resolve(HardcoreFile.NAME);
        Files.writeString(yoltFile, json, StandardCharsets.UTF_8);
        Files.writeString(modFile, json, StandardCharsets.UTF_8);
        ConfigLoader.Result yolt = ConfigLoader.load(yoltFile, null, true);
        HardcoreFile.Result mod = HardcoreFile.start(modFile);
        assertFalse(mod.failed(), json);
        List<String> expected = concerning(yolt.warnings());
        assertEquals(expected, concerning(mod.notes()), json);
        // The mod's only other note is the copy it keeps before a rewrite that leaves out something typed
        List<String> others = mod.notes().stream().filter(note -> !concerns(note)).toList();
        assertEquals(expected.isEmpty() ? List.of() : List.of("old file saved as yolt-hardcore.json.bak before the rewrite"),
                others, json);
        assertSameValues(yolt.config(), mod.loaded(), json);
    }

    private void assertSameOnReload(String json) throws IOException {
        Path folder = Files.createTempDirectory(dir, "reload");
        Path yoltFile = folder.resolve("you-only-live-twice.json");
        Path modFile = folder.resolve(HardcoreFile.NAME);
        Files.writeString(yoltFile, json, StandardCharsets.UTF_8);
        Files.writeString(modFile, json, StandardCharsets.UTF_8);
        ConfigLoader.Result yolt = ConfigLoader.load(yoltFile, yoltInUse(), false);
        HardcoreFile.Result mod = HardcoreFile.reload(modFile, modInUse());
        assertFalse(mod.failed(), json);
        assertEquals(concerning(yolt.warnings()), mod.notes(), json);
        assertSameValues(yolt.config(), mod.loaded(), json);
    }

    private static void assertSameValues(YoltConfig yolt, HardcoreFile.Loaded mod, String json) {
        assertEquals(GSON.toJsonTree(yolt.hardcore), GSON.toJsonTree(mod.hardcore()), json);
        assertEquals(texts(yolt), mod.texts(), json);
    }

    // Values in use that differ from the defaults, so a value kept on a reload is told from a default
    private static final String IN_USE = """
            {"slow": {"cap": 0.4}, "weight": {"enabled": true, "freeLoad": 20, "containerDepth": 5, "groups": {"c:ores": 4},
                                              "itemsEach": {}},
             "curse": {"enabled": false, "maxLostHearts": 2},
             "nutrients": {"foods": {"minecraft:bread": {"sugar": 0.5}}, "unknownFood": "even"},
             "tougherMobs": {"exclude": ["minecraft:creeper"], "damageAppliesTo": "all", "overrides": {}},
             "bloodMoon": {"barColour": "blue", "health": 2, "warnTick": 8000}, "cold": {"heatRadius": 2}}""";
    private static final BloodMoonTexts TEXTS_IN_USE = new BloodMoonTexts("Title", "Warning", "Risen", "Up", "Set", "Bar");

    private static YoltConfig yoltInUse() {
        YoltConfig config = new YoltConfig();
        config.hardcore = GSON.fromJson(IN_USE, HardcoreConfig.HardcoreSection.class);
        YoltConfig.MessagesSection messages = config.messages;
        messages.bloodMoonWarningTitle = TEXTS_IN_USE.warningTitle();
        messages.bloodMoonWarningLine = TEXTS_IN_USE.warningLine();
        messages.bloodMoonRisenLine = TEXTS_IN_USE.risenLine();
        messages.bloodMoonUpLine = TEXTS_IN_USE.upLine();
        messages.bloodMoonSetLine = TEXTS_IN_USE.setLine();
        messages.bloodMoonBarTitle = TEXTS_IN_USE.barTitle();
        return config;
    }

    private static HardcoreFile.Loaded modInUse() {
        return new HardcoreFile.Loaded(GSON.fromJson(IN_USE, HardcoreConfig.HardcoreSection.class), TEXTS_IN_USE);
    }

    private static BloodMoonTexts texts(YoltConfig config) {
        YoltConfig.MessagesSection messages = config.messages;
        return new BloodMoonTexts(messages.bloodMoonWarningTitle, messages.bloodMoonWarningLine, messages.bloodMoonRisenLine,
                messages.bloodMoonUpLine, messages.bloodMoonSetLine, messages.bloodMoonBarTitle);
    }

    // What both files have: the hardcore block and the six blood moon texts, or the messages section that holds them
    private static boolean concerns(String note) {
        return note.startsWith("hardcore.") || note.startsWith("hardcore:") || note.startsWith("messages:")
                || TEXT_KEYS.stream().anyMatch(key -> note.startsWith("messages." + key + ":"));
    }

    private static List<String> concerning(List<String> notes) {
        List<String> kept = new ArrayList<>();
        for (String note : notes) {
            if (concerns(note)) {
                kept.add(note);
            }
        }
        return kept;
    }
}
