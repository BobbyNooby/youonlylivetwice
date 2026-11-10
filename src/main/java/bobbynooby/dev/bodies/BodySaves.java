package bobbynooby.dev.bodies;

import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Writes what a body went through into its owner's save. Only for a body that is being removed. */
public final class BodySaves {
    private BodySaves() {}

    /**
     * Carries what happened to a body's gear over to the gear in the save, by GearSync's rules. Only for a body
     * that is being removed, or its copies would exist twice. An entry in the save that doesn't read back is the
     * owner's own item, from a mod or datapack removed since, so nothing is ever written over it.
     * <p>
     * A body item headed for such a slot can't be a copy of it, since the copy wouldn't read back either: a dispenser
     * put it on the body. It goes into a free inventory slot of the save instead. Returns the ones that found no free
     * slot; the caller has to put them somewhere, so no item is ever written nowhere.
     */
    public static List<ItemStack> applyBodyGear(CompoundTag tag, Map<EquipmentSlot, ItemStack> bodyGear,
                                                HolderLookup.Provider registries, String owner) {
        OfflinePlayerData.Gear saved = OfflinePlayerData.readGear(tag, registries);
        Map<EquipmentSlot, ItemStack> changed = new EnumMap<>(EquipmentSlot.class);
        GearSync.applyBodyGear(bodyGear, slot -> saved.items().getOrDefault(slot, ItemStack.EMPTY), changed::put);
        Map<EquipmentSlot, ItemStack> kept = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : saved.unreadable().keySet()) {
            ItemStack bodyItem = changed.remove(slot);
            if (bodyItem != null) {
                kept.put(slot, bodyItem);
            }
        }
        // One that reads back in part counts as the item, as in vanilla's own load, and the raw entry goes in the log
        saved.partlyRead().forEach((slot, entry) -> {
            if (changed.containsKey(slot)) {
                Yolt.LOGGER.warn("Writing {}'s partly unreadable item in slot {} back as the body left it, without the "
                        + "parts that can't be read: {}", owner, slot.getName(), entry);
            }
        });
        OfflinePlayerData.writeGear(tag, changed, registries);
        // After the write, so a main hand it just filled counts as taken
        List<ItemStack> unplaced = new ArrayList<>();
        kept.forEach((slot, bodyItem) -> {
            Tag entry = saved.unreadable().get(slot);
            String item = Stacks.saved(bodyItem, registries);
            OptionalInt free = OfflinePlayerData.addToInventory(tag, bodyItem, registries);
            if (free.isPresent()) {
                Yolt.LOGGER.warn("Keeping {}'s unreadable item in slot {} in their save file: {}. The body's item for that "
                        + "slot goes into inventory slot {} instead: {}", owner, slot.getName(), entry, free.getAsInt(), item);
            } else {
                Yolt.LOGGER.warn("Keeping {}'s unreadable item in slot {} in their save file: {}. The body's item for that "
                        + "slot has no free inventory slot there, so it goes elsewhere: {}", owner, slot.getName(), entry, item);
                unplaced.add(bodyItem);
            }
        });
        return unplaced;
    }

    /**
     * Writes a body's state into its owner's save, as a login would have taken it over: where it stands, its
     * health, fall, fire and air, its effects when copyEffects is on, and its gear. Only for a body being removed.
     * Returns the body's items that found no place in the save, as applyBodyGear does.
     */
    public static List<ItemStack> applySnapshot(CompoundTag tag, BodySnapshot snapshot, boolean copyEffects,
                                                HolderLookup.Provider registries, String owner) {
        // The body only keeps which way it faces, so the saved pitch stays
        OfflinePlayerData.writePosition(tag, snapshot.dimension(), snapshot.pos(), snapshot.yRot());
        tag.putFloat(OfflinePlayerData.HEALTH, snapshot.health());
        tag.putFloat(OfflinePlayerData.ABSORPTION, snapshot.absorption());
        tag.putDouble(OfflinePlayerData.FALL_DISTANCE, snapshot.fallDistance());
        tag.putShort(OfflinePlayerData.FIRE, (short) snapshot.fireTicks());
        tag.putShort(OfflinePlayerData.AIR, (short) snapshot.air());
        if (copyEffects) {
            List<MobEffectInstance> before = OfflinePlayerData.readEffects(tag, registries);
            List<AttributeInstance.Packed> attributes = OfflinePlayerData.readAttributes(tag, registries);
            OfflinePlayerData.writeAttributes(tag, swapEffectModifiers(attributes, before, snapshot.effects()), registries);
            OfflinePlayerData.writeEffects(tag, snapshot.effects(), registries);
        }
        return applyBodyGear(tag, snapshot.gear(), registries, owner);
    }

    // An effect's attribute modifiers are saved with the attributes and loading doesn't add them again, so the old
    // effects' come off and the new ones' go on, as removing and adding the effects on a live player would
    private static List<AttributeInstance.Packed> swapEffectModifiers(List<AttributeInstance.Packed> attributes,
                                                                      List<MobEffectInstance> before,
                                                                      List<MobEffectInstance> after) {
        Map<Holder<Attribute>, Double> bases = new LinkedHashMap<>();
        Map<Holder<Attribute>, List<AttributeModifier>> modifiers = new HashMap<>();
        for (AttributeInstance.Packed packed : attributes) {
            bases.put(packed.attribute(), packed.baseValue());
            modifiers.put(packed.attribute(), new ArrayList<>(packed.modifiers()));
        }
        for (MobEffectInstance effect : before) {
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
                List<AttributeModifier> list = modifiers.get(attribute);
                if (list != null) {
                    list.removeIf(existing -> existing.id().equals(modifier.id()));
                }
            });
        }
        // The save only lists the attributes the player has used. One it lacks starts from a player's default, which is
        // what loading would have given it; one a player doesn't have at all would be dropped by the load anyway.
        AttributeSupplier defaults = DefaultAttributes.getSupplier(EntityTypes.PLAYER);
        for (MobEffectInstance effect : after) {
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
                if (!bases.containsKey(attribute)) {
                    if (!defaults.hasAttribute(attribute)) {
                        return;
                    }
                    bases.put(attribute, defaults.getBaseValue(attribute));
                    modifiers.put(attribute, new ArrayList<>());
                }
                List<AttributeModifier> list = modifiers.get(attribute);
                list.removeIf(existing -> existing.id().equals(modifier.id()));
                list.add(modifier);
            });
        }
        List<AttributeInstance.Packed> swapped = new ArrayList<>();
        bases.forEach((attribute, base) -> swapped.add(new AttributeInstance.Packed(attribute, base, modifiers.get(attribute))));
        return swapped;
    }
}
