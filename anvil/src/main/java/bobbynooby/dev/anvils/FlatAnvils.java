package bobbynooby.dev.anvils;

import bobbynooby.dev.anvils.mixin.ContainerMenuAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Flat anvil prices: each use costs the price of its biggest job, the prior-work counter adds nothing and every result
 * comes out with it at 0, and nothing becomes too expensive. Vanilla still works out the result itself, so what can
 * combine with what, the materials used up and the name limit stay its own. A player {@link YoltAnvils#exempt} keeps
 * vanilla's anvil.
 */
public final class FlatAnvils {
    private static final float VANILLA_BREAK_CHANCE = 0.12F;
    // The cost is the anvil menu's only data slot
    private static final int COST_DATA_SLOT = 0;
    // No cost is ever negative, so the client can't already hold it
    private static final int UNKNOWN_COST = -1;

    private FlatAnvils() {}

    /** True when this player's anvil uses are priced flat. */
    public static boolean on(Player player) {
        return player instanceof ServerPlayer serverPlayer && YoltAnvils.settings().enabled()
                && !YoltAnvils.exempt(serverPlayer);
    }

    /** The prior-work counters vanilla adds to the price, which count for nothing while the price is flat. */
    public static long priorWork(Player player, long vanilla) {
        return on(player) ? 0L : vanilla;
    }

    /** The price at which vanilla refuses a use as too expensive; never reached while the price is flat. */
    public static int tooExpensiveAt(Player player, int vanilla) {
        return on(player) ? Integer.MAX_VALUE : vanilla;
    }

    /**
     * Prices the result vanilla has just made, before the menu syncs it. True when the use only renames, so the item on
     * the right isn't used up for nothing.
     */
    public static boolean finish(AnvilMenu menu, Player player, DataSlot cost) {
        if (!on(player)) {
            return false;
        }
        Slot resultSlot = menu.getSlot(AnvilMenu.RESULT_SLOT);
        ItemStack result = resultSlot.getItem();
        boolean onlyRenames = false;
        if (!result.isEmpty()) {
            ItemStack left = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
            boolean enchants = !EnchantmentHelper.getEnchantmentsForCrafting(result)
                    .equals(EnchantmentHelper.getEnchantmentsForCrafting(left));
            boolean repairs = result.getDamageValue() < left.getDamageValue();
            boolean renames = !Objects.equals(result.get(DataComponents.CUSTOM_NAME), left.get(DataComponents.CUSTOM_NAME));
            AnvilSettings settings = YoltAnvils.settings();
            int price = AnvilPrices.price(enchants, repairs, renames, settings.enchantCost(), settings.repairCost(),
                    settings.renameCost());
            // Vanilla keeps one book from enchanting a whole stack by pricing it at 40, a ceiling that is gone here
            if (price == 0 || (enchants && left.getCount() > 1)) {
                resultSlot.container.setItem(resultSlot.getContainerSlot(), ItemStack.EMPTY);
                cost.set(0);
            } else {
                clearPriorWork(result);
                cost.set(price);
                onlyRenames = !enchants && !repairs;
            }
        }
        resendResultAndCost(menu);
        return onlyRenames;
    }

    /** The chance that one use wears the anvil down a step. */
    public static float breakChance(Player player, float vanilla) {
        return on(player) ? (float) YoltAnvils.settings().breakChance() : vanilla;
    }

    /** What happens to the anvil block after a use: vanilla's own, or the same with our break chance. */
    public static BiConsumer<Level, BlockPos> wear(Player player, BiConsumer<Level, BlockPos> vanilla) {
        if (!on(player)) {
            return vanilla;
        }
        return (level, pos) -> wear(player, level, pos);
    }

    // Vanilla's wear with the chance read from the settings
    private static void wear(Player player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!player.hasInfiniteMaterials() && state.is(BlockTags.ANVIL)
                && player.getRandom().nextFloat() < breakChance(player, VANILLA_BREAK_CHANCE)) {
            BlockState worn = AnvilBlock.damage(state);
            if (worn == null) {
                level.removeBlock(pos, false);
                level.levelEvent(LevelEvent.SOUND_ANVIL_BROKEN, pos, 0);
            } else {
                level.setBlock(pos, worn, 2);
                level.levelEvent(LevelEvent.SOUND_ANVIL_USED, pos, 0);
            }
        } else {
            level.levelEvent(LevelEvent.SOUND_ANVIL_USED, pos, 0);
        }
    }

    // Every result leaves with the counter at 0, whatever either input carried, so an old item comes out clean and
    // prices like a fresh one if the feature is switched off. Set to 0, the default, not removed: a removed default
    // component reads as missing.
    private static void clearPriorWork(ItemStack result) {
        result.set(DataComponents.REPAIR_COST, 0);
    }

    // The client works out vanilla's price on every change and keystroke and shows it until told otherwise, and a sync
    // only sends what the client is thought to lack. Forgetting what it holds makes the next sync send the result slot
    // and then the cost, even when neither changed on this side.
    private static void resendResultAndCost(AnvilMenu menu) {
        menu.setRemoteSlotUnsafe(AnvilMenu.RESULT_SLOT, null);
        ((ContainerMenuAccessor) menu).yoltAnvilsRemoteDataSlots().set(COST_DATA_SLOT, UNKNOWN_COST);
    }
}
