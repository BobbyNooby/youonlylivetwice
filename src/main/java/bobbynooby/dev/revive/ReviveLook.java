package bobbynooby.dev.revive;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.graves.GraveItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Gives revive papers the resource pack's model while resourcePack.reviveTexture is on, and takes it away again when it
 * is off. Set at runtime rather than by the recipe, so a paper never shows the missing texture once the pack is gone.
 */
public final class ReviveLook {
    private static final int TICKS_PER_SECOND = 20;

    private ReviveLook() {}

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ReviveLook::tick);
    }

    /**
     * On a revive paper: with the switch on, a paper with no model of its own gets reviveModel; with it off, a paper
     * showing reviveModel goes back to plain paper. A model anyone else set is left alone, and so is every other item.
     */
    public static void apply(ItemStack stack) {
        if (!GraveItems.isReviveHead(stack)) {
            return;
        }
        Identifier model = Identifier.tryParse(Settings.get().resourcePack.reviveModel);
        if (model == null) {
            return;
        }
        // Every item has a model from its type; only one set on the stack itself shows up here
        boolean ownModel = stack.hasNonDefault(DataComponents.ITEM_MODEL);
        if (Settings.on(c -> c.resourcePack.reviveTexture)) {
            if (!ownModel) {
                stack.set(DataComponents.ITEM_MODEL, model);
            }
        } else if (ownModel && model.equals(stack.get(DataComponents.ITEM_MODEL))) {
            // Setting the type's own model drops it from the stack; remove() would leave the paper with no model at all
            stack.set(DataComponents.ITEM_MODEL, stack.getPrototype().get(DataComponents.ITEM_MODEL));
        }
    }

    // Papers made before the switch changed follow it within a second. A stack is only changed when its model has to
    // change, so the inventory isn't sent again every second.
    private static void tick(MinecraftServer server) {
        if (!Settings.active() || server.getTickCount() % TICKS_PER_SECOND != 0) {
            return;
        }
        Guard.run(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sweep(player);
            }
        }, "Could not update the look of revive papers");
    }

    // The whole inventory, armour and off hand included, then the cursor and the open menu's own slots (the crafting
    // grid, or a chest's contents while it is open), whose papers would otherwise wait until they are put away
    private static void sweep(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            apply(inventory.getItem(slot));
        }
        AbstractContainerMenu menu = player.containerMenu;
        apply(menu.getCarried());
        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                apply(slot.getItem());
            }
        }
    }
}
