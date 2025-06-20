package bobbynooby.dev.mixin;


import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DimensionType.class)
public class DimensionTypeMixin {

    @ModifyReturnValue(method = "coordinateScale", at = @At("TAIL"))
    private double syncOverWorldScale(double original) {
        return 1;
    }


}
