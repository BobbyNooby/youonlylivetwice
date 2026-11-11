package bobbynooby.dev.graves;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** A grave's items in a 6-row chest menu. Anyone can take items out; nothing can be put in. */
public final class GraveMenu extends AbstractContainerMenu {
    private static final int ROWS = 6;
    static final int GRAVE_SLOTS = ROWS * 9;
    private static final double MAX_DISTANCE = 8.0;

    private final Grave grave;
    private final SharedGraveContainer container;

    public GraveMenu(int containerId, Inventory inventory, Grave grave) {
        super(MenuType.GENERIC_9x6, containerId);
        this.grave = grave;
        // Fetched here and not by the caller: opening a menu first closes the old one, which can let go of the container
        this.container = GraveMenus.sharedContainer(inventory.player.level().getServer(), grave);
        container.startOpen(inventory.player);

        // Same layout as a vanilla 6-row chest, so a vanilla client draws it as one
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new TakeOnlySlot(container, column + row * 9, 8 + column * 18, 18 + row * 18));
            }
        }
        addStandardInventorySlots(inventory, 8, 18 + ROWS * 18 + 13);
    }

    public UUID graveId() {
        return grave.id();
    }

    @Override
    public boolean stillValid(Player player) {
        MinecraftServer server = player.level().getServer();
        return server != null
                && GraveStore.get(server).byId(grave.id()).isPresent()
                && player.level().dimension().equals(grave.dimension())
                && player.distanceToSqr(Vec3.atCenterOf(grave.pos())) <= MAX_DISTANCE * MAX_DISTANCE;
    }

    // Shift-click only ever moves items out of the grave
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex >= GRAVE_SLOTS) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();
        if (!moveItemStackTo(stack, GRAVE_SLOTS, slots.size(), true)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return moved;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        super.clicked(slotIndex, buttonNum, input, player);
        // Saved once more in case some path changed a stack without telling the container
        container.setChanged();
        // Only after the click: closing the menu in the middle of one would leave the taken item on a dropped cursor
        if (container.isEmpty()) {
            GraveMenus.removeIfEmpty(player.level().getServer(), grave);
        }
    }

    @Override
    public void removed(Player player) {
        // Vanilla drops the cursor of a player who left, or was removed at their respawn, as an item that despawns. A
        // grave never expires, so the stack goes back into it instead. The grave is still there: one emptied by a
        // click goes at once and closes every menu on it.
        if (player instanceof ServerPlayer serverPlayer && cursorWouldDrop(serverPlayer) && !getCarried().isEmpty()) {
            setCarried(container.putBack(getCarried()));
        }
        super.removed(player);
        container.stopOpen(player);
    }

    // The same check as AbstractContainerMenu.dropOrPlaceInInventory
    private static boolean cursorWouldDrop(ServerPlayer player) {
        return player.hasDisconnected()
                || player.isRemoved() && player.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION;
    }

    // Refusing every place also stops number-key swaps, drags and bundles from putting anything in
    private static final class TakeOnlySlot extends Slot {
        TakeOnlySlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        // Creative middle-click would put a full copy of the stack on the cursor and leave the original here
        @Override
        public ItemStack safeClone(Player player) {
            return ItemStack.EMPTY;
        }
    }
}
