package bobbynooby.dev.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Empty stacks can't be encoded and would crash a save, so they are dropped before anything is stored. */
public final class Stacks {
    private Stacks() {
    }

    /** The non-empty stacks themselves, in a new list. */
    public static List<ItemStack> nonEmpty(Collection<ItemStack> stacks) {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                kept.add(stack);
            }
        }
        return kept;
    }

    /** The stack's whole data as it is saved, for a log line that must hold all of it, not just the count and id. */
    public static String saved(ItemStack stack, HolderLookup.Provider registries) {
        return ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).result()
                .map(Object::toString)
                .orElseGet(stack::toString);
    }

    /** Copies of the non-empty stacks, in a new list. */
    public static List<ItemStack> nonEmptyCopies(Iterable<ItemStack> stacks) {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                kept.add(stack.copy());
            }
        }
        return kept;
    }
}
