package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Cold;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class HardcoreFreezeMixin {
    // Right after vanilla takes its two frozen ticks off and before it reads them, so its frost and its freeze hit see
    // the chill too
    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;removeFrost()V"))
    private void showChill(CallbackInfo ci) {
        Cold.showChill((LivingEntity) (Object) this);
    }

    // Vanilla's frost slowdown would stack past the slowdown cap; the chill slows inside it instead
    @Inject(method = "tryAddFrost", at = @At("HEAD"), cancellable = true)
    private void skipFrostForChill(CallbackInfo ci) {
        if (Cold.chills((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }

    // The first hurt in the living tick is the freeze hit; the drowning one for water-sensitive mobs comes after it
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            ordinal = 0))
    private boolean floorFreezeHit(LivingEntity entity, ServerLevel level, DamageSource source, float amount,
                                   Operation<Boolean> original) {
        float hit = Cold.freezeHit(entity, amount);
        return hit > 0.0F && original.call(entity, level, source, hit);
    }
}
