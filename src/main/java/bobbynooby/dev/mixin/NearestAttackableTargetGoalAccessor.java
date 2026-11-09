package bobbynooby.dev.mixin;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets Bodies.addBodyGoal find a mob's own player goal, so a body gets the same priority and conditions
@Mixin(NearestAttackableTargetGoal.class)
public interface NearestAttackableTargetGoalAccessor {
    @Accessor("targetType")
    Class<?> yoltTargetType();

    @Accessor("targetConditions")
    TargetingConditions yoltTargetConditions();
}
