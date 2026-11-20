package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.hardcore.MobBoosts;
import bobbynooby.dev.mixin.MobAccessor;
import bobbynooby.dev.mixin.NearestAttackableTargetGoalAccessor;
import bobbynooby.dev.mixin.TargetGoalAccessor;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Gives hostile mobs the goal that sends them after bodies. */
public final class BodyMobs {
    // How many mannequin bodies each level has loaded, counted as Bodies lists and unlists them. In a level with none,
    // every hostile mob's search for one could only come up empty. Server thread only.
    private static final Reference2IntOpenHashMap<Level> MANNEQUIN_BODIES = new Reference2IntOpenHashMap<>();

    private BodyMobs() {}

    static void init() {
        // Runs for each entity of a chunk in turn, so a throw would break off the rest
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob) {
                Guard.run(() -> MobBoosts.onLoad(mob, level), "Could not boost {}", mob.getUUID());
                Guard.run(() -> addBodyGoal(mob), "Could not let {} hunt bodies", mob.getUUID());
            }
        });
        // Static, so it would outlive the server in a JVM that starts another one
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MANNEQUIN_BODIES.clear());
    }

    static void bodyLoaded(Entity body, Level level) {
        if (body instanceof Mannequin) {
            MANNEQUIN_BODIES.addTo(level, 1);
        }
    }

    static void bodyUnloaded(Entity body, Level level) {
        if (body instanceof Mannequin && MANNEQUIN_BODIES.addTo(level, -1) <= 1) {
            MANNEQUIN_BODIES.removeInt(level);
        }
    }

    /** True when the level has a mannequin body loaded, the only kind the body goal hunts. Public so a test can read it. */
    public static boolean mannequinBodiesIn(Level level) {
        return MANNEQUIN_BODIES.getInt(level) > 0;
    }

    /**
     * Lets a hostile mob hunt bodies, at the priority of its own player goal and with its rules. The goal only touches
     * the mob's own target list, not the entity list, so this is safe during a load. A mob loads again each time its
     * chunk starts ticking again, so it never gets a second goal.
     */
    public static void addBodyGoal(Mob mob) {
        if (!Settings.active() || !BodyTargetGoal.HUNTERS.contains(mob.getType())) {
            return;
        }
        GoalSelector targets = ((MobAccessor) mob).yoltTargetSelector();
        // Phantoms and guardians have no such goal, and pick players with a goal of their own at 1
        int priority = 1;
        NearestAttackableTargetGoal<?> playerGoal = null;
        for (WrappedGoal wrapped : targets.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof BodyTargetGoal) {
                return;
            }
            if (playerGoal == null && wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> goal
                    && ((NearestAttackableTargetGoalAccessor) goal).yoltTargetType() == Player.class) {
                priority = wrapped.getPriority();
                playerGoal = goal;
            }
        }
        if (playerGoal == null) {
            targets.addGoal(priority, new BodyTargetGoal(mob, null));
            return;
        }
        BodyTargetGoal bodyGoal = new BodyTargetGoal(mob,
                ((NearestAttackableTargetGoalAccessor) playerGoal).yoltTargetConditions());
        // How long the mob keeps after a target out of sight: an evoker's or illusioner's 300 ticks, not the usual 60
        bodyGoal.setUnseenMemoryTicks(((TargetGoalAccessor) playerGoal).yoltUnseenMemoryTicks());
        targets.addGoal(priority, bodyGoal);
    }
}
