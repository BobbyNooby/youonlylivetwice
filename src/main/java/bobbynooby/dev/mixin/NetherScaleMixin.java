package bobbynooby.dev.mixin;


import bobbynooby.dev.config.Settings;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DimensionType.class)
public abstract class NetherScaleMixin {

    @ModifyReturnValue(method = "coordinateScale", at = @At("TAIL"))
    private double syncOverWorldScale(double original) {
        if (!Settings.active() || !Settings.get().netherScaleOneToOne) {
            return original;
        }
        return 1;
    }


}
