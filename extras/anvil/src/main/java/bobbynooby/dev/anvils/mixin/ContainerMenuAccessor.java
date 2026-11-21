package bobbynooby.dev.anvils.mixin;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets FlatAnvils make the next sync send the anvil's cost even when it didn't change on the server. The method's name
// carries the mod's, since every mod's accessors on a class share one namespace.
@Mixin(AbstractContainerMenu.class)
public interface ContainerMenuAccessor {
    @Accessor("remoteDataSlots")
    IntList yoltAnvilsRemoteDataSlots();
}
