package bobbynooby.dev.core;

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
