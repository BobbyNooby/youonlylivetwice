package bobbynooby.dev.mending.mixin;

import bobbynooby.dev.mending.MendingRepair;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExperienceOrb.class)
public abstract class MendingRateMixin {
    // Vanilla rounds each repair to whole points and drops XP that rounds to none, so the whole step is ours. Its own
    // call back into itself never runs, since the first one is already taken.
    @Inject(method = "repairPlayerItems", at = @At("HEAD"), cancellable = true)
    private void mendAtOurRate(ServerPlayer player, int amount, CallbackInfoReturnable<Integer> cir) {
        if (MendingRepair.handles(player)) {
            cir.setReturnValue(MendingRepair.repairPlayerItems(player, amount));
        }
    }
}
