package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

// Totals rather than "is it there", so an item copied twice or half lost fails the check
final class GraveAsserts {
    private GraveAsserts() {
    }

    static int count(List<ItemStack> items, Item item) {
        return items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    static int countInGraves(List<Grave> graves, Item item) {
        int total = 0;
        for (Grave grave : graves) {
            total += count(grave.items(), item);
        }
        return total;
    }

    // Across every grave the owner has on the server
    static int countInGraves(MinecraftServer server, UUID owner, Item item) {
        return countInGraves(GraveStore.get(server).all().stream().filter(grave -> grave.owner().equals(owner)).toList(), item);
    }
}
