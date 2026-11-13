package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Relentless mobs: hostile mobs track from further away, through a saved follow-range modifier set as they load, and a
 * share of zombies can break wooden doors on every difficulty, the door dropping as an item.
 */
public final class Relentless {
    public static final Identifier RELENTLESS = Yolt.id("relentless");
    /** Its door-breaking roll is done, so it never rolls again. */
    public static final String DOOR_ROLLED = "yolt.door_rolled";

    private Relentless() {}

    /** Sets or removes the follow-range modifier to match the config, and gives a zombie its one door-breaking roll. */
    public static void onLoad(Mob mob) {
        if (!Settings.on(c -> c.hardcore.relentlessMobs.enabled) || !touches(mob)) {
            MobBoosts.multiply(mob, Attributes.FOLLOW_RANGE, RELENTLESS, 1.0);
            return;
        }
        HardcoreConfig.RelentlessMobsSection config = config();
        MobBoosts.multiply(mob, Attributes.FOLLOW_RANGE, RELENTLESS, config.followRange);
        // A zombie that won keeps it for good, saved with it, even once the feature is off
        if (mob instanceof Zombie zombie && !zombie.entityTags().contains(DOOR_ROLLED)) {
            if (zombie.getRandom().nextDouble() < config.doorBreakerChance) {
                zombie.setCanBreakDoors(true);
            }
            zombie.addTag(DOOR_ROLLED);
        }
    }

    /** The mob's follow range as vanilla has it, without our modifier, for searches we keep at vanilla's reach. */
    public static double vanillaFollowRange(Mob mob) {
        double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        AttributeInstance instance = mob.getAttribute(Attributes.FOLLOW_RANGE);
        AttributeModifier ours = instance == null ? null : instance.getModifier(RELENTLESS);
        // Each multiplied-total modifier multiplies on its own, so dividing ours out leaves the rest as they are
        return ours == null ? range : range / (1.0 + ours.amount());
    }

    /** True when a zombie's Hard-only door breaking should work on this difficulty too. */
    public static boolean breaksDoorsOnAnyDifficulty(Mob mob) {
        return Settings.on(c -> c.hardcore.relentlessMobs.enabled && c.hardcore.relentlessMobs.doorsAnyDifficulty)
                && mob instanceof Zombie && touches(mob);
    }

    /** True when a door this mob breaks should drop rather than vanish. */
    public static boolean dropsBrokenDoor(Mob mob) {
        return Settings.on(c -> c.hardcore.relentlessMobs.enabled && c.hardcore.relentlessMobs.dropBrokenDoor) && touches(mob);
    }

    /**
     * Breaks the door with its drop. The mob's goal points at the top half, and a door drops only from its bottom
     * half, so that is the half broken; the top then goes with it and drops nothing, which makes one door item.
     */
    public static boolean breakDoor(Level level, BlockPos pos, Mob mob) {
        BlockState state = level.getBlockState(pos);
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        boolean topHalf = state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                && belowState.getBlock() instanceof DoorBlock && belowState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        return level.destroyBlock(topHalf ? below : pos, true, mob);
    }

    // Hostile mobs only, and never one on either exclude list
    private static boolean touches(Mob mob) {
        return mob instanceof Enemy && !MobBoosts.excludedBy(mob, config().exclude)
                && !MobBoosts.excludedBy(mob, Settings.get().hardcore.tougherMobs.exclude);
    }

    private static HardcoreConfig.RelentlessMobsSection config() {
        return Settings.get().hardcore.relentlessMobs;
    }
}
