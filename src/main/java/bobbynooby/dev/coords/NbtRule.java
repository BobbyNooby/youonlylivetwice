package bobbynooby.dev.coords;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Locale;

/**
 * Finds the positions vanilla puts in the NBT a client is sent: a block position saved as three ints under a key ending
 * in "pos" or named exit_portal, and an entity's Pos as three doubles. A position under any other key goes out as it is.
 */
public final class NbtRule {
    private NbtRule() {}

    /** The tag with every position in it shifted, as a copy. The same tag when it holds no position. */
    public static CompoundTag shift(CompoundTag tag, Offset offset) {
        if (offset.isZero() || !holdsPosition(tag)) {
            return tag;
        }
        CompoundTag copy = tag.copy();
        shiftInPlace(copy, offset);
        return copy;
    }

    private static boolean holdsPosition(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                Tag value = compound.get(key);
                if (isBlockPos(key, value) || isEntityPos(key, value) || holdsPosition(value)) {
                    return true;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                if (holdsPosition(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Only ever called on a copy of our own
    private static void shiftInPlace(Tag tag, Offset offset) {
        if (tag instanceof CompoundTag compound) {
            for (String key : List.copyOf(compound.keySet())) {
                Tag value = compound.get(key);
                if (isBlockPos(key, value)) {
                    int[] pos = ((IntArrayTag) value).getAsIntArray();
                    compound.putIntArray(key, new int[] {pos[0] + offset.x(), pos[1], pos[2] + offset.z()});
                } else if (isEntityPos(key, value)) {
                    ListTag pos = (ListTag) value;
                    pos.set(0, DoubleTag.valueOf(pos.getDoubleOr(0, 0.0) + offset.x()));
                    pos.set(2, DoubleTag.valueOf(pos.getDoubleOr(2, 0.0) + offset.z()));
                } else {
                    shiftInPlace(value, offset);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                shiftInPlace(element, offset);
            }
        }
    }

    private static boolean isBlockPos(String key, Tag value) {
        return value instanceof IntArrayTag array && array.size() == 3
                && (key.toLowerCase(Locale.ROOT).endsWith("pos") || key.equals("exit_portal"));
    }

    private static boolean isEntityPos(String key, Tag value) {
        return key.equals("Pos") && value instanceof ListTag list && list.size() == 3
                && list.stream().allMatch(element -> element instanceof DoubleTag);
    }
}
