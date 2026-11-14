package bobbynooby.dev.mixin;

import bobbynooby.dev.world.NetherScale;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PortalForcer.class)
public abstract class NetherPortalSearchMixin {

    // The 128 is the Overworld side's search radius; the Nether side's 16 is left alone
    @ModifyExpressionValue(method = "findClosestPortalPosition", at = @At(value = "CONSTANT", args = "intValue=128"))
    private int searchNearbyAtOneToOne(int original) {
        return NetherScale.overworldPortalSearch(original);
    }

}
