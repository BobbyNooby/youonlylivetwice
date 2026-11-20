package bobbynooby.dev.revive;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.graves.GraveItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;

/**
 * Gives revive papers the resource pack's model while resourcePack.reviveTexture is on, and takes it away again when it
 * is off. Set as a paper is crafted and kept up once a second, rather than stamped into the recipe's result, so papers
 * made with the pack on lose the model again, and don't show the missing texture, once it is off.
 */
public final class ReviveLook {
    private static final int TICKS_PER_SECOND = 20;
    // The paper's own marker ({yolt: {revive: true}}) also keeps the model YOLT put on it, so after reviveModel changes
    // the old model can still be told from one an admin set by hand
    private static final String ROOT = "yolt";
    private static final String MODEL = "model";

    private ReviveLook() {}

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ReviveLook::tick);
    }

    /**
     * On a revive paper: with the switch on, a paper with no model of its own, or with a model YOLT put on it that isn't
     * reviveModel any more, gets reviveModel; with it off, a paper showing a model YOLT put on it goes back to plain
     * paper. A model anyone else set is left alone, and so is every other item. Returns true when the stack was changed.
     */
    public static boolean apply(ItemStack stack) {
        if (!GraveItems.isReviveHead(stack)) {
            return false;
        }
        Identifier model = Identifier.tryParse(Settings.get().resourcePack.reviveModel);
        if (model == null) {
            return false;
        }
        // Every item has a model from its type; only one set on the stack itself shows up here
        Identifier shown = stack.hasNonDefault(DataComponents.ITEM_MODEL) ? stack.get(DataComponents.ITEM_MODEL) : null;
        Identifier noted = noted(stack);
        // A paper from before YOLT noted its model shows reviveModel as it was then, so that one counts as YOLT's too
        boolean ours = shown != null && (shown.equals(noted) || shown.equals(model));
        if (Settings.on(c -> c.resourcePack.reviveTexture)) {
            if (shown == null || ours && !(shown.equals(model) && model.equals(noted))) {
                stack.set(DataComponents.ITEM_MODEL, model);
                note(stack, model);
                return true;
            }
        } else if (ours) {
            // Setting the type's own model drops it from the stack; remove() would leave the paper with no model at all
            stack.set(DataComponents.ITEM_MODEL, stack.getPrototype().get(DataComponents.ITEM_MODEL));
            // Without the note the paper is as the recipe made it again, so it stacks with papers that never had one
            note(stack, null);
            return true;
        }
        return false;
    }

    // Papers made before the switch changed follow it within a second. A stack is only changed when its model has to
    // change, so the inventory isn't sent again every second. It runs with the switch off too, since that is what takes
    // the model off papers after the switch goes off, even ones that come back after a restart.
    private static void tick(MinecraftServer server) {
        if (!Settings.active() || server.getTickCount() % TICKS_PER_SECOND != 0) {
            return;
        }
        // One player's failure leaves the others' papers to be swept
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Guard.run(() -> sweep(player), "Could not update the look of {}'s revive papers", player.nameAndId().name());
        }
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
            // A chest's contents are saved only once the chest knows they changed
            if (slot.container != inventory && apply(slot.getItem())) {
                slot.setChanged();
            }
        }
    }

    // The model YOLT last put on the paper, if it noted one
    private static @Nullable Identifier noted(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        return data.copyTag().getCompound(ROOT)
                .flatMap(inner -> inner.getString(MODEL))
                .map(Identifier::tryParse)
                .orElse(null);
    }

    private static void note(ItemStack stack, @Nullable Identifier model) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = tag.getCompoundOrEmpty(ROOT);
            if (model == null) {
                inner.remove(MODEL);
            } else {
                inner.putString(MODEL, model.toString());
            }
            tag.put(ROOT, inner);
        });
    }
}
