package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Legs;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class HardcoreFallMixin {
    // The hit as it came in, before the half second after an earlier hit took that hit's damage off it
    @Unique
    private float yoltWholeHit;

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void noteWholeHit(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        yoltWholeHit = amount;
    }

    // What protection is about to work on: the whole hit, or what the half second left of it. Noted on the way in,
    // since the method changes its own argument as it goes.
    @Unique
    private float yoltDealt;

    @Inject(method = "getDamageAfterMagicAbsorb", at = @At("HEAD"))
    private void noteDealt(DamageSource source, float damage, CallbackInfoReturnable<Float> cir) {
        yoltDealt = damage;
    }

    // Both actuallyHurt methods, the living entity's and the player's own, take this value just before absorption
    // hearts soak it up, so this sees every hit after armour and protection enchantments. The damage is left as it is.
    // Right after another hit vanilla only deals the difference, so a fall would look smaller than it was and a poison
    // tick or a friend's punch just before landing would save the legs: the fall is judged whole, at the same share
    // protection let through.
    @ModifyReturnValue(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"))
    private float seeFallDamage(float damage, @Local(argsOnly = true) DamageSource source) {
        float whole = yoltDealt > 0.0F && yoltWholeHit > yoltDealt ? damage * (yoltWholeHit / yoltDealt) : damage;
        Legs.onFallDamage((LivingEntity) (Object) this, source, whole);
        return damage;
    }
}
