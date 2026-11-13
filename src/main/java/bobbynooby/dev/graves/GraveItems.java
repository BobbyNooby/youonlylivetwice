package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/** Builds and recognises the player heads that mark graves and revive items. */
public final class GraveItems {
    private static final String ROOT = "yolt";
    private static final String GRAVE_HEAD = "grave_head";
    private static final String REVIVE = "revive";
    private static final String GRAVE_ID = "grave";

    private GraveItems() {}

    public static ItemStack graveHead(ResolvableProfile profile) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, profile);
        stack.set(DataComponents.CUSTOM_DATA, marker(GRAVE_HEAD));
        return stack;
    }

    /**
     * The revive head the recipe makes, with every component of the grave head in the grid and the recipe's own on top,
     * as vanilla's transmute recipe does, so it keeps the owner's skin and id. Any other result is left as it is.
     */
    public static ItemStack reviveHeadFrom(ItemStack made, ItemStackTemplate result, CraftingInput input) {
        if (!isReviveHead(made)) {
            return made;
        }
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (isGraveHead(stack)) {
                return TransmuteRecipe.createWithOriginalComponents(result, stack);
            }
        }
        return made;
    }

    public static boolean isGraveHead(ItemStack stack) {
        return hasMarker(stack, GRAVE_HEAD);
    }

    public static boolean isReviveHead(ItemStack stack) {
        return hasMarker(stack, REVIVE);
    }

    /**
     * True for an item a death destroys, as vanilla's curse of vanishing does. Never a grave or revive head, which can
     * take the curse too: losing one would lose its owner's revive.
     */
    public static boolean vanishesAtDeath(ItemStack stack) {
        return !stack.isEmpty() && cursed(stack) && !isGraveHead(stack) && !isReviveHead(stack);
    }

    /** True when the anvil's result gives a grave or revive head curse of vanishing that the input head didn't have. */
    public static boolean anvilAddsVanishing(ItemStack input, ItemStack result) {
        return Settings.active() && (isGraveHead(result) || isReviveHead(result)) && cursed(result) && !cursed(input);
    }

    private static boolean cursed(ItemStack stack) {
        return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
    }

    /** The id stored on the head's profile. Empty when the head has no profile, no stored id, or the nil id. */
    public static Optional<UUID> ownerId(ItemStack stack) {
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile == null || !storesId(profile)) {
            return Optional.empty();
        }
        UUID id = profile.partialProfile().id();
        return id.equals(Util.NIL_UUID) ? Optional.empty() : Optional.of(id);
    }

    /** What a grave's head block carries: the id of the grave it was placed for. */
    static CustomData graveMarker(UUID graveId) {
        CompoundTag inner = new CompoundTag();
        inner.store(GRAVE_ID, UUIDUtil.CODEC, graveId);
        CompoundTag root = new CompoundTag();
        root.put(ROOT, inner);
        return CustomData.of(root);
    }

    /** The grave id a head block carries, if it was placed with one. */
    static Optional<UUID> graveId(@Nullable CustomData data) {
        if (data == null) {
            return Optional.empty();
        }
        return data.copyTag().getCompound(ROOT).flatMap(inner -> inner.read(GRAVE_ID, UUIDUtil.CODEC));
    }

    private static CustomData marker(String key) {
        CompoundTag inner = new CompoundTag();
        inner.putBoolean(key, true);
        CompoundTag root = new CompoundTag();
        root.put(ROOT, inner);
        return CustomData.of(root);
    }

    // partialProfile() makes an id up from the name when none is stored, and on an online-mode server that isn't
    // the player's id. Only the saved form tells the two apart, for both a name-only lookup and a static profile.
    // A profile that can't be encoded couldn't be saved with the item either, so it counts as having no id.
    private static boolean storesId(ResolvableProfile profile) {
        return ResolvableProfile.CODEC.encodeStart(NbtOps.INSTANCE, profile).result()
                .filter(tag -> tag instanceof CompoundTag compound && compound.contains("id"))
                .isPresent();
    }

    private static boolean hasMarker(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        return data.copyTag().getCompound(ROOT)
                .map(inner -> inner.getBooleanOr(key, false))
                .orElse(false);
    }
}
