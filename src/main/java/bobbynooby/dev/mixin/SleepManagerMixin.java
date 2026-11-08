package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SleepStatus.class)
public class SleepManagerMixin {

    @Inject(method = "amountSleeping", at = @At("HEAD"), cancellable = true)
    private void getFakeSleeping(CallbackInfoReturnable<Integer> cir) {
        if (Settings.active() && Settings.get().sleep.hideSleepers) {
            cir.setReturnValue(0);
        }
    }


    @Inject(method = "sleepersNeeded", at = @At("HEAD"), cancellable = true)
    private void getFakeNightSkippingRequirement(CallbackInfoReturnable<Integer> cir) {
        if (Settings.active() && Settings.get().sleep.noNightSkip) {
            cir.setReturnValue(727);
        }
    }
}
