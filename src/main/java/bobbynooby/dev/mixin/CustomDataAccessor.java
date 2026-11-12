package bobbynooby.dev.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// An item's custom NBT, which the coordinate offset only reads on each send, so it needs no copy of it
@Mixin(CustomData.class)
public interface CustomDataAccessor {

    @Accessor("tag")
    CompoundTag yoltTag();
}
