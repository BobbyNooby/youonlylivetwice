package bobbynooby.dev.mixin;

import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SleepManager.class)
public class SleepManagerMixin {

    @Inject(method = "getSleeping", at = @At("HEAD"), cancellable = true)
    private void getFakeSleeping(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(0);
    }


    @Inject(method = "getNightSkippingRequirement", at = @At("HEAD"), cancellable = true)
    private void getFakeNightSkippingRequirement(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(727);
    }
}
