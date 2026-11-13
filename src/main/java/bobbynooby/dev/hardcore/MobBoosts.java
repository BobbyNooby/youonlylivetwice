package bobbynooby.dev.hardcore;

import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.CreeperAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tougher mobs: hostile mobs get more health and speed as saved attribute modifiers, set each time a mob loads, and hit
 * harder when a hit lands. A mob is judged once, the first time it is seen, and the verdict is kept as an entity tag,
 * because how it was spawned is only known then and a mob loads again each time its chunk starts ticking.
 */
public final class MobBoosts {
    public static final Identifier TOUGHER = Yolt.id("tougher");
    /** Boosted, and its health scaled already. */
    public static final String TOUGH = "yolt.tough";
    /** Left alone for good, for how it was spawned. */
    public static final String VANILLA = "yolt.vanilla";
    public static final Identifier BLOOD_MOON = Yolt.id("blood_moon");
    /** First seen during a blood moon, and boosted for it for life. */
    public static final String BLOOD_MOON_MARK = "yolt.blood_moon";

    private MobBoosts() {}

    /** Decides a mob seen for the first time, then sets or removes the modifiers to match the config. */
    public static void onLoad(Mob mob, ServerLevel level) {
        // Asked before tougher mobs tags it, which would make a new mob look seen before
        boolean firstSight = firstSight(mob);
        toughen(mob);
        markForBloodMoon(mob, level, firstSight);
        Relentless.onLoad(mob);
    }

    // New to the world: not loaded from disk, not just through a portal, never ticked, and with no verdict of ours yet.
    // A mob loads again each time its chunk starts ticking, so the tick count tells one already about from one just
    // spawned; a portal makes a fresh copy of a mob that already lived elsewhere.
    private static boolean firstSight(Mob mob) {
        Set<String> tags = mob.entityTags();
        EntitySpawnReason reason = mob.spawnReason();
        return mob.tickCount == 0 && reason != EntitySpawnReason.LOAD && reason != EntitySpawnReason.DIMENSION_TRAVEL
                && !tags.contains(TOUGH) && !tags.contains(VANILLA) && !tags.contains(BLOOD_MOON_MARK);
    }

    // The same mobs tougher mobs would touch, whether or not tougher mobs is on; its boost goes on top of that one
    private static void markForBloodMoon(Mob mob, ServerLevel level, boolean firstSight) {
        if (!Settings.on(c -> c.hardcore.bloodMoon.enabled)) {
            applyBloodMoon(mob, MobMaths.Boost.NONE);
            return;
        }
        if (mob.entityTags().contains(BLOOD_MOON_MARK)) {
            // A cured zombie villager carries the mark over; a villager gets nothing, and a later zombie of it starts
            // with no mark
            if (!(mob instanceof Enemy)) {
                applyBloodMoon(mob, MobMaths.Boost.NONE);
                mob.removeTag(BLOOD_MOON_MARK);
                return;
            }
            MobMaths.Boost boost = bloodMoonBoost();
            boolean unscaled = convertedUnscaled(mob, BLOOD_MOON);
            applyBloodMoon(mob, boost);
            if (unscaled) {
                mob.setHealth(mob.getHealth() * (float) boost.health());
            }
            return;
        }
        if (!firstSight || !BloodMoon.activeIn(level) || !eligible(mob)) {
            return;
        }
        MobMaths.Boost boost = bloodMoonBoost();
        applyBloodMoon(mob, boost);
        // Once only, as with tougher mobs
        mob.setHealth(mob.getHealth() * (float) boost.health());
        mob.addTag(BLOOD_MOON_MARK);
        if (mob instanceof Creeper creeper && creeper.getRandom().nextDouble() < Settings.get().hardcore.bloodMoon.chargedCreeperChance) {
            creeper.getEntityData().set(CreeperAccessor.yoltPowered(), true);
        }
    }

    private static void applyBloodMoon(Mob mob, MobMaths.Boost boost) {
        multiply(mob, Attributes.MAX_HEALTH, BLOOD_MOON, boost.health());
        multiply(mob, Attributes.MOVEMENT_SPEED, BLOOD_MOON, boost.speed());
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static MobMaths.Boost bloodMoonBoost() {
        HardcoreConfig.BloodMoonSection config = Settings.get().hardcore.bloodMoon;
        return MobMaths.bloodMoon(config.health, config.damage, config.speed);
    }

    private static void toughen(Mob mob) {
        if (!Settings.on(c -> c.hardcore.tougherMobs.enabled)) {
            apply(mob, MobMaths.Boost.NONE);
            return;
        }
        HardcoreConfig.TougherMobsSection config = config();
        if (mob.entityTags().contains(TOUGH)) {
            // An id added to exclude since takes the boost off again, but its health was scaled already
            MobMaths.Boost boost = boost(mob, config);
            boolean unscaled = convertedUnscaled(mob, TOUGHER);
            apply(mob, boost);
            if (unscaled) {
                mob.setHealth(mob.getHealth() * (float) boost.health());
            }
            return;
        }
        if (!(mob instanceof Enemy) || excluded(mob, config) || mob.entityTags().contains(VANILLA)) {
            return;
        }
        if (!spawnAllowed(mob.spawnReason(), config)) {
            mob.addTag(VANILLA);
            return;
        }
        MobMaths.Boost boost = boost(mob, config);
        apply(mob, boost);
        // Once only: the modifier is saved with the mob, and so is the health it already has
        mob.setHealth(mob.getHealth() * (float) boost.health());
        mob.addTag(TOUGH);
    }

    // A mob converted from a boosted one (a zombie that drowned, a skeleton that froze) gets the old mob's tags but not
    // its health or modifiers, so its health is scaled once more. The modifier, missing until then, keeps a second load
    // of the same mob from scaling it again.
    private static boolean convertedUnscaled(Mob mob, Identifier id) {
        if (mob.spawnReason() != EntitySpawnReason.CONVERSION || mob.isLoadedFromDisk()) {
            return false;
        }
        AttributeInstance maxHealth = mob.getAttribute(Attributes.MAX_HEALTH);
        return maxHealth != null && maxHealth.getModifier(id) == null;
    }

    /** True for a hostile mob tougher mobs would boost on first sight. */
    public static boolean eligible(Mob mob) {
        HardcoreConfig.TougherMobsSection config = config();
        return mob instanceof Enemy && !excluded(mob, config) && !mob.entityTags().contains(VANILLA)
                && spawnAllowed(mob.spawnReason(), config);
    }

    /**
     * The damage a hit deals: times the attacker's damage boosts when a boosted mob landed it directly, tougher mobs'
     * and the blood moon's multiplied together.
     */
    public static float damage(LivingEntity victim, DamageSource source, float amount) {
        boolean tougher = Settings.on(c -> c.hardcore.tougherMobs.enabled);
        boolean bloodMoon = Settings.on(c -> c.hardcore.bloodMoon.enabled);
        if (!tougher && !bloodMoon) {
            return amount;
        }
        // The one who caused it, so a skeleton's arrow counts and poison or fire, which have none, don't
        if (!(source.getEntity() instanceof Mob attacker)) {
            return amount;
        }
        boolean tough = tougher && attacker.entityTags().contains(TOUGH);
        boolean marked = bloodMoon && attacker.entityTags().contains(BLOOD_MOON_MARK);
        if (!tough && !marked) {
            return amount;
        }
        HardcoreConfig.TougherMobsSection config = config();
        if (config.damageAppliesTo != HardcoreConfig.DamageAppliesTo.ALL && !(victim instanceof Player)
                && !BodyRules.isBody(victim)) {
            return amount;
        }
        MobMaths.Boost boost = tough ? boost(attacker, config) : MobMaths.Boost.NONE;
        if (marked) {
            boost = boost.times(bloodMoonBoost());
        }
        double multiplier = boost.damage();
        return multiplier == 1.0 ? amount : (float) (amount * multiplier);
    }

    /**
     * Sets a multiplier on one attribute as a saved modifier of our own, or takes it off at 1. Saved, because a mob's
     * health is checked against its max health as it loads, before any hook of ours runs.
     */
    static void multiply(LivingEntity entity, Holder<Attribute> attribute, Identifier id, double multiplier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (multiplier == 1.0) {
            instance.removeModifier(id);
            return;
        }
        double amount = multiplier - 1.0;
        AttributeModifier current = instance.getModifier(id);
        // Unchanged, so a chunk that keeps loading doesn't resend the attribute each time
        if (current != null && current.amount() == amount
                && current.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
            return;
        }
        instance.addOrReplacePermanentModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    static boolean excludedBy(Mob mob, List<String> exclude) {
        return exclude.contains(id(mob));
    }

    private static void apply(Mob mob, MobMaths.Boost boost) {
        multiply(mob, Attributes.MAX_HEALTH, TOUGHER, boost.health());
        multiply(mob, Attributes.MOVEMENT_SPEED, TOUGHER, boost.speed());
        // Vanilla only brings health down to a lower max on the mob's next tick
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static MobMaths.Boost boost(Mob mob, HardcoreConfig.TougherMobsSection config) {
        if (!(mob instanceof Enemy)) {
            return MobMaths.Boost.NONE;
        }
        return MobMaths.tougher(id(mob), config.health, config.damage, config.speed, new HashSet<>(config.exclude),
                config.overrides);
    }

    private static boolean excluded(Mob mob, HardcoreConfig.TougherMobsSection config) {
        return excludedBy(mob, config.exclude);
    }

    // A spawn egg from a dispenser counts as an egg. A mob loaded from disk shows LOAD, and one made without
    // EntityType.create shows nothing: neither says how it first came, so both count as natural.
    private static boolean spawnAllowed(@Nullable EntitySpawnReason reason, HardcoreConfig.TougherMobsSection config) {
        if (reason == null) {
            return true;
        }
        return switch (reason) {
            case SPAWNER -> config.includeSpawners;
            case TRIAL_SPAWNER -> config.includeTrialSpawners;
            case COMMAND, SPAWN_ITEM_USE, DISPENSER -> config.includeCommandsAndEggs;
            default -> true;
        };
    }

    private static String id(Mob mob) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
    }

    private static HardcoreConfig.TougherMobsSection config() {
        return Settings.get().hardcore.tougherMobs;
    }
}
