package bobbynooby.dev.coords;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class NbtRuleTest {
    private static final Offset O = new Offset(-4_096, 2_048);

    @Test
    void positionKeysShifted() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("flower_pos", new int[] {1, 2, 3});
        tag.putIntArray("exit_portal", new int[] {4, 5, 6});
        tag.putIntArray("SleepingPos", new int[] {7, 8, 9});
        tag.putIntArray("colors", new int[] {1, 2, 3});
        CompoundTag shifted = NbtRule.shift(tag, O);
        assertArrayEquals(new int[] {1 - 4_096, 2, 3 + 2_048}, shifted.getIntArray("flower_pos").orElseThrow());
        assertArrayEquals(new int[] {4 - 4_096, 5, 6 + 2_048}, shifted.getIntArray("exit_portal").orElseThrow());
        assertArrayEquals(new int[] {7 - 4_096, 8, 9 + 2_048}, shifted.getIntArray("SleepingPos").orElseThrow());
        assertArrayEquals(new int[] {1, 2, 3}, shifted.getIntArray("colors").orElseThrow());
    }

    @Test
    void entityPosListShifted() {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", doubles(1.5, 64.0, 2.5));
        CompoundTag shifted = NbtRule.shift(tag, O);
        assertEquals(doubles(1.5 - 4_096, 64.0, 2.5 + 2_048), shifted.getListOrEmpty("Pos"));
    }

    // A vibration's source, a living entity's last explosion impact; Motion is a velocity
    @Test
    void doubleListsUnderAnyPosKeyShifted() {
        CompoundTag event = new CompoundTag();
        event.put("pos", doubles(10.5, 64.0, -3.5));
        CompoundTag tag = new CompoundTag();
        tag.put("event", event);
        tag.put("current_explosion_impact_pos", doubles(1.0, 2.0, 3.0));
        tag.put("Motion", doubles(0.5, 0.0, 0.5));
        CompoundTag shifted = NbtRule.shift(tag, O);
        assertEquals(doubles(10.5 - 4_096, 64.0, -3.5 + 2_048), shifted.getCompoundOrEmpty("event").getListOrEmpty("pos"));
        assertEquals(doubles(1.0 - 4_096, 2.0, 3.0 + 2_048), shifted.getListOrEmpty("current_explosion_impact_pos"));
        assertEquals(doubles(0.5, 0.0, 0.5), shifted.getListOrEmpty("Motion"));
    }

    // A leash held by an entity is saved as its UUID, four ints, which is no position
    @Test
    void namedBlockPositionsShifted() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("leash", new int[] {1, 2, 3});
        tag.putIntArray("patrol_target", new int[] {4, 5, 6});
        tag.putIntArray("wander_target", new int[] {7, 8, 9});
        tag.putIntArray("beam_target", new int[] {10, 11, 12});
        CompoundTag shifted = NbtRule.shift(tag, O);
        assertArrayEquals(new int[] {1 - 4_096, 2, 3 + 2_048}, shifted.getIntArray("leash").orElseThrow());
        assertArrayEquals(new int[] {4 - 4_096, 5, 6 + 2_048}, shifted.getIntArray("patrol_target").orElseThrow());
        assertArrayEquals(new int[] {7 - 4_096, 8, 9 + 2_048}, shifted.getIntArray("wander_target").orElseThrow());
        assertArrayEquals(new int[] {10 - 4_096, 11, 12 + 2_048}, shifted.getIntArray("beam_target").orElseThrow());

        CompoundTag heldByEntity = new CompoundTag();
        heldByEntity.putIntArray("leash", new int[] {1, 2, 3, 4});
        assertSame(heldByEntity, NbtRule.shift(heldByEntity, O));
    }

    @Test
    void breezeJumpTargetShifted() {
        CompoundTag target = new CompoundTag();
        target.putIntArray("value", new int[] {5, 70, -9});
        target.putLong("ttl", 100L);
        CompoundTag memories = new CompoundTag();
        memories.put("minecraft:breeze_jump_target", target);
        CompoundTag other = new CompoundTag();
        other.putIntArray("value", new int[] {1, 2, 3});
        memories.put("minecraft:angry_at", other);
        CompoundTag brain = new CompoundTag();
        brain.put("memories", memories);
        CompoundTag tag = new CompoundTag();
        tag.put("Brain", brain);
        CompoundTag shifted = NbtRule.shift(tag, O);
        CompoundTag shiftedMemories = shifted.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories");
        CompoundTag shiftedTarget = shiftedMemories.getCompoundOrEmpty("minecraft:breeze_jump_target");
        assertArrayEquals(new int[] {5 - 4_096, 70, -9 + 2_048}, shiftedTarget.getIntArray("value").orElseThrow());
        assertEquals(100L, shiftedTarget.getLongOr("ttl", 0L));
        assertArrayEquals(new int[] {1, 2, 3}, shiftedMemories.getCompoundOrEmpty("minecraft:angry_at").getIntArray("value")
                .orElseThrow());
    }

    // An explorer map on a shelf: each marker's target is two doubles, and its type and rotation stay
    @Test
    void mapDecorationsShifted() {
        CompoundTag marker = new CompoundTag();
        marker.putString("type", "minecraft:target_x");
        marker.putDouble("x", 1_234.5);
        marker.putDouble("z", -77.0);
        marker.putFloat("rotation", 180.0F);
        CompoundTag decorations = new CompoundTag();
        decorations.put("+", marker);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:map_decorations", decorations);
        CompoundTag map = new CompoundTag();
        map.putString("id", "minecraft:filled_map");
        map.put("components", components);
        ListTag items = new ListTag();
        items.add(map);
        CompoundTag tag = new CompoundTag();
        tag.put("Items", items);
        CompoundTag before = tag.copy();

        CompoundTag shifted = NbtRule.shift(tag, O);
        CompoundTag shiftedMarker = shifted.getListOrEmpty("Items").getCompoundOrEmpty(0).getCompoundOrEmpty("components")
                .getCompoundOrEmpty("minecraft:map_decorations").getCompoundOrEmpty("+");
        assertEquals(1_234.5 - 4_096, shiftedMarker.getDoubleOr("x", 0.0));
        assertEquals(-77.0 + 2_048, shiftedMarker.getDoubleOr("z", 0.0));
        assertEquals("minecraft:target_x", shiftedMarker.getStringOr("type", ""));
        assertEquals(180.0F, shiftedMarker.getFloatOr("rotation", 0.0F));
        assertEquals(before, tag);

        // The same two keys anywhere else are no position
        CompoundTag other = new CompoundTag();
        other.put("minecraft:custom", marker.copy());
        assertSame(other, NbtRule.shift(other, O));
    }

    @Test
    void nestedTagsShifted() {
        CompoundTag target = new CompoundTag();
        target.putString("dimension", "minecraft:overworld");
        target.putIntArray("pos", new int[] {10, 64, -20});
        CompoundTag tracker = new CompoundTag();
        tracker.put("target", target);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:lodestone_tracker", tracker);
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:compass");
        item.put("components", components);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag tag = new CompoundTag();
        tag.put("components", components.copy());
        tag.put("Items", items);

        CompoundTag shifted = NbtRule.shift(tag, O);
        int[] expected = {10 - 4_096, 64, -20 + 2_048};
        assertArrayEquals(expected, shifted.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:lodestone_tracker")
                .getCompoundOrEmpty("target").getIntArray("pos").orElseThrow());
        assertArrayEquals(expected, shifted.getListOrEmpty("Items").getCompoundOrEmpty(0).getCompoundOrEmpty("components")
                .getCompoundOrEmpty("minecraft:lodestone_tracker").getCompoundOrEmpty("target").getIntArray("pos").orElseThrow());
    }

    @Test
    void sameInstanceWhenNothingToShiftAndInputUntouched() {
        CompoundTag plain = new CompoundTag();
        plain.putString("id", "minecraft:chest");
        plain.putIntArray("colors", new int[] {1, 2, 3});
        assertSame(plain, NbtRule.shift(plain, O));

        CompoundTag withPos = new CompoundTag();
        withPos.putIntArray("pos", new int[] {1, 2, 3});
        withPos.put("Pos", doubles(1.5, 64.0, 2.5));
        CompoundTag before = withPos.copy();
        CompoundTag shifted = NbtRule.shift(withPos, O);
        assertNotSame(withPos, shifted);
        assertEquals(before, withPos);
    }

    @Test
    void wrongLengthArraysUntouched() {
        CompoundTag two = new CompoundTag();
        two.putIntArray("pos", new int[] {1, 2});
        assertSame(two, NbtRule.shift(two, O));
        CompoundTag four = new CompoundTag();
        four.putIntArray("pos", new int[] {1, 2, 3, 4});
        assertSame(four, NbtRule.shift(four, O));
    }

    private static ListTag doubles(double x, double y, double z) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(x));
        list.add(DoubleTag.valueOf(y));
        list.add(DoubleTag.valueOf(z));
        return list;
    }
}
