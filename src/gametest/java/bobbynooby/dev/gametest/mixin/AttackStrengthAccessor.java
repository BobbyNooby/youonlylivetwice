package bobbynooby.dev.gametest.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface AttackStrengthAccessor {
    // A test player never ticks, so its attack never charges up by itself
    @Accessor("attackStrengthTicker")
    void yoltSetAttackStrengthTicker(int ticks);
}
