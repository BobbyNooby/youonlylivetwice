package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.Legs;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class HardcoreFallMixin {
    // Both actuallyHurt methods, the living entity's and the player's own, take this value just before absorption
    // hearts soak it up, so this sees every hit after armour and protection enchantments. The damage is left as it is.
    @ModifyReturnValue(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"))
    private float seeFallDamage(float damage, @Local(argsOnly = true) DamageSource source) {
        Legs.onFallDamage((LivingEntity) (Object) this, source, damage);
        return damage;
    }
}
