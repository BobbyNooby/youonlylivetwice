package bobbynooby.dev.hardcore;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// A world played while the layer was part of YOLT keeps what it saved: the modifiers saved on mobs, the marks on them,
// the tags a data pack may override and the store's file all keep YOLT's names, hosted or not
class HardcoreIdsTest {
    private static final String NAMESPACE = "you-only-live-twice";

    @Test
    void modifiersKeepTheirIds() {
        assertEquals(id("undead_curse"), Curse.MODIFIER);
        assertEquals(id("hardcore_slow"), Slowdowns.MODIFIER);
        assertEquals(id("tougher"), MobBoosts.TOUGHER);
        assertEquals(id("blood_moon"), MobBoosts.BLOOD_MOON);
        assertEquals(id("relentless"), Relentless.RELENTLESS);
        assertEquals(id("nutrients"), Nutrients.MODIFIER);
    }

    // The files are in the jar at the path the ids name, which is where a data pack overrides them
    @Test
    void tagsKeepTheirIds() {
        assertEquals(id("heat_sources"), Cold.HEAT_SOURCES.location());
        assertEquals(Registries.BLOCK, Cold.HEAT_SOURCES.registry());
        assertEquals(id("held_heat"), Cold.HELD_HEAT.location());
        assertEquals(Registries.ITEM, Cold.HELD_HEAT.registry());
        for (String file : List.of("data/you-only-live-twice/tags/block/heat_sources.json",
                "data/you-only-live-twice/tags/item/held_heat.json")) {
            assertNotNull(HardcoreIdsTest.class.getClassLoader().getResource(file), file);
        }
    }

    @Test
    void marksKeepTheirNames() {
        assertEquals("yolt.tough", MobBoosts.TOUGH);
        assertEquals("yolt.vanilla", MobBoosts.VANILLA);
        assertEquals("yolt.blood_moon", MobBoosts.BLOOD_MOON_MARK);
        assertEquals("yolt.door_rolled", Relentless.DOOR_ROLLED);
    }

    @Test
    void theStoreKeepsItsFile() {
        Path world = Path.of("world");
        assertEquals(world.resolve("database").resolve("you-only-live-twice-hardcore.db"), HardcoreStates.storeFile(world));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }
}
