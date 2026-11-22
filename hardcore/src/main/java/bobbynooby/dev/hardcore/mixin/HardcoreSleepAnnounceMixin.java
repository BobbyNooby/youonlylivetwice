package bobbynooby.dev.hardcore.mixin;

import bobbynooby.dev.hardcore.BedRest;
import bobbynooby.dev.hardcore.BloodMoon;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class HardcoreSleepAnnounceMixin {
    // With the skip blocked, the "X/Y players sleeping" line's count could never be reached. A nap never counts towards
    // the skip either, so while anyone in the dimension naps, that line or "Sleeping through this night" would promise
    // a skip that never comes. Nobody naps at night, so the line stays vanilla's then.
    @Inject(method = "announceSleepStatus", at = @At("HEAD"), cancellable = true)
    private void hideSleepStatusNoSkipFollows(CallbackInfo ci) {
        if (BloodMoon.blocksSkip() || BedRest.anyNapping(((ServerLevel) (Object) this).players())) {
            ci.cancel();
        }
    }
}
