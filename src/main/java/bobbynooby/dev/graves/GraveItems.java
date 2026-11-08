package bobbynooby.dev.graves;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Optional;
import java.util.UUID;

/** Builds and recognises the player heads that mark graves and revive items. */
public final class GraveItems {
    private static final String ROOT = "yolt";
    private static final String GRAVE_HEAD = "grave_head";
    private static final String REVIVE = "revive";

    private GraveItems() {
    }

    public static ItemStack graveHead(ResolvableProfile profile) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, profile);
        stack.set(DataComponents.CUSTOM_DATA, marker(GRAVE_HEAD));
        return stack;
    }

    // Keeps the signed textures property so the skin shows without a lookup
    public static ResolvableProfile profileOf(ServerPlayer player) {
        return ResolvableProfile.createResolved(player.getGameProfile());
    }

    public static ResolvableProfile profileOf(NameAndId who) {
        return ResolvableProfile.createResolved(new GameProfile(who.id(), who.name()));
    }

    public static boolean isGraveHead(ItemStack stack) {
        return hasMarker(stack, GRAVE_HEAD);
    }

    public static boolean isReviveHead(ItemStack stack) {
        return hasMarker(stack, REVIVE);
    }

    public static Optional<UUID> ownerId(ItemStack stack) {
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        return profile == null ? Optional.empty() : Optional.of(profile.partialProfile().id());
    }

    private static CustomData marker(String key) {
        CompoundTag inner = new CompoundTag();
        inner.putBoolean(key, true);
        CompoundTag root = new CompoundTag();
        root.put(ROOT, inner);
        return CustomData.of(root);
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
