package bobbynooby.dev.coords;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the positions vanilla puts in the NBT a client can be sent: a block position saved as three ints under a key
 * ending in "pos" or under one of a few other names, three doubles under a key ending in "pos", such as an entity's Pos,
 * and a breeze's jump target in its brain. A position under any other key goes out as it is.
 */
public final class NbtRule {
    // Vanilla's block positions under keys that don't end in "pos": an end gateway's exit, a leash knot, and where a
    // patrol, a wandering trader and an end crystal's beam are headed
    private static final Set<String> BLOCK_POS_NAMES = Set.of("exit_portal", "leash", "patrol_target", "wander_target",
            "beam_target");
    // The one brain memory saved as a block position, which keeps it under "value"
    private static final String JUMP_TARGET = "minecraft:breeze_jump_target";

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
                if (isBlockPos(key, value) || isEntityPos(key, value) || isJumpTarget(key, value) || holdsPosition(value)) {
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
                    shiftBlockPos(compound, key, offset);
                } else if (isJumpTarget(key, value)) {
                    shiftBlockPos((CompoundTag) value, "value", offset);
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

    private static void shiftBlockPos(CompoundTag compound, String key, Offset offset) {
        int[] pos = ((IntArrayTag) compound.get(key)).getAsIntArray();
        compound.putIntArray(key, new int[] {pos[0] + offset.x(), pos[1], pos[2] + offset.z()});
    }

    private static boolean isBlockPos(String key, Tag value) {
        return isThreeInts(value) && (endsInPos(key) || BLOCK_POS_NAMES.contains(key));
    }

    private static boolean isEntityPos(String key, Tag value) {
        return endsInPos(key) && value instanceof ListTag list && list.size() == 3
                && list.stream().allMatch(element -> element instanceof DoubleTag);
    }

    private static boolean isJumpTarget(String key, Tag value) {
        return key.equals(JUMP_TARGET) && value instanceof CompoundTag memory && isThreeInts(memory.get("value"));
    }

    private static boolean isThreeInts(Tag value) {
        return value instanceof IntArrayTag array && array.size() == 3;
    }

    private static boolean endsInPos(String key) {
        return key.toLowerCase(Locale.ROOT).endsWith("pos");
    }
}
