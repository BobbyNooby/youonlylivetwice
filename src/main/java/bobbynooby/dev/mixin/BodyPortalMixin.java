package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.Bodies;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// LivingEntity's override starts with this one, and Mannequin, minecarts and boats don't override it
@Mixin(Entity.class)
public abstract class BodyPortalMixin {
    @Inject(method = "canUsePortal", at = @At("HEAD"), cancellable = true)
    private void keepBodyOutOfPortals(boolean ignorePassenger, CallbackInfoReturnable<Boolean> cir) {
        if (Bodies.blocksPortal((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
