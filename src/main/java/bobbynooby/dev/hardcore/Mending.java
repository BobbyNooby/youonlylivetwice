package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Mending nerf: XP still mends gear first, at a configurable rate per point instead of vanilla's 2. The part of a
 * point too small to repair anything yet is kept for the player's next pickup, and XP that repairs nothing reaches the
 * bar, so small orbs are never eaten.
 */
public final class Mending {
    private static final double VANILLA_RATE = 2.0;
    // Only in memory: losing a fraction of a point on a restart costs nobody anything
    private static final Map<UUID, Double> CARRIED = new HashMap<>();

    private Mending() {}

    static void init() {
        ServerPlayerEvents.LEAVE.register(player -> CARRIED.remove(player.getUUID()));
        Status.rule(Mending::statusPart);
    }

    /** True when this pickup's repair is ours rather than vanilla's. */
    public static boolean handles(ServerPlayer player) {
        return Settings.on(c -> c.hardcore.mending.enabled) && !StandIn.is(player) && !Hardcore.exempt(player);
    }

    /**
     * Vanilla's pick of a damaged item with the repair-with-XP effect, repaired at our rate, then the next item with
     * what is left. Returns the XP left for the bar.
     */
    public static int repairPlayerItems(ServerPlayer player, int xp) {
        double rate = rate();
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
        MendingMaths.Result result = MendingMaths.repair(xp, rate, stack.getDamageValue(), CARRIED.getOrDefault(uuid, 0.0));
        CARRIED.put(uuid, result.carried());
        stack.setDamageValue(stack.getDamageValue() - result.repaired());
        int left = xp - result.xpUsed();
        // XP is only left over once this item is whole, so each step has one damaged item fewer to pick from
        return left > 0 ? repairPlayerItems(player, left) : 0;
    }

    private static double rate() {
        return Settings.get().hardcore.mending.durabilityPerXp;
    }

    private static Optional<String> statusPart(ServerPlayer player) {
        if (!Settings.on(c -> c.hardcore.mending.enabled)) {
            return Optional.empty();
        }
        return Optional.of("Mending " + number(rate()) + "/XP (vanilla " + number(VANILLA_RATE) + ")");
    }

    // 0.4 as it is, and 2 rather than 2.0
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
