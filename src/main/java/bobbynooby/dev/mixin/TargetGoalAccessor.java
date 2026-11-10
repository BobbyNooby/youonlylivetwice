package bobbynooby.dev.mixin;

import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets BodyMobs.addBodyGoal give the body goal the memory of the mob's own player goal. The field is TargetGoal's, so
// an accessor on NearestAttackableTargetGoal can't reach it.
@Mixin(TargetGoal.class)
public interface TargetGoalAccessor {
    @Accessor("unseenMemoryTicks")
    int yoltUnseenMemoryTicks();
}
