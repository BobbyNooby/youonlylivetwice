package bobbynooby.dev.graves;

import bobbynooby.dev.core.Stacks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Every viewer of a grave clicks into this one container, so two looters can never both take a stack. */
final class SharedGraveContainer extends SimpleContainer {
    private final MinecraftServer server;
    private final UUID graveId;
    private final List<ContainerUser> viewers = new ArrayList<>();
    // What the grave held as this container was filled. Only what has left since can be taken back in.
    private final List<ItemStack> filledWith;

    SharedGraveContainer(MinecraftServer server, UUID graveId, List<ItemStack> items) {
        // Room for every stored stack, so saving back can never drop one the menu has no slot for
        super(Math.max(GraveMenu.GRAVE_SLOTS, items.size()));
        this.server = server;
        this.graveId = graveId;
        this.filledWith = Stacks.nonEmptyCopies(items);
        int slot = 0;
        // Copies, so taking from the menu never changes the record behind its back. Put in the list
        // directly because setItem would cut an oversized stack down to the slot limit.
        for (ItemStack stack : Stacks.nonEmptyCopies(items)) {
            getItems().set(slot++, stack);
        }
    }

    // Every change goes straight into the record, so a save or restart never brings back a taken item.
    // Only the live container writes: one let go of could otherwise overwrite newer contents.
    @Override
    public void setChanged() {
        if (!GraveMenus.isShared(graveId, this)) {
            return;
        }
        GraveStore store = GraveStore.get(server);
        store.byId(graveId).ifPresent(grave -> {
            store.put(grave.withItems(Stacks.nonEmptyCopies(getItems())));
        });
        for (ContainerUser viewer : viewers) {
            if (viewer instanceof ServerPlayer player) {
                GraveMenus.changedBy(player.getUUID());
            }
        }
    }

    /**
     * Takes the grave's share of a closing menu's cursor stack back in, out of that stack: never more of the item than
     * has left the grave since this container was filled, through any viewer. So the grave never holds more than it did
     * then, and the room it needs is always there. With one looter their own items stay theirs; with several, one
     * looter's own items can stand in for the same items another took. It goes onto a stack of the same item first,
     * then into an empty slot. Returns any of the share that found no room, which only happens if something else wrote
     * the container.
     */
    ItemStack takeBack(ItemStack carried) {
        int share = Math.min(carried.getCount(), count(filledWith, carried) - count(getItems(), carried));
        if (share <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = carried.split(share);
        List<ItemStack> items = getItems();
        for (ItemStack in : items) {
            if (!rest.isEmpty() && ItemStack.isSameItemSameComponents(in, rest)) {
                int moved = Math.min(rest.getCount(), in.getMaxStackSize() - in.getCount());
                if (moved > 0) {
                    in.grow(moved);
                    rest.shrink(moved);
                }
            }
        }
        // Set in the list directly, like the constructor, so nothing is cut down to the slot limit
        for (int slot = 0; slot < items.size() && !rest.isEmpty(); slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, rest);
                rest = ItemStack.EMPTY;
            }
        }
        setChanged();
        return rest;
    }

    private static int count(List<ItemStack> stacks, ItemStack like) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItemSameComponents(stack, like)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public void startOpen(ContainerUser user) {
        viewers.add(user);
    }

    @Override
    public void stopOpen(ContainerUser user) {
        viewers.remove(user);
        if (viewers.isEmpty()) {
            GraveMenus.letGo(graveId, this);
        }
    }

    void closeMenus() {
        for (ContainerUser viewer : List.copyOf(viewers)) {
            if (viewer instanceof ServerPlayer player && player.containerMenu instanceof GraveMenu menu
                    && menu.graveId().equals(graveId)) {
                player.closeContainer();
            }
        }
    }
}
