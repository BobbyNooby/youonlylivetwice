package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.BodyRules;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

// Vanilla scales mob damage for the difficulty in Player.hurtServer only, so a mannequin body took Normal damage on
// every difficulty. A player's own override calls into this too, so BodyRules only scales mannequins.
@Mixin(LivingEntity.class)
public abstract class BodyDifficultyDamageMixin {
    @WrapMethod(method = "hurtServer")
    private boolean scaleBodyDamage(ServerLevel level, DamageSource source, float damage, Operation<Boolean> original) {
        float taken = BodyRules.damageTaken((LivingEntity) (Object) this, level, source, damage);
        if (!BodyRules.hitLands(damage, taken)) {
            return false;
        }
        return original.call(level, source, taken);
    }
}
