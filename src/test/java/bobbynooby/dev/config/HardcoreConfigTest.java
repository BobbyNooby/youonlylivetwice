package bobbynooby.dev.config;

import bobbynooby.dev.hardcore.HardcoreConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HardcoreConfigTest {
    @TempDir
    Path dir;

    // A section in a section in a section in a section, as the hardcore features nest
    static class Deep {
        public ASection a = new ASection();

        public static class ASection {
            public BSection b = new BSection();
        }

        public static class BSection {
            public CSection c = new CSection();
        }

        public static class CSection {
            public DSection d = new DSection();
        }

        public static class DSection {
            public int e = 1;
        }
    }

    private static final List<String> GROUP_ORDER = List.of(
            "c:ores", "c:raw_materials", "c:ingots", "c:stones", "c:cobblestones", "c:obsidians",
            "minecraft:stone_bricks", "minecraft:logs",
            "c:storage_blocks/iron", "c:storage_blocks/gold", "c:storage_blocks/copper", "minecraft:copper",
            "c:storage_blocks/netherite", "c:storage_blocks/raw_iron", "c:storage_blocks/raw_gold",
            "c:storage_blocks/raw_copper", "c:storage_blocks",
            "c:feathers", "c:strings");

    // Paper first, then the stone-like blocks no group tag reaches
    private static final List<String> ITEM_ORDER = List.of("minecraft:paper",
            "minecraft:deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:polished_deepslate",
            "minecraft:chiseled_deepslate", "minecraft:bricks", "minecraft:smooth_stone", "minecraft:polished_andesite",
            "minecraft:polished_diorite", "minecraft:polished_granite", "minecraft:polished_tuff", "minecraft:tuff_bricks",
            "minecraft:blackstone", "minecraft:polished_blackstone", "minecraft:polished_blackstone_bricks",
            "minecraft:end_stone_bricks", "minecraft:mud_bricks", "minecraft:cracked_deepslate_bricks",
            "minecraft:cracked_deepslate_tiles", "minecraft:cracked_polished_blackstone_bricks",
            "minecraft:chiseled_polished_blackstone", "minecraft:chiseled_tuff", "minecraft:chiseled_tuff_bricks",
            "minecraft:gilded_blackstone", "minecraft:end_stone", "minecraft:cinnabar", "minecraft:polished_cinnabar",
            "minecraft:cinnabar_bricks", "minecraft:chiseled_cinnabar", "minecraft:sulfur", "minecraft:potent_sulfur",
            "minecraft:polished_sulfur", "minecraft:sulfur_bricks", "minecraft:chiseled_sulfur");

    // In order: per-item weights, anvils whatever their wear, and animal armour at its material's chestplate
    private static final List<Map.Entry<String, Double>> EACH = List.of(
            Map.entry("minecraft:elytra", 0.5), Map.entry("minecraft:mace", 1.0),
            Map.entry("minecraft:heavy_core", 1.0), Map.entry("minecraft:trident", 0.25),
            Map.entry("minecraft:anvil", 1.5), Map.entry("minecraft:chipped_anvil", 1.5),
            Map.entry("minecraft:damaged_anvil", 1.5),
            Map.entry("minecraft:leather_horse_armor", 0.15), Map.entry("minecraft:copper_horse_armor", 0.4),
            Map.entry("minecraft:iron_horse_armor", 0.6), Map.entry("minecraft:golden_horse_armor", 0.5),
            Map.entry("minecraft:diamond_horse_armor", 1.2), Map.entry("minecraft:netherite_horse_armor", 1.6),
            Map.entry("minecraft:copper_nautilus_armor", 0.4), Map.entry("minecraft:iron_nautilus_armor", 0.6),
            Map.entry("minecraft:golden_nautilus_armor", 0.5), Map.entry("minecraft:diamond_nautilus_armor", 1.2),
            Map.entry("minecraft:netherite_nautilus_armor", 1.6));

    private static final List<String> MAPS = List.of("hardcore.weight.groups", "hardcore.weight.items",
            "hardcore.weight.itemsEach", "hardcore.weight.armourFactors");

    private ConfigLoader.Result loadJson(String json) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return ConfigLoader.load(file, null, true);
    }

    private static void assertWeightDefaults(HardcoreConfig.WeightSection weight) {
        assertFalse(weight.enabled);
        assertEquals(16.0, weight.freeLoad);
        assertEquals(144.0, weight.fullLoad);
        assertEquals(0.5, weight.maxSlow);
        assertEquals(1.0, weight.defaultWeight);
        assertEquals(0.1, weight.unstackableWeight);
        assertEquals(0.25, weight.toolWeight);
        assertEquals(GROUP_ORDER, new ArrayList<>(weight.groups.keySet()));
        assertEquals(3.0, weight.groups.get("c:ores"));
        assertEquals(3.0, weight.groups.get("minecraft:logs"));
        assertEquals(27.0, weight.groups.get("c:storage_blocks/iron"));
        assertEquals(27.0, weight.groups.get("c:storage_blocks/raw_copper"));
        assertEquals(27.0, weight.groups.get("minecraft:copper"));
        assertEquals(9.0, weight.groups.get("c:storage_blocks"));
        assertEquals(0.25, weight.groups.get("c:strings"));
        assertEquals(ITEM_ORDER, new ArrayList<>(weight.items.keySet()));
        assertEquals(0.25, weight.items.get("minecraft:paper"));
        for (String stone : ITEM_ORDER.subList(1, ITEM_ORDER.size())) {
            assertEquals(3.0, weight.items.get(stone), stone);
        }
        assertEquals(EACH, new ArrayList<>(weight.itemsEach.entrySet()));
        assertEquals(List.of("leather", "copper", "chainmail", "gold", "iron", "turtle_scute", "diamond", "netherite"),
                new ArrayList<>(weight.armourFactors.keySet()));
        assertEquals(Map.of("leather", 0.05, "copper", 0.1, "chainmail", 0.1, "gold", 0.1, "iron", 0.1,
                "turtle_scute", 0.1, "diamond", 0.15, "netherite", 0.2), weight.armourFactors);
        assertTrue(weight.countContainers);
        assertEquals(1.0, weight.containerContents);
        assertEquals(3, weight.containerDepth);
        assertTrue(weight.countCursorAndGrid);
        assertEquals(0.2, weight.heavyJumpExhaustion);
    }

    @Test
    void config1WeightDefaultsMatchTheSpec() throws IOException {
        YoltConfig fresh = new YoltConfig();
        assertEquals(0.5, fresh.hardcore.slow.cap);
        assertEquals(0.05, fresh.hardcore.slow.step);
        assertWeightDefaults(fresh.hardcore.weight);

        // Written out and read back, the maps keep the order, since the first matching group wins
        Path file = dir.resolve("config.json");
        ConfigLoader.load(file, null, true);
        ConfigLoader.Result reread = ConfigLoader.load(file, null, true);
        assertEquals(List.of(), reread.warnings());
        assertWeightDefaults(reread.config().hardcore.weight);
    }

    @Test
    void config1EveryHardcoreKeyIsFoundByTheWalker() {
        ConfigKey freeLoad = ConfigKeys.find("hardcore.weight.freeLoad").orElseThrow(() -> new AssertionError("no freeLoad"));
        assertEquals(ConfigKey.Kind.DOUBLE, freeLoad.kind());
        assertTrue(ConfigKeys.find("hardcore.slow.cap").isPresent());
        assertTrue(ConfigKeys.find("hardcore.slow.step").isPresent());
        assertTrue(ConfigKeys.find("hardcore.weight.defaultWeight").isPresent());
        assertEquals(ConfigKey.Kind.INT, ConfigKeys.find("hardcore.weight.containerDepth").orElseThrow().kind());
        assertEquals(ConfigKey.Kind.BOOLEAN, ConfigKeys.find("hardcore.weight.enabled").orElseThrow().kind());
        assertEquals("16.0", freeLoad.show(new YoltConfig()));
    }

    @Test
    void config1WalkerGoesFiveLevelsDeep() {
        List<String> paths = ConfigKeys.of(Deep.class).stream().map(ConfigKey::path).toList();
        assertEquals(List.of("a.b.c.d.e"), paths);
    }

    @Test
    void config1MapsAreFileOnly() {
        for (String path : MAPS) {
            assertTrue(ConfigKeys.fileOnly(path), path);
            assertTrue(ConfigKeys.find(path).isEmpty(), path);
        }
    }

    @Test
    void configMapFromTheFileReplacesTheDefault() throws IOException {
        ConfigLoader.Result r = loadJson("{\"hardcore\":{\"weight\":{\"groups\":{\"c:ores\":5.0}}}}");
        assertEquals(Map.of("c:ores", 5.0), r.config().hardcore.weight.groups);
        assertEquals(List.of(), r.warnings());
        // The other maps keep their defaults
        assertEquals(new HardcoreConfig.WeightSection().items, r.config().hardcore.weight.items);
    }

    @Test
    void configMapWithABadValueFallsBackAlone() throws IOException {
        ConfigLoader.Result r = loadJson("{\"hardcore\":{\"weight\":{\"freeLoad\":20,\"groups\":{\"c:ores\":\"heavy\"}}}}");
        assertEquals(GROUP_ORDER, new ArrayList<>(r.config().hardcore.weight.groups.keySet()));
        assertEquals(3.0, r.config().hardcore.weight.groups.get("c:ores"));
        assertEquals(20.0, r.config().hardcore.weight.freeLoad);
        assertEquals(1, r.warnings().stream().filter(w -> w.contains("hardcore.weight.groups")).count(), r.warnings().toString());
        assertTrue(r.warnings().contains("hardcore.weight.groups: expected an object of numbers, using default"), r.warnings().toString());
        assertFalse(r.unparseable());

        // A number where the map goes falls back the same way
        ConfigLoader.Result notAMap = loadJson("{\"hardcore\":{\"weight\":{\"freeLoad\":20,\"items\":3}}}");
        assertEquals(new HardcoreConfig.WeightSection().items, notAMap.config().hardcore.weight.items);
        assertEquals(20.0, notAMap.config().hardcore.weight.freeLoad);
        assertTrue(notAMap.warnings().contains("hardcore.weight.items: expected an object of numbers, using default"),
                notAMap.warnings().toString());
    }

    @Test
    void config1ChecksFallBack() throws IOException {
        ConfigLoader.Result r = loadJson("{\"hardcore\":{\"slow\":{\"cap\":1.5},"
                + "\"weight\":{\"freeLoad\":60,\"fullLoad\":56,\"containerContents\":-1}}}");
        HardcoreConfig.HardcoreSection hardcore = r.config().hardcore;
        assertEquals(16.0, hardcore.weight.freeLoad);
        assertEquals(144.0, hardcore.weight.fullLoad);
        assertEquals(0.5, hardcore.slow.cap);
        assertEquals(1.0, hardcore.weight.containerContents);
        List<String> expected = List.of(
                "hardcore.slow.cap: must be 0 to 1, using default",
                "hardcore.weight.freeLoad: must be below hardcore.weight.fullLoad, using default",
                "hardcore.weight.fullLoad: must be above hardcore.weight.freeLoad, using default",
                "hardcore.weight.containerContents: must be 0 to 1, using default");
        for (String warning : expected) {
            assertTrue(r.warnings().contains(warning), "no \"" + warning + "\" in " + r.warnings());
        }
    }

    private static ConfigKey key(String path) {
        return ConfigKeys.find(path).orElseThrow(() -> new AssertionError("no key " + path));
    }

    // A cap of 0 is how a server keeps the other hardcore rules without any slowdown, so the step rule doesn't send it
    // back to 0.5. A cap just above 0 still needs a step that fits under it.
    @Test
    void config1SlowCapZeroTurnsTheSlowdownsOff() throws IOException {
        ConfigLoader.Result r = loadJson("{\"hardcore\":{\"slow\":{\"cap\":0}}}");
        assertEquals(0.0, r.config().hardcore.slow.cap);
        assertEquals(0.05, r.config().hardcore.slow.step);
        assertEquals(List.of(), r.warnings());

        Path file = dir.resolve("config.json");
        ConfigLoader.Change set = ConfigLoader.setKey(file, new YoltConfig(), key("hardcore.slow.cap"), new JsonPrimitive(0.0));
        assertNull(set.problem());
        assertEquals(0.0, set.config().hardcore.slow.cap);

        ConfigLoader.Result tiny = loadJson("{\"hardcore\":{\"slow\":{\"cap\":0.01}}}");
        assertEquals(0.5, tiny.config().hardcore.slow.cap);
        assertTrue(tiny.warnings().contains("hardcore.slow.cap: must be at least hardcore.slow.step, using default"),
                tiny.warnings().toString());
    }

    // Set from a config that was fine, either switch or either number can make the curse outlast the limp. Each set goes
    // through, keeps both features on, and the mix gets a notice instead.
    @Test
    void config1CommandAllowsACurseThatOutlastsTheLimp() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{}");

        // 8 hearts lost is fine while the limp is off
        YoltConfig curseOnly = new YoltConfig();
        curseOnly.hardcore.curse.enabled = true;
        curseOnly.hardcore.curse.maxLostHearts = 8;
        assertNull(ConfigChecks.curseOutlastsLimp(curseOnly));
        assertCrossedAfterSet(file, curseOnly, "hardcore.limp.enabled", new JsonPrimitive(true));

        // The curse ships on, so it is turned off here to be the switch that goes on
        YoltConfig limpOnly = new YoltConfig();
        limpOnly.hardcore.curse.enabled = false;
        limpOnly.hardcore.limp.enabled = true;
        limpOnly.hardcore.curse.maxLostHearts = 8;
        assertCrossedAfterSet(file, limpOnly, "hardcore.curse.enabled", new JsonPrimitive(true));

        YoltConfig both = new YoltConfig();
        both.hardcore.curse.enabled = true;
        both.hardcore.limp.enabled = true;
        assertNull(ConfigChecks.curseOutlastsLimp(both), "the shipped numbers with both on");
        // 3 hearts left against a recovery line of 3
        assertCrossedAfterSet(file, both, "hardcore.curse.maxLostHearts", new JsonPrimitive(7));
        assertCrossedAfterSet(file, both, "hardcore.limp.recoverAtHearts", new JsonPrimitive(8.0));

        // One heart more than the line has no notice
        ConfigLoader.Change fine = ConfigLoader.setKey(file, both, key("hardcore.curse.maxLostHearts"), new JsonPrimitive(6));
        assertNull(fine.problem());
        assertEquals(6, fine.config().hardcore.curse.maxLostHearts);
        assertNull(ConfigChecks.curseOutlastsLimp(fine.config()));
    }

    private static void assertCrossedAfterSet(Path file, YoltConfig live, String path, JsonPrimitive value) {
        ConfigLoader.Change change = ConfigLoader.setKey(file, live, key(path), value);
        assertNull(change.problem(), path);
        assertEquals(value.getAsString(), key(path).show(change.config()), path);
        assertTrue(change.config().hardcore.curse.enabled, path);
        assertTrue(change.config().hardcore.limp.enabled, path);
        String notice = ConfigChecks.curseOutlastsLimp(change.config());
        assertNotNull(notice, path);
        assertTrue(notice.contains("This is allowed"), notice);
    }

    // Every key the table below must hold: the hardcore layer's and the resource pack's
    private static List<ConfigKey> specKeys() {
        return ConfigKeys.all().stream()
                .filter(k -> k.path().startsWith("hardcore.") || k.path().startsWith("resourcePack."))
                .toList();
    }

    // Section 6's defaults as the file writes them, one line a key, maps and lists left out. Some differ from its text
    // on purpose: "default" is defaultWeight, which Java can name, and healEveryDays is a decimal.
    private static Map<String, String> specDefaults() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("hardcore.slow.cap", "0.5");
        d.put("hardcore.slow.step", "0.05");
        d.put("hardcore.weight.enabled", "false");
        d.put("hardcore.weight.freeLoad", "16.0");
        d.put("hardcore.weight.fullLoad", "144.0");
        d.put("hardcore.weight.maxSlow", "0.5");
        d.put("hardcore.weight.defaultWeight", "1.0");
        d.put("hardcore.weight.unstackableWeight", "0.1");
        d.put("hardcore.weight.toolWeight", "0.25");
        d.put("hardcore.weight.countContainers", "true");
        d.put("hardcore.weight.containerContents", "1.0");
        d.put("hardcore.weight.containerDepth", "3");
        d.put("hardcore.weight.countCursorAndGrid", "true");
        d.put("hardcore.weight.heavyJumpExhaustion", "0.2");
        d.put("hardcore.legs.enabled", "false");
        d.put("hardcore.legs.breakAtDamage", "6.0");
        d.put("hardcore.legs.heavyLandings", "false");
        d.put("hardcore.legs.breakAtDamageFullLoad", "4.0");
        d.put("hardcore.legs.includeElytraCrash", "false");
        d.put("hardcore.legs.slow", "0.2");
        d.put("hardcore.legs.healPoints", "30");
        d.put("hardcore.legs.healTicks", "80");
        d.put("hardcore.legs.healTicksSaturated", "40");
        d.put("hardcore.legs.exhaustionPerPoint", "3.0");
        d.put("hardcore.legs.healthFirst", "true");
        d.put("hardcore.legs.regenEffectHeals", "true");
        d.put("hardcore.legs.healTicksNoRegenRule", "1200");
        d.put("hardcore.legs.bodiesCanBreakLegs", "false");
        d.put("hardcore.limp.enabled", "false");
        d.put("hardcore.limp.belowHearts", "2.0");
        d.put("hardcore.limp.recoverAtHearts", "3.0");
        d.put("hardcore.limp.slow", "0.2");
        d.put("hardcore.limp.countAbsorption", "false");
        d.put("hardcore.bedRest.enabled", "false");
        d.put("hardcore.bedRest.allowDaytime", "true");
        d.put("hardcore.bedRest.healEveryTicks", "50");
        d.put("hardcore.bedRest.exhaustionPerHeal", "0.0");
        d.put("hardcore.bedRest.legHealSpeed", "2.0");
        d.put("hardcore.curse.enabled", "true");
        d.put("hardcore.curse.heartsPerDeath", "1");
        d.put("hardcore.curse.heartsPerTotem", "1");
        d.put("hardcore.curse.maxLostHearts", "4");
        d.put("hardcore.curse.healHearts", "1");
        d.put("hardcore.curse.healEveryDays", "3.0");
        d.put("hardcore.curse.countAfk", "true");
        d.put("hardcore.curse.afkMinutes", "5");
        d.put("hardcore.curse.reviveRefundHearts", "0");
        d.put("hardcore.curse.unkillRefunds", "true");
        d.put("hardcore.nutrients.enabled", "false");
        d.put("hardcore.nutrients.gainPerPoint", "0.03");
        d.put("hardcore.nutrients.decayPerPoint", "0.01");
        d.put("hardcore.nutrients.startLevel", "0.6");
        d.put("hardcore.nutrients.deficientBelow", "0.25");
        d.put("hardcore.nutrients.penalties.protein.healingMultiplier", "0.5");
        d.put("hardcore.nutrients.penalties.sugar.sprintSlow", "0.1");
        d.put("hardcore.nutrients.penalties.vitamins.miningSlow", "0.15");
        d.put("hardcore.nutrients.unknownFood", "none");
        d.put("hardcore.hungerTax.multiplier", "1.0");
        d.put("hardcore.mending.enabled", "true");
        d.put("hardcore.mending.durabilityPerXp", "0.4");
        d.put("hardcore.anvil.enabled", "true");
        d.put("hardcore.anvil.enchantCost", "6");
        d.put("hardcore.anvil.repairCost", "5");
        d.put("hardcore.anvil.renameCost", "1");
        d.put("hardcore.anvil.breakChance", "0.12");
        d.put("hardcore.elytra.enabled", "false");
        d.put("hardcore.elytra.glideGravityMultiplier", "0.6");
        d.put("hardcore.elytra.rocketCutoffSpeed", "20.0");
        d.put("hardcore.tougherMobs.enabled", "false");
        d.put("hardcore.tougherMobs.health", "1.5");
        d.put("hardcore.tougherMobs.damage", "1.25");
        d.put("hardcore.tougherMobs.speed", "1.1");
        d.put("hardcore.tougherMobs.damageAppliesTo", "players");
        d.put("hardcore.tougherMobs.includeSpawners", "true");
        d.put("hardcore.tougherMobs.includeTrialSpawners", "false");
        d.put("hardcore.tougherMobs.includeCommandsAndEggs", "false");
        d.put("hardcore.relentlessMobs.enabled", "false");
        d.put("hardcore.relentlessMobs.followRange", "1.5");
        d.put("hardcore.relentlessMobs.doorsAnyDifficulty", "true");
        d.put("hardcore.relentlessMobs.doorBreakerChance", "0.25");
        d.put("hardcore.relentlessMobs.dropBrokenDoor", "true");
        d.put("hardcore.bloodMoon.enabled", "false");
        d.put("hardcore.bloodMoon.chance", "0.1");
        d.put("hardcore.bloodMoon.minNightsBetween", "5");
        d.put("hardcore.bloodMoon.fullMoonsOnly", "false");
        d.put("hardcore.bloodMoon.graceDays", "8");
        d.put("hardcore.bloodMoon.startTick", "12542");
        d.put("hardcore.bloodMoon.endTick", "23460");
        d.put("hardcore.bloodMoon.warnTick", "9000");
        d.put("hardcore.bloodMoon.health", "1.25");
        d.put("hardcore.bloodMoon.damage", "1.0");
        d.put("hardcore.bloodMoon.speed", "1.1");
        d.put("hardcore.bloodMoon.spawnCap", "2.0");
        d.put("hardcore.bloodMoon.capBoostNearPlayerBodies", "false");
        d.put("hardcore.bloodMoon.canSleepThrough", "false");
        d.put("hardcore.bloodMoon.armourChance", "0.35");
        d.put("hardcore.bloodMoon.enchantChance", "0.25");
        d.put("hardcore.bloodMoon.xp", "2.0");
        d.put("hardcore.bloodMoon.extraLootRolls", "1");
        d.put("hardcore.bloodMoon.chargedCreeperChance", "0.0");
        d.put("hardcore.bloodMoon.bossBar", "true");
        d.put("hardcore.bloodMoon.darkenSky", "true");
        d.put("hardcore.bloodMoon.fog", "false");
        d.put("hardcore.bloodMoon.barColour", "red");
        d.put("hardcore.bloodMoon.warning.title", "true");
        d.put("hardcore.bloodMoon.warning.chat", "true");
        d.put("hardcore.bloodMoon.warning.sound", "true");
        d.put("hardcore.cold.enabled", "false");
        d.put("hardcore.cold.chillPerSecond", "0.01");
        d.put("hardcore.cold.warmPerSecond", "0.03");
        d.put("hardcore.cold.shelteredBelowSkyLight", "8");
        d.put("hardcore.cold.heatRadius", "4");
        d.put("hardcore.cold.leatherPerPiece", "0.25");
        d.put("hardcore.cold.wetMultiplier", "2.0");
        d.put("hardcore.cold.waterMultiplier", "4.0");
        d.put("hardcore.cold.maxSlow", "0.2");
        d.put("hardcore.cold.freezeDamage", "true");
        d.put("hardcore.cold.damageEverySeconds", "2");
        d.put("hardcore.cold.canKill", "false");
        d.put("hardcore.cold.shiverExhaustionPerSecond", "0.05");
        d.put("resourcePack.reviveTexture", "false");
        d.put("resourcePack.reviveModel", "you-only-live-twice:revive_paper");
        return d;
    }

    @Test
    void config1EveryHardcoreKeyHasItsSpecDefault() {
        Map<String, String> expected = specDefaults();
        YoltConfig fresh = new YoltConfig();
        Map<String, String> actual = new LinkedHashMap<>();
        for (ConfigKey key : specKeys()) {
            actual.put(key.path(), key.show(fresh));
        }
        // Equal as maps, so a key without a line here fails, and so does a line with no key
        assertEquals(expected, actual);
        // The deepest key, five levels down
        assertEquals("0.5", key("hardcore.nutrients.penalties.protein.healingMultiplier").show(fresh));

        // The foods table, spot-checked
        Map<String, Map<String, Double>> foods = fresh.hardcore.nutrients.foods;
        assertEquals(41, foods.size());
        assertEquals(Map.of("protein", 1.0, "vitamins", 0.25), foods.get("minecraft:cooked_beef"));
        assertEquals(Map.of("protein", 0.5), foods.get("minecraft:beef"));
        assertEquals(Map.of("protein", 0.75, "vitamins", 0.5), foods.get("minecraft:cooked_cod"));
        assertEquals(Map.of("protein", 0.25), foods.get("minecraft:rotten_flesh"));
        assertEquals(Map.of("vitamins", 1.0, "sugar", 0.5), foods.get("minecraft:apple"));
        assertEquals(Map.of("sugar", 1.0, "vitamins", 0.25), foods.get("minecraft:baked_potato"));
        assertEquals(Map.of("sugar", 1.0, "protein", 0.5, "vitamins", 0.25), foods.get("minecraft:cake"));
        assertEquals(Map.of("protein", 0.5, "vitamins", 0.5, "sugar", 0.5), foods.get("minecraft:rabbit_stew"));
        assertEquals(Map.of("sugar", 1.0, "vitamins", 0.5, "protein", 0.25), foods.get("minecraft:pumpkin_pie"));
        assertEquals(Map.of("vitamins", 0.5, "protein", 0.25), foods.get("minecraft:mushroom_stew"));
        assertEquals(Map.of("protein", 1.0, "vitamins", 1.0, "sugar", 1.0), foods.get("minecraft:enchanted_golden_apple"));
        assertEquals(Map.of(), foods.get("minecraft:spider_eye"));
        assertEquals(Map.of(), foods.get("minecraft:pufferfish"));
        assertEquals(Map.of(), foods.get("minecraft:poisonous_potato"));
        assertEquals(List.of("minecraft:wither", "minecraft:ender_dragon", "minecraft:elder_guardian", "minecraft:warden"),
                fresh.hardcore.tougherMobs.exclude);
        assertEquals(Map.of("minecraft:ghast", Map.of("health", 2.0), "minecraft:phantom", Map.of("health", 1.0, "damage", 1.0, "speed", 1.0),
                "minecraft:creeper", Map.of("damage", 1.0)), fresh.hardcore.tougherMobs.overrides);
        assertEquals(List.of("minecraft:warden"), fresh.hardcore.relentlessMobs.exclude);
    }

    // Named in the file as the other enums are, lowercase, and offered as such by the command
    @Test
    void bloodMoonBarColourReadsLowercase() throws IOException {
        assertEquals(List.of("pink", "blue", "red", "green", "yellow", "purple", "white"), key("hardcore.bloodMoon.barColour").choices());
        assertEquals(HardcoreConfig.BarColour.BLUE,
                loadJson("{\"hardcore\":{\"bloodMoon\":{\"barColour\":\"blue\"}}}").config().hardcore.bloodMoon.barColour);
        ConfigLoader.Result unknown = loadJson("{\"hardcore\":{\"bloodMoon\":{\"barColour\":\"maroon\"}}}");
        assertEquals(HardcoreConfig.BarColour.RED, unknown.config().hardcore.bloodMoon.barColour);
        assertTrue(unknown.warnings().contains("hardcore.bloodMoon.barColour: unknown value, using default"), unknown.warnings().toString());

        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"hardcore\":{\"bloodMoon\":{\"barColour\":\"maroon\"}}}");
        YoltConfig live = new YoltConfig();
        live.hardcore.bloodMoon.barColour = HardcoreConfig.BarColour.PURPLE;
        ConfigLoader.Result reloaded = ConfigLoader.load(file, live, false);
        assertEquals(HardcoreConfig.BarColour.PURPLE, reloaded.config().hardcore.bloodMoon.barColour);
        assertEquals(List.of("hardcore.bloodMoon.barColour: unknown value, using the current value"), reloaded.warnings());
    }

    // One of each kind of label; the full list of keys that wait is ConfigKeysTest's
    @Test
    void config1AppliesLabels() {
        assertEquals(Applies.When.NEW_DEATHS, key("hardcore.curse.heartsPerDeath").when());
        assertEquals(Applies.When.MOBS_LOAD, key("hardcore.tougherMobs.health").when());
        assertEquals(Applies.When.NOW, key("hardcore.weight.freeLoad").when());
    }

    // Every hardcore and resource pack value takes its default again, maps included, and only the expected warnings come
    // out, in order
    private static void assertFellBack(ConfigLoader.Result r, List<String> expected) {
        List<String> hardcore = r.warnings().stream()
                .filter(w -> w.startsWith("hardcore.") || w.startsWith("resourcePack."))
                .toList();
        assertEquals(expected.stream().map(w -> w + ", using default").toList(), hardcore);
        YoltConfig fresh = new YoltConfig();
        for (ConfigKey key : specKeys()) {
            assertEquals(key.show(fresh), key.show(r.config()), key.path());
        }
        HardcoreConfig.WeightSection weight = r.config().hardcore.weight;
        assertEquals(fresh.hardcore.weight.groups, weight.groups);
        assertEquals(fresh.hardcore.weight.items, weight.items);
        assertEquals(fresh.hardcore.weight.itemsEach, weight.itemsEach);
        assertEquals(fresh.hardcore.weight.armourFactors, weight.armourFactors);
        assertEquals(fresh.hardcore.nutrients.foods, r.config().hardcore.nutrients.foods);
        assertEquals(fresh.hardcore.tougherMobs.overrides, r.config().hardcore.tougherMobs.overrides);
    }

    @Test
    void config1EveryLoadCheckFallsBack() throws IOException {
        // One bad value for each check on a single key
        ConfigLoader.Result single = loadJson("""
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
                },
                "resourcePack": {"reviveModel": "Revive Paper"}}""");
        assertFellBack(single, List.of(
                "hardcore.slow.cap: must be 0 to 1",
                "hardcore.slow.step: must be above 0",
                "hardcore.weight.freeLoad: must be 0 or more",
                "hardcore.weight.fullLoad: must be 0 or more",
                "hardcore.weight.maxSlow: must be 0 to 1",
                "hardcore.weight.defaultWeight: must be 0 or more",
                "hardcore.weight.unstackableWeight: must be 0 or more",
                "hardcore.weight.toolWeight: must be 0 or more",
                "hardcore.weight.groups: every weight must be 0 or more",
                "hardcore.weight.items: every weight must be 0 or more",
                "hardcore.weight.itemsEach: every weight must be 0 or more",
                "hardcore.weight.armourFactors: every factor must be 0 or more",
                "hardcore.weight.containerContents: must be 0 to 1",
                "hardcore.weight.containerDepth: must be 0 to 8",
                "hardcore.weight.heavyJumpExhaustion: must be 0 or more",
                "hardcore.legs.breakAtDamage: must be 0 or more",
                "hardcore.legs.breakAtDamageFullLoad: must be 0 or more",
                "hardcore.legs.slow: must be 0 to 1",
                "hardcore.legs.healPoints: must be 0 or more",
                "hardcore.legs.healTicks: must be 0 or more",
                "hardcore.legs.healTicksSaturated: must be 0 or more",
                "hardcore.legs.healTicksNoRegenRule: must be 0 or more",
                "hardcore.legs.exhaustionPerPoint: must be 0 or more",
                "hardcore.limp.belowHearts: must be more than 0",
                "hardcore.limp.recoverAtHearts: must be more than 0",
                "hardcore.limp.slow: must be 0 to 1",
                "hardcore.bedRest.healEveryTicks: must be 0 or more",
                "hardcore.bedRest.exhaustionPerHeal: must be 0 or more",
                "hardcore.bedRest.legHealSpeed: must be 0 or more",
                "hardcore.curse.heartsPerDeath: must be 0 or more",
                "hardcore.curse.heartsPerTotem: must be 0 or more",
                "hardcore.curse.maxLostHearts: must be 0 or more",
                "hardcore.curse.healHearts: must be 0 or more",
                "hardcore.curse.healEveryDays: must be above 0",
                "hardcore.curse.afkMinutes: must be 1 or more",
                "hardcore.curse.reviveRefundHearts: must be 0 or more",
                "hardcore.nutrients.gainPerPoint: must be 0 or more",
                "hardcore.nutrients.decayPerPoint: must be 0 or more",
                "hardcore.nutrients.startLevel: must be 0 to 1",
                "hardcore.nutrients.deficientBelow: must be 0 to 1",
                "hardcore.nutrients.penalties.protein.healingMultiplier: must be 0 or more",
                "hardcore.nutrients.penalties.sugar.sprintSlow: must be 0 to 1",
                "hardcore.nutrients.penalties.vitamins.miningSlow: must be 0 to 1",
                "hardcore.nutrients.foods: every share must be 0 to 1 and name protein, vitamins or sugar",
                "hardcore.nutrients.unknownFood: unknown value",
                "hardcore.hungerTax.multiplier: must be 0 or more",
                "hardcore.mending.durabilityPerXp: must be 0 or more",
                "hardcore.anvil.enchantCost: must be 1 to 39",
                "hardcore.anvil.repairCost: must be 1 to 39",
                "hardcore.anvil.renameCost: must be 1 to 39",
                "hardcore.anvil.breakChance: must be 0 to 1",
                "hardcore.elytra.glideGravityMultiplier: must be 0 or more",
                "hardcore.elytra.rocketCutoffSpeed: must be 0 or more",
                "hardcore.tougherMobs.health: must be above 0",
                "hardcore.tougherMobs.damage: must be 0 or more",
                "hardcore.tougherMobs.speed: must be 0 or more",
                "hardcore.tougherMobs.damageAppliesTo: unknown value",
                "hardcore.tougherMobs.overrides: every value must name health, damage or speed and be 0 or more, with health above 0",
                "hardcore.relentlessMobs.followRange: must be above 0",
                "hardcore.relentlessMobs.doorBreakerChance: must be 0 to 1",
                "hardcore.bloodMoon.chance: must be 0 to 1",
                "hardcore.bloodMoon.minNightsBetween: must be 0 or more",
                "hardcore.bloodMoon.graceDays: must be 0 or more",
                "hardcore.bloodMoon.health: must be above 0",
                "hardcore.bloodMoon.damage: must be 0 or more",
                "hardcore.bloodMoon.speed: must be 0 or more",
                "hardcore.bloodMoon.spawnCap: must be 0 or more",
                "hardcore.bloodMoon.armourChance: must be 0 to 1",
                "hardcore.bloodMoon.enchantChance: must be 0 to 1",
                "hardcore.bloodMoon.xp: must be 0 or more",
                "hardcore.bloodMoon.extraLootRolls: must be 0 or more",
                "hardcore.bloodMoon.chargedCreeperChance: must be 0 to 1",
                "hardcore.bloodMoon.barColour: unknown value",
                "hardcore.cold.chillPerSecond: must be 0 or more",
                "hardcore.cold.warmPerSecond: must be 0 or more",
                "hardcore.cold.shelteredBelowSkyLight: must be 0 to 15",
                "hardcore.cold.heatRadius: must be 0 to 8",
                "hardcore.cold.leatherPerPiece: must be 0 to 1",
                "hardcore.cold.wetMultiplier: must be 0 or more",
                "hardcore.cold.waterMultiplier: must be 0 or more",
                "hardcore.cold.maxSlow: must be 0 to 1",
                "hardcore.cold.damageEverySeconds: must be 1 or more",
                "hardcore.cold.shiverExhaustionPerSecond: must be 0 or more",
                "resourcePack.reviveModel: must be an id such as you-only-live-twice:revive_paper"));

        // Each pair fine on its own but wrong together, so both of its keys go back
        ConfigLoader.Result pairs = loadJson("""
                {"hardcore": {
                  "slow": {"cap": 0.1, "step": 0.2},
                  "weight": {"freeLoad": 60, "fullLoad": 56},
                  "legs": {"breakAtDamage": 3, "breakAtDamageFullLoad": 5},
                  "limp": {"belowHearts": 4, "recoverAtHearts": 3},
                  "bloodMoon": {"warnTick": 13000}
                }}""");
        assertFellBack(pairs, List.of(
                "hardcore.slow.step: must be at most hardcore.slow.cap",
                "hardcore.slow.cap: must be at least hardcore.slow.step",
                "hardcore.weight.freeLoad: must be below hardcore.weight.fullLoad",
                "hardcore.weight.fullLoad: must be above hardcore.weight.freeLoad",
                "hardcore.legs.breakAtDamageFullLoad: must be at most hardcore.legs.breakAtDamage",
                "hardcore.legs.breakAtDamage: must be at least hardcore.legs.breakAtDamageFullLoad",
                "hardcore.limp.belowHearts: must be less than hardcore.limp.recoverAtHearts",
                "hardcore.limp.recoverAtHearts: must be more than hardcore.limp.belowHearts",
                "hardcore.bloodMoon.warnTick: must keep 0 <= warnTick < startTick < endTick <= 23999",
                "hardcore.bloodMoon.startTick: must keep 0 <= warnTick < startTick < endTick <= 23999",
                "hardcore.bloodMoon.endTick: must keep 0 <= warnTick < startTick < endTick <= 23999"));

        // The curse against the limp, from either side: the mix is kept as typed, with both features on, and only gets a
        // notice
        for (String json : List.of(
                "{\"hardcore\":{\"curse\":{\"enabled\":true,\"maxLostHearts\":8},\"limp\":{\"enabled\":true}}}",
                "{\"hardcore\":{\"curse\":{\"enabled\":true},\"limp\":{\"enabled\":true,\"recoverAtHearts\":8}}}")) {
            ConfigLoader.Result r = loadJson(json);
            assertEquals(List.of(), r.warnings().stream().filter(w -> w.startsWith("hardcore.")).toList(), json);
            YoltConfig typed = new Gson().fromJson(json, YoltConfig.class);
            assertEquals(typed.hardcore.curse.maxLostHearts, r.config().hardcore.curse.maxLostHearts, json);
            assertEquals(typed.hardcore.limp.recoverAtHearts, r.config().hardcore.limp.recoverAtHearts, json);
            assertTrue(r.config().hardcore.curse.enabled, json);
            assertTrue(r.config().hardcore.limp.enabled, json);
            assertNotNull(ConfigChecks.curseOutlastsLimp(r.config()), json);
        }

        // Right on the edges nothing falls back: the same damage twice, and one heart more than the limp's line
        ConfigLoader.Result edges = loadJson("{\"hardcore\":{\"legs\":{\"breakAtDamage\":5,\"breakAtDamageFullLoad\":5},"
                + "\"curse\":{\"enabled\":true,\"maxLostHearts\":6},\"limp\":{\"enabled\":true}}}");
        assertEquals(List.of(), edges.warnings().stream().filter(w -> w.startsWith("hardcore.")).toList());
        assertEquals(6, edges.config().hardcore.curse.maxLostHearts);
        assertNull(ConfigChecks.curseOutlastsLimp(edges.config()));
    }

    // A file holding each value at its dotted path, as an admin would type them, read at startup
    private ConfigLoader.Result loadValues(Map<String, String> values) throws IOException {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, String> value : values.entrySet()) {
            JsonObject section = root;
            String[] parts = value.getKey().split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                if (!section.has(parts[i])) {
                    section.add(parts[i], new JsonObject());
                }
                section = section.getAsJsonObject(parts[i]);
            }
            section.add(parts[parts.length - 1], JsonParser.parseString(value.getValue()));
        }
        return loadJson(root.toString());
    }

    // Each value right on the edge of what its check allows, alone or with the pair it has to fit, is read as typed
    // with no warning at all. One check written with < for <= would send it back.
    @Test
    void config1EveryLoadCheckKeepsItsInsideEdges() throws IOException {
        List<String> shares = List.of("hardcore.slow.cap", "hardcore.weight.maxSlow", "hardcore.weight.containerContents",
                "hardcore.legs.slow", "hardcore.limp.slow", "hardcore.nutrients.startLevel", "hardcore.nutrients.deficientBelow",
                "hardcore.nutrients.penalties.sugar.sprintSlow", "hardcore.nutrients.penalties.vitamins.miningSlow",
                "hardcore.anvil.breakChance", "hardcore.relentlessMobs.doorBreakerChance", "hardcore.bloodMoon.chance",
                "hardcore.bloodMoon.armourChance", "hardcore.bloodMoon.enchantChance", "hardcore.bloodMoon.chargedCreeperChance",
                "hardcore.cold.leatherPerPiece", "hardcore.cold.maxSlow");
        List<Map<String, String>> cases = new ArrayList<>();
        for (String share : shares) {
            cases.add(Map.of(share, "0"));
            cases.add(Map.of(share, "1"));
        }
        for (String aboveZero : List.of("hardcore.slow.step", "hardcore.curse.healEveryDays", "hardcore.tougherMobs.health",
                "hardcore.relentlessMobs.followRange", "hardcore.bloodMoon.health", "hardcore.limp.belowHearts")) {
            cases.add(Map.of(aboveZero, "1e-9"));
        }
        for (String price : List.of("hardcore.anvil.enchantCost", "hardcore.anvil.repairCost", "hardcore.anvil.renameCost")) {
            cases.add(Map.of(price, "1"));
            cases.add(Map.of(price, "39"));
        }
        cases.add(Map.of("hardcore.weight.containerDepth", "0"));
        cases.add(Map.of("hardcore.weight.containerDepth", "8"));
        cases.add(Map.of("hardcore.cold.shelteredBelowSkyLight", "0"));
        cases.add(Map.of("hardcore.cold.shelteredBelowSkyLight", "15"));
        cases.add(Map.of("hardcore.cold.heatRadius", "0"));
        cases.add(Map.of("hardcore.cold.heatRadius", "8"));
        cases.add(Map.of("hardcore.cold.damageEverySeconds", "1"));
        cases.add(Map.of("hardcore.curse.afkMinutes", "1"));
        cases.add(Map.of("hardcore.bloodMoon.warnTick", "0"));
        cases.add(Map.of("hardcore.bloodMoon.endTick", "23999"));
        cases.add(Map.of("hardcore.bloodMoon.warnTick", "0", "hardcore.bloodMoon.startTick", "1", "hardcore.bloodMoon.endTick", "2"));
        cases.add(Map.of("hardcore.slow.cap", "1", "hardcore.slow.step", "1"));
        cases.add(Map.of("hardcore.limp.belowHearts", "0.5", "hardcore.limp.recoverAtHearts", "0.51"));
        cases.add(Map.of("hardcore.legs.breakAtDamage", "0", "hardcore.legs.breakAtDamageFullLoad", "0"));
        cases.add(Map.of("hardcore.weight.freeLoad", "0", "hardcore.weight.fullLoad", "1e-9"));

        for (Map<String, String> values : cases) {
            ConfigLoader.Result r = loadValues(values);
            assertEquals(List.of(), r.warnings(), values.toString());
            for (Map.Entry<String, String> value : values.entrySet()) {
                assertEquals(Double.parseDouble(value.getValue()), key(value.getKey()).json(r.config()).getAsDouble(),
                        values.toString());
            }
        }
    }

    // One step past each edge, or a pair that only just touches, falls back: exactly that key's warning, or both keys of
    // the pair (all three of the blood moon's times), and every hardcore value is its default again
    @Test
    void config1EveryLoadCheckRefusesJustOutside() throws IOException {
        String order = "must keep 0 <= warnTick < startTick < endTick <= 23999";
        List<String> moonOrder = List.of("hardcore.bloodMoon.warnTick: " + order, "hardcore.bloodMoon.startTick: " + order,
                "hardcore.bloodMoon.endTick: " + order);
        Map<Map<String, String>, List<String>> cases = new LinkedHashMap<>();
        cases.put(Map.of("hardcore.weight.containerDepth", "-1"), List.of("hardcore.weight.containerDepth: must be 0 to 8"));
        cases.put(Map.of("hardcore.cold.shelteredBelowSkyLight", "-1"), List.of("hardcore.cold.shelteredBelowSkyLight: must be 0 to 15"));
        cases.put(Map.of("hardcore.cold.heatRadius", "-1"), List.of("hardcore.cold.heatRadius: must be 0 to 8"));
        cases.put(Map.of("hardcore.anvil.enchantCost", "40"), List.of("hardcore.anvil.enchantCost: must be 1 to 39"));
        cases.put(Map.of("hardcore.anvil.renameCost", "40"), List.of("hardcore.anvil.renameCost: must be 1 to 39"));
        cases.put(Map.of("hardcore.anvil.repairCost", "0"), List.of("hardcore.anvil.repairCost: must be 1 to 39"));
        cases.put(Map.of("hardcore.bloodMoon.endTick", "24000"), moonOrder);
        cases.put(Map.of("hardcore.bloodMoon.warnTick", "-1"), moonOrder);
        cases.put(Map.of("hardcore.bloodMoon.warnTick", "12542"), moonOrder);
        cases.put(Map.of("hardcore.bloodMoon.startTick", "23460"), moonOrder);
        cases.put(Map.of("hardcore.weight.freeLoad", "144"), List.of("hardcore.weight.freeLoad: must be below hardcore.weight.fullLoad",
                "hardcore.weight.fullLoad: must be above hardcore.weight.freeLoad"));
        cases.put(Map.of("hardcore.limp.belowHearts", "3"), List.of("hardcore.limp.belowHearts: must be less than hardcore.limp.recoverAtHearts",
                "hardcore.limp.recoverAtHearts: must be more than hardcore.limp.belowHearts"));
        cases.put(Map.of("hardcore.slow.step", "0.500000001"), List.of("hardcore.slow.step: must be at most hardcore.slow.cap",
                "hardcore.slow.cap: must be at least hardcore.slow.step"));
        for (Map.Entry<Map<String, String>, List<String>> refused : cases.entrySet()) {
            assertFellBack(loadValues(refused.getKey()), refused.getValue());
        }
    }

    // A mob may do no extra damage, and an entry with nothing in it changes nothing, but a health of 0 would kill the
    // mob as it loads, so it sends the whole map back
    @Test
    void config1OverrideHealthZeroIsRefusedButDamageZeroIsKept() throws IOException {
        Map<String, Map<String, Double>> kept = loadJson("{\"hardcore\":{\"tougherMobs\":{\"overrides\":"
                + "{\"minecraft:zombie\":{\"damage\":0},\"minecraft:husk\":{}}}}}").config().hardcore.tougherMobs.overrides;
        assertEquals(Map.of("minecraft:zombie", Map.of("damage", 0.0), "minecraft:husk", Map.of()), kept);

        ConfigLoader.Result refused = loadJson("{\"hardcore\":{\"tougherMobs\":{\"overrides\":{\"minecraft:zombie\":{\"health\":0}}}}}");
        assertEquals(new YoltConfig().hardcore.tougherMobs.overrides, refused.config().hardcore.tougherMobs.overrides);
        assertTrue(refused.warnings().contains("hardcore.tougherMobs.overrides: every value must name health, damage or speed and be 0"
                + " or more, with health above 0, using default"), refused.warnings().toString());
    }

    // A share of exactly 0 or 1 is fine, and so is a food that gives nothing at all; a hair over 1 sends the map back
    @Test
    void config1FoodSharesAtTheirEdgesAndEmptyObjectAreKept() throws IOException {
        ConfigLoader.Result r = loadJson("{\"hardcore\":{\"nutrients\":{\"foods\":"
                + "{\"minecraft:apple\":{\"protein\":0,\"sugar\":1},\"minecraft:bread\":{}}}}}");
        assertEquals(Map.of("minecraft:apple", Map.of("protein", 0.0, "sugar", 1.0), "minecraft:bread", Map.of()),
                r.config().hardcore.nutrients.foods);
        assertEquals(List.of(), r.warnings());

        ConfigLoader.Result over = loadJson("{\"hardcore\":{\"nutrients\":{\"foods\":{\"minecraft:apple\":{\"protein\":1.0000001}}}}}");
        assertEquals(new YoltConfig().hardcore.nutrients.foods, over.config().hardcore.nutrients.foods);
        assertTrue(over.warnings().contains("hardcore.nutrients.foods: every share must be 0 to 1 and name protein, vitamins or sugar,"
                + " using default"), over.warnings().toString());
    }

    // The curse may take more hearts than a player has; the notice then counts what is left as none, not below none
    @Test
    void config1CurseOutlastsLimpNoticeClampsAtZeroHearts() {
        YoltConfig config = new YoltConfig();
        config.hardcore.curse.enabled = true;
        config.hardcore.limp.enabled = true;
        config.hardcore.curse.maxLostHearts = 12;
        String notice = ConfigChecks.curseOutlastsLimp(config);
        assertNotNull(notice);
        assertTrue(notice.startsWith("with hardcore.curse.maxLostHearts 12, a fully cursed player has 0 hearts,"), notice);
        assertFalse(notice.contains("-2"), notice);
    }
}
