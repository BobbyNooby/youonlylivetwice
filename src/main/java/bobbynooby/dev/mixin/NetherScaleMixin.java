package bobbynooby.dev.mixin;

import bobbynooby.dev.world.NetherScale;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DimensionType.class)
public abstract class NetherScaleMixin {

    @ModifyReturnValue(method = "coordinateScale", at = @At("RETURN"))
    private double syncOverWorldScale(double original) {
        return NetherScale.scale(original);
    }

}
