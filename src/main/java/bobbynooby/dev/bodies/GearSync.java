package bobbynooby.dev.bodies;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Carries what happened to a body's gear (a popped totem, wear and tear) over to the owner's real items. */
public final class GearSync {
    public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);

    private GearSync() {
    }

    public static void applyBodyGear(Map<EquipmentSlot, ItemStack> bodyGear, Function<EquipmentSlot, ItemStack> getReal,
                                     BiConsumer<EquipmentSlot, ItemStack> setReal) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack body = bodyGear.getOrDefault(slot, ItemStack.EMPTY);
            ItemStack real = getReal.apply(slot);
            if (real.isEmpty()) {
                continue;
            }
            if (body.isEmpty()) {
                setReal.accept(slot, ItemStack.EMPTY);
            } else if (ItemStack.isSameItem(body, real)) {
                // A different item means the player swapped it elsewhere; only the same item's wear is carried over
                ItemStack updated = real.copy();
                updated.setCount(body.getCount());
                if (body.has(DataComponents.DAMAGE)) {
                    updated.set(DataComponents.DAMAGE, body.get(DataComponents.DAMAGE));
                } else {
                    updated.remove(DataComponents.DAMAGE);
                }
                setReal.accept(slot, updated);
            }
        }
    }
}
