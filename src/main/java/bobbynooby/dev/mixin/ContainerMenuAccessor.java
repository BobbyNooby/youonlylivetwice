package bobbynooby.dev.mixin;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets Anvils make the next sync send the anvil's cost even when it didn't change on the server
@Mixin(AbstractContainerMenu.class)
public interface ContainerMenuAccessor {
    @Accessor("remoteDataSlots")
    IntList yoltRemoteDataSlots();
}
