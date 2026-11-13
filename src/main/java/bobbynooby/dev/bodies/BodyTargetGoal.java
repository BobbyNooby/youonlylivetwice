package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.hardcore.Relentless;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/** Sends a hostile mob after mannequin bodies the way it goes after players. */
public final class BodyTargetGoal extends NearestAttackableTargetGoal<Mannequin> {
    /**
     * The goal-based mobs that hunt players on sight. Neutral ones, such as endermen, zombified piglins and iron golems,
     * are left out, and so are the brain-based ones, whose targeting doesn't use goals.
     */
    public static final Set<EntityType<?>> HUNTERS = Set.of(
            EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED, EntityTypes.ZOMBIE_VILLAGER,
            EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.BOGGED, EntityTypes.PARCHED, EntityTypes.WITHER_SKELETON,
            EntityTypes.CREEPER, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER, EntityTypes.WITCH,
            EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.EVOKER, EntityTypes.ILLUSIONER, EntityTypes.RAVAGER,
            EntityTypes.VEX, EntityTypes.BLAZE, EntityTypes.GHAST, EntityTypes.SLIME, EntityTypes.MAGMA_CUBE, EntityTypes.PHANTOM,
            EntityTypes.SILVERFISH, EntityTypes.ENDERMITE, EntityTypes.GUARDIAN, EntityTypes.ELDER_GUARDIAN, EntityTypes.SHULKER);

    // What the phantom's own player search reaches: it circles far above whoever it is after
    private static final double PHANTOM_RANGE = 64.0;

    /**
     * @param playerConditions the mob's own player goal's conditions, which a body has to meet as well (a slime's
     *                         height limit, a drowned's daylight rule); null when the mob has no such goal
     */
    public BodyTargetGoal(Mob mob, @Nullable TargetingConditions playerConditions) {
        super(mob, Mannequin.class, true, (target, level) -> BodyRules.isBody(target)
                && (playerConditions == null || playerConditions.test(level, mob, target))
                // The guardian's own rule: nothing within 3 blocks, where its beam would stop
                && !(mob instanceof Guardian && target.distanceToSqr(mob) <= 9.0));
    }

    // The light call is deprecated in 26.3 with nothing to replace it, and vanilla's own spider goal still uses it
    @SuppressWarnings("deprecation")
    @Override
    public boolean canUse() {
        // The spider's own rule: no hunting in daylight
        if (!switchedOn() || this.mob instanceof Spider && this.mob.getLightLevelDependentMagicValue() >= 0.5F) {
            return false;
        }
        // The phantom's own player goal doesn't claim the target, so without this the two would keep taking it off each other
        if (this.mob instanceof Phantom && this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
            return false;
        }
        return super.canUse();
    }

    // Also checked here, so turning the switch off stops a hunt already under way
    @Override
    public boolean canContinueToUse() {
        return !phantomRetargeted() && switchedOn() && super.canContinueToUse();
    }

    // Only a body is this goal's to let go of: the phantom's own goal keeps after the player it picked
    @Override
    public void stop() {
        if (phantomRetargeted()) {
            this.targetMob = null;
            return;
        }
        super.stop();
    }

    // Vanilla's reach even when relentless mobs track further: a wider search for bodies costs more than it adds.
    // Public so a test can read it.
    @Override
    public double getFollowDistance() {
        return this.mob instanceof Phantom ? PHANTOM_RANGE : Relentless.vanillaFollowRange(this.mob);
    }

    @Override
    protected AABB getTargetSearchArea(double followDistance) {
        AABB box = this.mob.getBoundingBox();
        if (this.mob instanceof Phantom) {
            return box.inflate(16.0, PHANTOM_RANGE, 16.0);
        }
        // The shulker's own search: only 4 blocks either way along the axis of the face it sits on
        if (this.mob instanceof Shulker shulker) {
            Direction.Axis axis = shulker.getAttachFace().getAxis();
            return box.inflate(axis == Direction.Axis.X ? 4.0 : followDistance, axis == Direction.Axis.Y ? 4.0 : followDistance,
                    axis == Direction.Axis.Z ? 4.0 : followDistance);
        }
        return super.getTargetSearchArea(followDistance);
    }

    // The phantom's own player goal sets its target without claiming it, so it can switch to a player under this goal
    private boolean phantomRetargeted() {
        return this.mob instanceof Phantom && this.mob.getTarget() != null && !(this.mob.getTarget() instanceof Mannequin);
    }

    private static boolean switchedOn() {
        return Settings.on(c -> c.bodies.mobsAttack);
    }
}
