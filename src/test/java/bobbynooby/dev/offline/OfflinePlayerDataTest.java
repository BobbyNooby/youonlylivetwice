package bobbynooby.dev.offline;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The save helpers that read and write vanilla's own keys, checked on a bare tag, with no server or registries
class OfflinePlayerDataTest {
    // A save with no health loads at full health, so only a health of 0 or less is a dead player's
    @Test
    void isDeadReadsTheHealthKey() {
        assertFalse(OfflinePlayerData.isDead(new CompoundTag()));
        assertFalse(OfflinePlayerData.isDead(health(0.5F)));
        assertTrue(OfflinePlayerData.isDead(health(0.0F)));
        assertTrue(OfflinePlayerData.isDead(health(-1.0F)));
    }

    // The level comes back, and all three XP keys are cleared, the progress into the level too
    @Test
    void takeExperienceReturnsTheLevelAndZeroesEveryKey() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("XpLevel", 12);
        tag.putInt("XpTotal", 345);
        tag.putFloat("XpP", 0.75F);
        assertEquals(12, OfflinePlayerData.takeExperience(tag));
        assertEquals(0, tag.getIntOr("XpLevel", -1));
        assertEquals(0, tag.getIntOr("XpTotal", -1));
        assertEquals(0.0F, tag.getFloatOr("XpP", -1.0F));
        // A save with no XP at all has none to give
        assertEquals(0, OfflinePlayerData.takeExperience(new CompoundTag()));
    }

    // As /tag does: adding a tag twice keeps one, removing one that isn't there changes nothing, and the others stay
    @Test
    void entityTagsAreAddedAndRemovedOnce() {
        CompoundTag tag = new CompoundTag();
        OfflinePlayerData.addEntityTag(tag, "kept");
        OfflinePlayerData.addEntityTag(tag, "mark");
        OfflinePlayerData.addEntityTag(tag, "mark");
        assertEquals(List.of("kept", "mark"), tags(tag));
        OfflinePlayerData.removeEntityTag(tag, "mark");
        OfflinePlayerData.removeEntityTag(tag, "mark");
        assertEquals(List.of("kept"), tags(tag));
        CompoundTag untouched = new CompoundTag();
        OfflinePlayerData.removeEntityTag(untouched, "mark");
        assertFalse(untouched.contains("Tags"));
    }

    // Vanilla's id for each mode; a save that names none is survival
    @Test
    void gameTypeDefaultsToSurvival() {
        assertEquals(GameType.SURVIVAL, OfflinePlayerData.gameType(new CompoundTag()));
        CompoundTag creative = new CompoundTag();
        creative.putInt("playerGameType", GameType.CREATIVE.getId());
        assertEquals(GameType.CREATIVE, OfflinePlayerData.gameType(creative));
        CompoundTag spectator = new CompoundTag();
        spectator.putInt("playerGameType", GameType.SPECTATOR.getId());
        assertEquals(GameType.SPECTATOR, OfflinePlayerData.gameType(spectator));
    }

    private static CompoundTag health(float health) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Health", health);
        return tag;
    }

    private static List<String> tags(CompoundTag tag) {
        return tag.read("Tags", Codec.STRING.listOf()).orElse(List.of());
    }
}
