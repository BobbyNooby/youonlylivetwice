package bobbynooby.dev.coords.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// An item's custom NBT, which the coordinate offset only reads on each send, so it needs no copy of it. YOLT's jar has
// an accessor of its own on this class, so the method's name is this mod's own.
@Mixin(CustomData.class)
public interface CustomDataAccessor {

    @Accessor("tag")
    CompoundTag yoltCoordinatesTag();
}
