package bobbynooby.dev.hardcore.mixin;

import bobbynooby.dev.hardcore.BedRest;
import bobbynooby.dev.hardcore.BloodMoon;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SleepStatus.class)
public abstract class HardcoreSleepMixin {
    // Far more sleepers than a real server has in bed at once, so in practice the night never skips. Under YOLT its own
    // sleep mixin gives the same number when its switch blocks the skip, so whichever of the two runs first, the count
    // is the same.
    @Unique
    private static final int SLEEPERS_NEEDED_WHEN_BLOCKED = 727;

    // A daytime nap never moves the clock: vanilla skips once enough have slept long enough, at any time of day
    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> withoutNappers(List<ServerPlayer> players) {
        return BedRest.withoutNappers(players);
    }

    @Inject(method = "sleepersNeeded", at = @At("HEAD"), cancellable = true)
    private void noSkipThroughBloodMoon(CallbackInfoReturnable<Integer> cir) {
        if (BloodMoon.blocksSkip()) {
            cir.setReturnValue(SLEEPERS_NEEDED_WHEN_BLOCKED);
        }
    }
}
