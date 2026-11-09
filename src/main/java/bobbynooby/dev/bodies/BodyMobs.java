package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.mixin.MobAccessor;
import bobbynooby.dev.mixin.NearestAttackableTargetGoalAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;

/** Gives hostile mobs the goal that sends them after bodies. */
public final class BodyMobs {
    private BodyMobs() {
    }

    static void init() {
        // Runs for each entity of a chunk in turn, so a throw would break off the rest
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob) {
                Guard.run(() -> addBodyGoal(mob), "Could not let {} hunt bodies", mob.getUUID());
            }
        });
    }

    /**
     * Lets a hostile mob hunt bodies, at the priority of its own player goal. The goal only touches the mob's own
     * target list, not the entity list, so this is safe during a load. A mob loads again each time its chunk starts
     * ticking again, so it never gets a second goal.
     */
    public static void addBodyGoal(Mob mob) {
        if (!Settings.active() || !BodyTargetGoal.HUNTERS.contains(mob.getType())) {
            return;
        }
        GoalSelector targets = ((MobAccessor) mob).yoltTargetSelector();
        // Phantoms and guardians have no such goal, and pick players with a goal of their own at 1
        int priority = 1;
        TargetingConditions playerConditions = null;
        for (WrappedGoal wrapped : targets.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof BodyTargetGoal) {
                return;
            }
            if (playerConditions == null && wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> goal
                    && ((NearestAttackableTargetGoalAccessor) goal).yoltTargetType() == Player.class) {
                priority = wrapped.getPriority();
                playerConditions = ((NearestAttackableTargetGoalAccessor) goal).yoltTargetConditions();
            }
        }
        targets.addGoal(priority, new BodyTargetGoal(mob, playerConditions));
    }
}
