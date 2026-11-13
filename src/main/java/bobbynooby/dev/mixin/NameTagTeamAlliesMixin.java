package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.NameTagTeam;
import net.minecraft.world.scores.Team;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Team.class)
public abstract class NameTagTeamAlliesMixin {
    // Every player shares our team, so being in it must not make them allies: pets, sweeps and mob targeting then work
    // as for players in no team. Any other team keeps vanilla's answer.
    @Inject(method = "isAlliedTo", at = @At("HEAD"), cancellable = true)
    private void noAlliesInOurTeam(@Nullable Team other, CallbackInfoReturnable<Boolean> cir) {
        if (NameTagTeam.alliesNobody((Team) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
