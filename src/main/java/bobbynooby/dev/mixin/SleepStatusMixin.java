package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
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

    @Inject(method = "amountSleeping", at = @At("HEAD"), cancellable = true)
    private void getFakeSleeping(CallbackInfoReturnable<Integer> cir) {
        if (Settings.active() && Settings.get().sleep.hideSleepers) {
            cir.setReturnValue(0);
        }
    }


    // Player bodies are among the level's players but can't sleep, so each one would hold the night up for everyone
    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> withoutBodies(List<ServerPlayer> players) {
        return PlayerBody.withoutBodies(players);
    }

    @Inject(method = "sleepersNeeded", at = @At("HEAD"), cancellable = true)
    private void getFakeNightSkippingRequirement(CallbackInfoReturnable<Integer> cir) {
        if (Settings.active() && Settings.get().sleep.noNightSkip) {
            cir.setReturnValue(727);
        }
    }
}
