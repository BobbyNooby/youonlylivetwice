package bobbynooby.dev.mixin;

import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.hardcore.BedRest;
import bobbynooby.dev.world.Sleep;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {

    // Vanilla reads this count only for the action-bar line, which YOLT cancels whole while sleepers are hidden, so
    // this only matters to another mod that reads the count
    @Inject(method = "amountSleeping", at = @At("HEAD"), cancellable = true)
    private void getFakeSleeping(CallbackInfoReturnable<Integer> cir) {
        if (Sleep.hidesSleepers()) {
            cir.setReturnValue(0);
        }
    }

    // Player bodies are among the level's players but can't sleep, so each one would hold the night up for everyone
    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> withoutBodies(List<ServerPlayer> players) {
        return StandIn.without(players);
    }

    // A daytime nap never moves the clock: vanilla skips once enough have slept long enough, at any time of day
    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> withoutNappers(List<ServerPlayer> players) {
        return BedRest.withoutNappers(players);
    }

    @Inject(method = "sleepersNeeded", at = @At("HEAD"), cancellable = true)
    private void getFakeNightSkippingRequirement(CallbackInfoReturnable<Integer> cir) {
        if (Sleep.blocksNightSkip()) {
            cir.setReturnValue(Sleep.SLEEPERS_NEEDED_WHEN_BLOCKED);
        }
    }
}
