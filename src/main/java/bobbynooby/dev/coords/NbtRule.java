package bobbynooby.dev.coords;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the positions vanilla puts in the NBT a client can be sent: a block position saved as three ints under a key
 * ending in "pos" or under one of a few other names, three doubles under a key ending in "pos", such as an entity's Pos,
 * a breeze's jump target in its brain, and the x and z of each map decoration on an item. A position under any other key
 * goes out as it is. A "pos" saved with its dimension beside it is shifted as a spot in that dimension.
 */
public final class NbtRule {
    // Vanilla's block positions under keys that don't end in "pos": an end gateway's exit, a leash knot, and where a
    // patrol, a wandering trader and an end crystal's beam are headed
    private static final Set<String> BLOCK_POS_NAMES = Set.of("exit_portal", "leash", "patrol_target", "wander_target",
            "beam_target");
    // The one brain memory saved as a block position, which keeps it under "value"
    private static final String JUMP_TARGET = "minecraft:breeze_jump_target";
    // An explorer map's markers, each with its target's x and z as doubles, in the item's saved components
    private static final String MAP_DECORATIONS = "minecraft:map_decorations";
    // How a spot that names its dimension is saved: the dimension's name and a block position
    private static final String GLOBAL_POS = "pos";
    private static final String DIMENSION = "dimension";

    private NbtRule() {}

    /** The tag with every position in it shifted, as a copy. The same tag when it holds no position. */
    public static CompoundTag shift(CompoundTag tag, Frame frame) {
        if (frame.isZero() || !holdsPosition(tag)) {
            return tag;
        }
        CompoundTag copy = tag.copy();
        shiftInPlace(copy, frame);
        return copy;
    }

    private static boolean holdsPosition(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                Tag value = compound.get(key);
                if (isBlockPos(key, value) || isEntityPos(key, value) || isJumpTarget(key, value) || isMapDecorations(key, value)
                        || holdsPosition(value)) {
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
    private static void shiftInPlace(Tag tag, Frame frame) {
        Offset offset = frame.offset();
        if (tag instanceof CompoundTag compound) {
            for (String key : List.copyOf(compound.keySet())) {
                Tag value = compound.get(key);
                if (isBlockPos(key, value)) {
                    shiftBlockPos(compound, key, key.equals(GLOBAL_POS) ? offsetFor(compound, frame) : offset);
                } else if (isJumpTarget(key, value)) {
                    shiftBlockPos((CompoundTag) value, "value", offset);
                } else if (isMapDecorations(key, value)) {
                    shiftDecorations((CompoundTag) value, offset);
                } else if (isEntityPos(key, value)) {
                    ListTag pos = (ListTag) value;
                    pos.set(0, DoubleTag.valueOf(pos.getDoubleOr(0, 0.0) + offset.x()));
                    pos.set(2, DoubleTag.valueOf(pos.getDoubleOr(2, 0.0) + offset.z()));
                } else {
                    shiftInPlace(value, frame);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                shiftInPlace(element, frame);
            }
        }
    }

    // A saved spot with its dimension beside it, such as a lodestone compass's target, is shifted as that dimension's
    // spots are. A dimension that isn't a name goes by the frame's offset.
    private static Offset offsetFor(CompoundTag compound, Frame frame) {
        Identifier dimension = compound.get(DIMENSION) instanceof StringTag(String name) ? Identifier.tryParse(name) : null;
        return dimension == null ? frame.offset() : frame.offsetIn(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private static void shiftBlockPos(CompoundTag compound, String key, Offset offset) {
        int[] pos = ((IntArrayTag) compound.get(key)).getAsIntArray();
        compound.putIntArray(key, new int[] {pos[0] + offset.x(), pos[1], pos[2] + offset.z()});
    }

    private static void shiftDecorations(CompoundTag decorations, Offset offset) {
        for (String id : decorations.keySet()) {
            if (decorations.get(id) instanceof CompoundTag decoration && isDecoration(decoration)) {
                decoration.putDouble("x", decoration.getDoubleOr("x", 0.0) + offset.x());
                decoration.putDouble("z", decoration.getDoubleOr("z", 0.0) + offset.z());
            }
        }
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

    private static boolean isMapDecorations(String key, Tag value) {
        return key.equals(MAP_DECORATIONS) && value instanceof CompoundTag decorations && decorations.keySet().stream()
                .anyMatch(id -> decorations.get(id) instanceof CompoundTag decoration && isDecoration(decoration));
    }

    private static boolean isDecoration(CompoundTag decoration) {
        return decoration.get("x") instanceof DoubleTag && decoration.get("z") instanceof DoubleTag;
    }

    private static boolean isThreeInts(Tag value) {
        return value instanceof IntArrayTag array && array.size() == 3;
    }

    private static boolean endsInPos(String key) {
        return key.toLowerCase(Locale.ROOT).endsWith("pos");
    }
}
