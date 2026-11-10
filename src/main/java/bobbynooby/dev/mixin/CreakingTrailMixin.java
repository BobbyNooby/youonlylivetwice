package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.CreakingTrail;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(CreakingHeartBlockEntity.class)
public abstract class CreakingTrailMixin {
    // Only the range flag changes; the trail itself still plays for players nearby
    @ModifyArg(method = "emitParticles", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"))
    private boolean normalRange(boolean overrideLimiter) {
        return CreakingTrail.overrideLimiter(overrideLimiter);
    }
}
