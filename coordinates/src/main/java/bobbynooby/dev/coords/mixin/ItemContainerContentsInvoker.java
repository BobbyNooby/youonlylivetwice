package bobbynooby.dev.coords.mixin;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Optional;

// A container item's slots, empty ones included, which the coordinate offset copies slot for slot when an item in one
// holds a position. Nothing public builds the contents from such a list. YOLT Hardcore, which YOLT's jar also ships,
// has an accessor of its own on this class, so the methods' names are this mod's own.
@Mixin(ItemContainerContents.class)
public interface ItemContainerContentsInvoker {

    @Invoker("<init>")
    static ItemContainerContents yoltCoordinatesNew(List<Optional<ItemStackTemplate>> items) {
        throw new AssertionError();
    }

    @Accessor("items")
    List<Optional<ItemStackTemplate>> yoltCoordinatesItems();
}
