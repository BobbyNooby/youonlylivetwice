package bobbynooby.dev.mending;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Mending nerf: XP still mends gear first, at a configurable rate per point instead of vanilla's 2. The part of a
 * point too small to repair anything yet is kept for the player's next pickup, and XP that repairs nothing reaches the
 * bar, so small orbs are never eaten. A player {@link YoltMending#exempt} keeps vanilla's Mending.
 */
public final class MendingRepair {
    // Only in memory: losing a fraction of a point on a restart costs nobody anything
    private static final Map<UUID, Double> CARRIED = new HashMap<>();

    private MendingRepair() {}

    // The carried part lasts only until the player leaves. Hooked from the mod's own onInitialize, which runs whether
    // the mod is hosted or not.
    static void init() {
        ServerPlayerEvents.LEAVE.register(player -> CARRIED.remove(player.getUUID()));
    }

    /** True when this pickup's repair is ours rather than vanilla's: the mod is on and the player isn't left out. */
    public static boolean handles(ServerPlayer player) {
        return YoltMending.settings().enabled() && !YoltMending.exempt(player);
    }

    /**
     * Vanilla's pick of a damaged item with the repair-with-XP effect, repaired at our rate, then the next item with
     * what is left. Returns the XP left for the bar.
     */
    public static int repairPlayerItems(ServerPlayer player, int xp) {
        double rate = YoltMending.settings().durabilityPerXp();
        if (!(rate > 0)) {
            return xp;
        }
        Optional<EnchantedItemInUse> selected =
                EnchantmentHelper.getRandomItemWith(EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
        if (selected.isEmpty()) {
            return xp;
        }
        ItemStack stack = selected.get().itemStack();
        UUID uuid = player.getUUID();
        MendingMaths.Result result = MendingMaths.repair(xp, rate, stack.getDamageValue(), carried(uuid));
        CARRIED.put(uuid, result.carried());
        stack.setDamageValue(stack.getDamageValue() - result.repaired());
        int left = xp - result.xpUsed();
        // XP is only left over once this item is whole, so each step has one damaged item fewer to pick from
        return left > 0 ? repairPlayerItems(player, left) : 0;
    }

    /** The part of a point kept for this player's next pickup: 0 when nothing is kept. */
    static double carried(UUID player) {
        return CARRIED.getOrDefault(player, 0.0);
    }
}
