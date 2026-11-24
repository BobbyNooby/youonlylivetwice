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
    // the skip either. Vanilla only sends the line while someone is asleep or has just got up, and at nap time every
    // sleeper is a napper, so the line is held back then: as a nap starts and as it ends, when the napper is out of bed
    // or gone before the count. At night the line stays vanilla's.
    @Inject(method = "announceSleepStatus", at = @At("HEAD"), cancellable = true)
    private void hideSleepStatusNoSkipFollows(CallbackInfo ci) {
        if (BloodMoon.blocksSkip() || BedRest.napTime((ServerLevel) (Object) this)) {
            ci.cancel();
        }
    }
}
