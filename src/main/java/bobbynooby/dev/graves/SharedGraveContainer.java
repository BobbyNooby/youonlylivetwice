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

// Every viewer of a grave clicks into this one container, so two looters can never both take a stack
final class SharedGraveContainer extends SimpleContainer {
    private final MinecraftServer server;
    private final UUID graveId;
    private final List<ContainerUser> viewers = new ArrayList<>();

    SharedGraveContainer(MinecraftServer server, UUID graveId, List<ItemStack> items) {
        // Room for every stored stack, so saving back can never drop one the menu has no slot for
        super(Math.max(GraveMenu.GRAVE_SLOTS, items.size()));
        this.server = server;
        this.graveId = graveId;
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
        if (GraveMenus.OPEN.get(graveId) != this) {
            return;
        }
        GraveStore store = GraveStore.get(server);
        store.byId(graveId).ifPresent(grave -> {
            store.put(grave.withItems(Stacks.nonEmptyCopies(getItems())));
        });
    }

    @Override
    public void startOpen(ContainerUser user) {
        viewers.add(user);
    }

    @Override
    public void stopOpen(ContainerUser user) {
        viewers.remove(user);
        if (viewers.isEmpty()) {
            GraveMenus.OPEN.remove(graveId, this);
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
