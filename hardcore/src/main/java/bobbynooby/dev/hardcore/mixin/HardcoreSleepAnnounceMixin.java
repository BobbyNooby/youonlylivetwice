package bobbynooby.dev.hardcore.mixin;

import bobbynooby.dev.hardcore.BloodMoon;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class HardcoreSleepAnnounceMixin {
    // With the skip blocked, the "X/Y players sleeping" line's count could never be reached
    @Inject(method = "announceSleepStatus", at = @At("HEAD"), cancellable = true)
    private void hideSleepStatusForBloodMoon(CallbackInfo ci) {
        if (BloodMoon.blocksSkip()) {
            ci.cancel();
        }
    }
}
