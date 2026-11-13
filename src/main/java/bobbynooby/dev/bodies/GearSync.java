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

    private GearSync() {}

    /**
     * Applies the body's gear to the real slots, one slot at a time:
     * <ul>
     *   <li>body empty, real not: the real slot is cleared (a popped totem, broken armour);</li>
     *   <li>body has an item, real empty: the real slot gets a copy (armour a dispenser put on the body);</li>
     *   <li>the copy: the body's damage and count go onto the real stack, never above the real count;</li>
     *   <li>anything else: the copy was used up and a dispenser filled the slot, so the real slot gets the body's item.</li>
     * </ul>
     * Only for a body that is being removed, or the copied gear would exist twice.
     */
    public static void applyBodyGear(Map<EquipmentSlot, ItemStack> bodyGear, Function<EquipmentSlot, ItemStack> getReal,
                                     BiConsumer<EquipmentSlot, ItemStack> setReal) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack body = bodyGear.getOrDefault(slot, ItemStack.EMPTY);
            ItemStack real = getReal.apply(slot);
            if (real.isEmpty()) {
                if (!body.isEmpty()) {
                    setReal.accept(slot, body.copy());
                }
                continue;
            }
            if (body.isEmpty()) {
                setReal.accept(slot, ItemStack.EMPTY);
            } else if (isCopyOf(body, real)) {
                ItemStack updated = real.copy();
                // Never above the real count: a larger body stack would create items
                updated.setCount(Math.min(body.getCount(), real.getCount()));
                if (body.has(DataComponents.DAMAGE)) {
                    updated.set(DataComponents.DAMAGE, body.get(DataComponents.DAMAGE));
                } else {
                    updated.remove(DataComponents.DAMAGE);
                }
                setReal.accept(slot, updated);
            } else {
                setReal.accept(slot, body.copy());
            }
        }
    }

    // The same item apart from what the body changes on its copy: wear, count, and the totem effect it may strip
    private static boolean isCopyOf(ItemStack body, ItemStack real) {
        return ItemStack.isSameItemSameComponents(comparable(body), comparable(real));
    }

    private static ItemStack comparable(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(DataComponents.DAMAGE);
        copy.remove(DataComponents.DEATH_PROTECTION);
        return copy;
    }
}
