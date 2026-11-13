package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.MobBoosts;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public abstract class HardcoreMobDamageMixin {
    // A player's own hurtServer scales for the difficulty before it gets here, and BodyDifficultyDamageMixin wraps the
    // whole method, so a body's damage is scaled the same way before this as well
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float boostMobHit(float damage, @Local(argsOnly = true) DamageSource source) {
        return MobBoosts.damage((LivingEntity) (Object) this, source, damage);
    }
}
