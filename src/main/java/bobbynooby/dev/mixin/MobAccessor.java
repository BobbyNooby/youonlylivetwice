package bobbynooby.dev.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets BodyMobs.addBodyGoal put the body goal beside a mob's own target goals
@Mixin(Mob.class)
public interface MobAccessor {
    @Accessor("targetSelector")
    GoalSelector yoltTargetSelector();
}
