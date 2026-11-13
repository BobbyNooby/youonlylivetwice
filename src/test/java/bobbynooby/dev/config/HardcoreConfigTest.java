package bobbynooby.dev.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            "c:storage_blocks/iron", "c:storage_blocks/gold", "c:storage_blocks/copper", "c:storage_blocks/netherite",
            "c:storage_blocks/raw_iron", "c:storage_blocks/raw_gold", "c:storage_blocks/raw_copper", "c:storage_blocks",
            "c:feathers", "c:strings");

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
        assertEquals(56.0, weight.fullLoad);
        assertEquals(0.5, weight.maxSlow);
        assertEquals(1.0, weight.defaultWeight);
        assertEquals(0.25, weight.unstackableWeight);
        assertEquals(0.5, weight.toolWeight);
        assertEquals(GROUP_ORDER, new ArrayList<>(weight.groups.keySet()));
        assertEquals(3.0, weight.groups.get("c:ores"));
        assertEquals(3.0, weight.groups.get("minecraft:logs"));
        assertEquals(27.0, weight.groups.get("c:storage_blocks/iron"));
        assertEquals(27.0, weight.groups.get("c:storage_blocks/raw_copper"));
        assertEquals(9.0, weight.groups.get("c:storage_blocks"));
        assertEquals(0.25, weight.groups.get("c:strings"));
        assertEquals(Map.of("minecraft:paper", 0.25, "minecraft:anvil", 9.0), weight.items);
        assertEquals(List.of("minecraft:paper", "minecraft:anvil"), new ArrayList<>(weight.items.keySet()));
        assertEquals(Map.of("minecraft:elytra", 1.5, "minecraft:mace", 2.0), weight.itemsEach);
        assertEquals(List.of("leather", "copper", "chainmail", "gold", "iron", "turtle_scute", "diamond", "netherite"),
                new ArrayList<>(weight.armourFactors.keySet()));
        assertEquals(0.15, weight.armourFactors.get("leather"));
        assertEquals(0.45, weight.armourFactors.get("netherite"));
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
        assertEquals(Map.of("minecraft:paper", 0.25, "minecraft:anvil", 9.0), r.config().hardcore.weight.items);
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
        assertEquals(Map.of("minecraft:paper", 0.25, "minecraft:anvil", 9.0), notAMap.config().hardcore.weight.items);
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
        assertEquals(56.0, hardcore.weight.fullLoad);
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
}
