package bobbynooby.dev.hardcore.mixin;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Optional;

// A container item's slots as they are kept, which the carried weight reads without making a stack of each. Another
// mod's jar may have an accessor of its own on this class, as YOLT Coordinates does, so the method's name is this mod's
// own.
@Mixin(ItemContainerContents.class)
public interface ItemContainerContentsInvoker {

    @Accessor("items")
    List<Optional<ItemStackTemplate>> yoltHardcoreItems();
}
