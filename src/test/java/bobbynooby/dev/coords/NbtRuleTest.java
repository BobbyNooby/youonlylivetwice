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
