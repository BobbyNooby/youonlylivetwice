package bobbynooby.dev.hardcore;

import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.mending.MendingSettings;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Range checks on the hardcore settings, which YOLT's config checks run on its hardcore block. A value that fails is
 * put back to the fallback's, and fellBack is told its key, starting with hardcore. as in YOLT's config, and the
 * problem.
 */
public final class HardcoreChecks {
    // Each level is one more pass over the contents of the containers inside, so the count stays cheap
    public static final int MAX_CONTAINER_DEPTH = 8;
    // A day is 24,000 ticks, so a time of day runs 0 to this
    public static final int LAST_TICK_OF_DAY = 23999;
    // An anvil price of 40 or more shows "Too Expensive!"
    public static final int MAX_ANVIL_PRICE = AnvilSettings.MAX_PRICE;
    // Sky light runs 0 to 15
    public static final int MAX_SKY_LIGHT = 15;
    // The heat scan covers a cube this far out every second for each player, so it stays small
    public static final int MAX_HEAT_RADIUS = 8;
    private static final Set<String> FOOD_GROUPS = Set.of("protein", "vitamins", "sugar");
    private static final Set<String> MOB_VALUES = Set.of("health", "damage", "speed");

    private HardcoreChecks() {}

    public static void check(HardcoreConfig.HardcoreSection c, HardcoreConfig.HardcoreSection fallback,
                             BiConsumer<String, String> fellBack) {
        HardcoreConfig.SlowSection slow = c.slow;
        if (!isShare(slow.cap)) {
            slow.cap = fallback.slow.cap;
            fellBack.accept("hardcore.slow.cap", "must be 0 to 1");
        }
        if (!(slow.step > 0)) {
            slow.step = fallback.slow.step;
            fellBack.accept("hardcore.slow.step", "must be above 0");
        }
        // Both go back, since either one could be the typo. A cap of 0 takes no speed at all, so no step is too big for it.
        if (slow.cap > 0 && slow.step > slow.cap) {
            slow.cap = fallback.slow.cap;
            slow.step = fallback.slow.step;
            fellBack.accept("hardcore.slow.step", "must be at most hardcore.slow.cap");
            fellBack.accept("hardcore.slow.cap", "must be at least hardcore.slow.step");
        }

        HardcoreConfig.WeightSection weight = c.weight;
        HardcoreConfig.WeightSection weightFallback = fallback.weight;
        if (!(weight.freeLoad >= 0)) {
            weight.freeLoad = weightFallback.freeLoad;
            fellBack.accept("hardcore.weight.freeLoad", "must be 0 or more");
        }
        if (!(weight.fullLoad >= 0)) {
            weight.fullLoad = weightFallback.fullLoad;
            fellBack.accept("hardcore.weight.fullLoad", "must be 0 or more");
        }
        if (!(weight.freeLoad < weight.fullLoad)) {
            weight.freeLoad = weightFallback.freeLoad;
            weight.fullLoad = weightFallback.fullLoad;
            fellBack.accept("hardcore.weight.freeLoad", "must be below hardcore.weight.fullLoad");
            fellBack.accept("hardcore.weight.fullLoad", "must be above hardcore.weight.freeLoad");
        }
        if (!isShare(weight.maxSlow)) {
            weight.maxSlow = weightFallback.maxSlow;
            fellBack.accept("hardcore.weight.maxSlow", "must be 0 to 1");
        }
        if (!(weight.defaultWeight >= 0)) {
            weight.defaultWeight = weightFallback.defaultWeight;
            fellBack.accept("hardcore.weight.defaultWeight", "must be 0 or more");
        }
        if (!(weight.unstackableWeight >= 0)) {
            weight.unstackableWeight = weightFallback.unstackableWeight;
            fellBack.accept("hardcore.weight.unstackableWeight", "must be 0 or more");
        }
        if (!(weight.toolWeight >= 0)) {
            weight.toolWeight = weightFallback.toolWeight;
            fellBack.accept("hardcore.weight.toolWeight", "must be 0 or more");
        }
        if (!noneNegative(weight.groups)) {
            weight.groups = new LinkedHashMap<>(weightFallback.groups);
            fellBack.accept("hardcore.weight.groups", "every weight must be 0 or more");
        }
        if (!noneNegative(weight.items)) {
            weight.items = new LinkedHashMap<>(weightFallback.items);
            fellBack.accept("hardcore.weight.items", "every weight must be 0 or more");
        }
        if (!noneNegative(weight.itemsEach)) {
            weight.itemsEach = new LinkedHashMap<>(weightFallback.itemsEach);
            fellBack.accept("hardcore.weight.itemsEach", "every weight must be 0 or more");
        }
        if (!noneNegative(weight.armourFactors)) {
            weight.armourFactors = new LinkedHashMap<>(weightFallback.armourFactors);
            fellBack.accept("hardcore.weight.armourFactors", "every factor must be 0 or more");
        }
        if (!isShare(weight.containerContents)) {
            weight.containerContents = weightFallback.containerContents;
            fellBack.accept("hardcore.weight.containerContents", "must be 0 to 1");
        }
        if (weight.containerDepth < 0 || weight.containerDepth > MAX_CONTAINER_DEPTH) {
            weight.containerDepth = weightFallback.containerDepth;
            fellBack.accept("hardcore.weight.containerDepth", "must be 0 to " + MAX_CONTAINER_DEPTH);
        }
        if (!(weight.heavyJumpExhaustion >= 0)) {
            weight.heavyJumpExhaustion = weightFallback.heavyJumpExhaustion;
            fellBack.accept("hardcore.weight.heavyJumpExhaustion", "must be 0 or more");
        }

        legs(c.legs, fallback.legs, fellBack);
        limp(c.limp, fallback.limp, fellBack);
        bedRest(c.bedRest, fallback.bedRest, fellBack);
        curse(c.curse, fallback.curse, fellBack);
        nutrients(c.nutrients, fallback.nutrients, fellBack);
        if (!(c.hungerTax.multiplier >= 0)) {
            c.hungerTax.multiplier = fallback.hungerTax.multiplier;
            fellBack.accept("hardcore.hungerTax.multiplier", "must be 0 or more");
        }
        if (!MendingSettings.isRate(c.mending.durabilityPerXp)) {
            c.mending.durabilityPerXp = fallback.mending.durabilityPerXp;
            fellBack.accept("hardcore.mending.durabilityPerXp", "must be 0 or more");
        }
        anvil(c.anvil, fallback.anvil, fellBack);
        if (!ElytraSettings.isMultiplier(c.elytra.glideGravityMultiplier)) {
            c.elytra.glideGravityMultiplier = fallback.elytra.glideGravityMultiplier;
            fellBack.accept("hardcore.elytra.glideGravityMultiplier", "must be 0 or more");
        }
        if (!ElytraSettings.isSpeed(c.elytra.rocketCutoffSpeed)) {
            c.elytra.rocketCutoffSpeed = fallback.elytra.rocketCutoffSpeed;
            fellBack.accept("hardcore.elytra.rocketCutoffSpeed", "must be 0 or more");
        }
        tougherMobs(c.tougherMobs, fallback.tougherMobs, fellBack);
        if (!(c.relentlessMobs.followRange > 0)) {
            c.relentlessMobs.followRange = fallback.relentlessMobs.followRange;
            fellBack.accept("hardcore.relentlessMobs.followRange", "must be above 0");
        }
        if (!isShare(c.relentlessMobs.doorBreakerChance)) {
            c.relentlessMobs.doorBreakerChance = fallback.relentlessMobs.doorBreakerChance;
            fellBack.accept("hardcore.relentlessMobs.doorBreakerChance", "must be 0 to 1");
        }
        bloodMoon(c.bloodMoon, fallback.bloodMoon, fellBack);
        cold(c.cold, fallback.cold, fellBack);
    }

    // No health would kill a mob as it loads
    private static void tougherMobs(HardcoreConfig.TougherMobsSection mobs, HardcoreConfig.TougherMobsSection fallback,
                                    BiConsumer<String, String> fellBack) {
        if (!(mobs.health > 0)) {
            mobs.health = fallback.health;
            fellBack.accept("hardcore.tougherMobs.health", "must be above 0");
        }
        if (!(mobs.damage >= 0)) {
            mobs.damage = fallback.damage;
            fellBack.accept("hardcore.tougherMobs.damage", "must be 0 or more");
        }
        if (!(mobs.speed >= 0)) {
            mobs.speed = fallback.speed;
            fellBack.accept("hardcore.tougherMobs.speed", "must be 0 or more");
        }
        if (mobs.damageAppliesTo == null) {
            mobs.damageAppliesTo = fallback.damageAppliesTo;
            fellBack.accept("hardcore.tougherMobs.damageAppliesTo", "unknown value");
        }
        if (!mobValues(mobs.overrides)) {
            mobs.overrides = new LinkedHashMap<>(fallback.overrides);
            fellBack.accept("hardcore.tougherMobs.overrides",
                    "every value must name health, damage or speed and be 0 or more, with health above 0");
        }
    }

    // As with tougher mobs, no health would kill a mob as it loads
    private static void bloodMoon(HardcoreConfig.BloodMoonSection moon, HardcoreConfig.BloodMoonSection fallback,
                                  BiConsumer<String, String> fellBack) {
        if (!isShare(moon.chance)) {
            moon.chance = fallback.chance;
            fellBack.accept("hardcore.bloodMoon.chance", "must be 0 to 1");
        }
        if (moon.minNightsBetween < 0) {
            moon.minNightsBetween = fallback.minNightsBetween;
            fellBack.accept("hardcore.bloodMoon.minNightsBetween", "must be 0 or more");
        }
        if (moon.graceDays < 0) {
            moon.graceDays = fallback.graceDays;
            fellBack.accept("hardcore.bloodMoon.graceDays", "must be 0 or more");
        }
        // All three go back, since any one of them could be the typo
        if (!(0 <= moon.warnTick && moon.warnTick < moon.startTick && moon.startTick < moon.endTick
                && moon.endTick <= LAST_TICK_OF_DAY)) {
            moon.warnTick = fallback.warnTick;
            moon.startTick = fallback.startTick;
            moon.endTick = fallback.endTick;
            String order = "must keep 0 <= warnTick < startTick < endTick <= " + LAST_TICK_OF_DAY;
            fellBack.accept("hardcore.bloodMoon.warnTick", order);
            fellBack.accept("hardcore.bloodMoon.startTick", order);
            fellBack.accept("hardcore.bloodMoon.endTick", order);
        }
        if (!(moon.health > 0)) {
            moon.health = fallback.health;
            fellBack.accept("hardcore.bloodMoon.health", "must be above 0");
        }
        if (!(moon.damage >= 0)) {
            moon.damage = fallback.damage;
            fellBack.accept("hardcore.bloodMoon.damage", "must be 0 or more");
        }
        if (!(moon.speed >= 0)) {
            moon.speed = fallback.speed;
            fellBack.accept("hardcore.bloodMoon.speed", "must be 0 or more");
        }
        if (!(moon.spawnCap >= 0)) {
            moon.spawnCap = fallback.spawnCap;
            fellBack.accept("hardcore.bloodMoon.spawnCap", "must be 0 or more");
        }
        if (!isShare(moon.armourChance)) {
            moon.armourChance = fallback.armourChance;
            fellBack.accept("hardcore.bloodMoon.armourChance", "must be 0 to 1");
        }
        if (!isShare(moon.enchantChance)) {
            moon.enchantChance = fallback.enchantChance;
            fellBack.accept("hardcore.bloodMoon.enchantChance", "must be 0 to 1");
        }
        if (!(moon.xp >= 0)) {
            moon.xp = fallback.xp;
            fellBack.accept("hardcore.bloodMoon.xp", "must be 0 or more");
        }
        if (moon.extraLootRolls < 0) {
            moon.extraLootRolls = fallback.extraLootRolls;
            fellBack.accept("hardcore.bloodMoon.extraLootRolls", "must be 0 or more");
        }
        if (!isShare(moon.chargedCreeperChance)) {
            moon.chargedCreeperChance = fallback.chargedCreeperChance;
            fellBack.accept("hardcore.bloodMoon.chargedCreeperChance", "must be 0 to 1");
        }
        if (moon.barColour == null) {
            moon.barColour = fallback.barColour;
            fellBack.accept("hardcore.bloodMoon.barColour", "unknown value");
        }
    }

    // Freeze damage is counted in whole seconds, so the shortest gap is one
    private static void cold(HardcoreConfig.ColdSection cold, HardcoreConfig.ColdSection fallback, BiConsumer<String, String> fellBack) {
        if (!(cold.chillPerSecond >= 0)) {
            cold.chillPerSecond = fallback.chillPerSecond;
            fellBack.accept("hardcore.cold.chillPerSecond", "must be 0 or more");
        }
        if (!(cold.warmPerSecond >= 0)) {
            cold.warmPerSecond = fallback.warmPerSecond;
            fellBack.accept("hardcore.cold.warmPerSecond", "must be 0 or more");
        }
        if (cold.shelteredBelowSkyLight < 0 || cold.shelteredBelowSkyLight > MAX_SKY_LIGHT) {
            cold.shelteredBelowSkyLight = fallback.shelteredBelowSkyLight;
            fellBack.accept("hardcore.cold.shelteredBelowSkyLight", "must be 0 to " + MAX_SKY_LIGHT);
        }
        if (cold.heatRadius < 0 || cold.heatRadius > MAX_HEAT_RADIUS) {
            cold.heatRadius = fallback.heatRadius;
            fellBack.accept("hardcore.cold.heatRadius", "must be 0 to " + MAX_HEAT_RADIUS);
        }
        if (!isShare(cold.leatherPerPiece)) {
            cold.leatherPerPiece = fallback.leatherPerPiece;
            fellBack.accept("hardcore.cold.leatherPerPiece", "must be 0 to 1");
        }
        if (!(cold.wetMultiplier >= 0)) {
            cold.wetMultiplier = fallback.wetMultiplier;
            fellBack.accept("hardcore.cold.wetMultiplier", "must be 0 or more");
        }
        if (!(cold.waterMultiplier >= 0)) {
            cold.waterMultiplier = fallback.waterMultiplier;
            fellBack.accept("hardcore.cold.waterMultiplier", "must be 0 or more");
        }
        if (!isShare(cold.maxSlow)) {
            cold.maxSlow = fallback.maxSlow;
            fellBack.accept("hardcore.cold.maxSlow", "must be 0 to 1");
        }
        if (cold.damageEverySeconds < 1) {
            cold.damageEverySeconds = fallback.damageEverySeconds;
            fellBack.accept("hardcore.cold.damageEverySeconds", "must be 1 or more");
        }
        if (!(cold.shiverExhaustionPerSecond >= 0)) {
            cold.shiverExhaustionPerSecond = fallback.shiverExhaustionPerSecond;
            fellBack.accept("hardcore.cold.shiverExhaustionPerSecond", "must be 0 or more");
        }
    }

    // A price of 0 could never be taken
    private static void anvil(HardcoreConfig.AnvilSection anvil, HardcoreConfig.AnvilSection fallback, BiConsumer<String, String> fellBack) {
        if (!isAnvilPrice(anvil.enchantCost)) {
            anvil.enchantCost = fallback.enchantCost;
            fellBack.accept("hardcore.anvil.enchantCost", "must be 1 to " + MAX_ANVIL_PRICE);
        }
        if (!isAnvilPrice(anvil.repairCost)) {
            anvil.repairCost = fallback.repairCost;
            fellBack.accept("hardcore.anvil.repairCost", "must be 1 to " + MAX_ANVIL_PRICE);
        }
        if (!isAnvilPrice(anvil.renameCost)) {
            anvil.renameCost = fallback.renameCost;
            fellBack.accept("hardcore.anvil.renameCost", "must be 1 to " + MAX_ANVIL_PRICE);
        }
        if (!isShare(anvil.breakChance)) {
            anvil.breakChance = fallback.breakChance;
            fellBack.accept("hardcore.anvil.breakChance", "must be 0 to 1");
        }
    }

    private static boolean isAnvilPrice(int price) {
        return AnvilSettings.isPrice(price);
    }

    private static void legs(HardcoreConfig.LegsSection legs, HardcoreConfig.LegsSection fallback, BiConsumer<String, String> fellBack) {
        if (!(legs.breakAtDamage >= 0)) {
            legs.breakAtDamage = fallback.breakAtDamage;
            fellBack.accept("hardcore.legs.breakAtDamage", "must be 0 or more");
        }
        if (!(legs.breakAtDamageFullLoad >= 0)) {
            legs.breakAtDamageFullLoad = fallback.breakAtDamageFullLoad;
            fellBack.accept("hardcore.legs.breakAtDamageFullLoad", "must be 0 or more");
        }
        // Both go back, since either one could be the typo
        if (legs.breakAtDamageFullLoad > legs.breakAtDamage) {
            legs.breakAtDamage = fallback.breakAtDamage;
            legs.breakAtDamageFullLoad = fallback.breakAtDamageFullLoad;
            fellBack.accept("hardcore.legs.breakAtDamageFullLoad", "must be at most hardcore.legs.breakAtDamage");
            fellBack.accept("hardcore.legs.breakAtDamage", "must be at least hardcore.legs.breakAtDamageFullLoad");
        }
        if (!isShare(legs.slow)) {
            legs.slow = fallback.slow;
            fellBack.accept("hardcore.legs.slow", "must be 0 to 1");
        }
        if (legs.healPoints < 0) {
            legs.healPoints = fallback.healPoints;
            fellBack.accept("hardcore.legs.healPoints", "must be 0 or more");
        }
        if (legs.healTicks < 0) {
            legs.healTicks = fallback.healTicks;
            fellBack.accept("hardcore.legs.healTicks", "must be 0 or more");
        }
        if (legs.healTicksSaturated < 0) {
            legs.healTicksSaturated = fallback.healTicksSaturated;
            fellBack.accept("hardcore.legs.healTicksSaturated", "must be 0 or more");
        }
        if (legs.healTicksNoRegenRule < 0) {
            legs.healTicksNoRegenRule = fallback.healTicksNoRegenRule;
            fellBack.accept("hardcore.legs.healTicksNoRegenRule", "must be 0 or more");
        }
        if (!(legs.exhaustionPerPoint >= 0)) {
            legs.exhaustionPerPoint = fallback.exhaustionPerPoint;
            fellBack.accept("hardcore.legs.exhaustionPerPoint", "must be 0 or more");
        }
    }

    private static void limp(HardcoreConfig.LimpSection limp, HardcoreConfig.LimpSection fallback, BiConsumer<String, String> fellBack) {
        if (!(limp.belowHearts > 0)) {
            limp.belowHearts = fallback.belowHearts;
            fellBack.accept("hardcore.limp.belowHearts", "must be more than 0");
        }
        if (!(limp.recoverAtHearts > 0)) {
            limp.recoverAtHearts = fallback.recoverAtHearts;
            fellBack.accept("hardcore.limp.recoverAtHearts", "must be more than 0");
        }
        // Both go back, since either one could be the typo
        if (!(limp.belowHearts < limp.recoverAtHearts)) {
            limp.belowHearts = fallback.belowHearts;
            limp.recoverAtHearts = fallback.recoverAtHearts;
            fellBack.accept("hardcore.limp.belowHearts", "must be less than hardcore.limp.recoverAtHearts");
            fellBack.accept("hardcore.limp.recoverAtHearts", "must be more than hardcore.limp.belowHearts");
        }
        if (!isShare(limp.slow)) {
            limp.slow = fallback.slow;
            fellBack.accept("hardcore.limp.slow", "must be 0 to 1");
        }
    }

    private static void bedRest(HardcoreConfig.BedRestSection bedRest, HardcoreConfig.BedRestSection fallback,
                                BiConsumer<String, String> fellBack) {
        if (bedRest.healEveryTicks < 0) {
            bedRest.healEveryTicks = fallback.healEveryTicks;
            fellBack.accept("hardcore.bedRest.healEveryTicks", "must be 0 or more");
        }
        if (!(bedRest.exhaustionPerHeal >= 0)) {
            bedRest.exhaustionPerHeal = fallback.exhaustionPerHeal;
            fellBack.accept("hardcore.bedRest.exhaustionPerHeal", "must be 0 or more");
        }
        if (!(bedRest.legHealSpeed >= 0)) {
            bedRest.legHealSpeed = fallback.legHealSpeed;
            fellBack.accept("hardcore.bedRest.legHealSpeed", "must be 0 or more");
        }
    }

    private static void curse(HardcoreConfig.CurseSection curse, HardcoreConfig.CurseSection fallback,
                              BiConsumer<String, String> fellBack) {
        if (curse.heartsPerDeath < 0) {
            curse.heartsPerDeath = fallback.heartsPerDeath;
            fellBack.accept("hardcore.curse.heartsPerDeath", "must be 0 or more");
        }
        if (curse.heartsPerTotem < 0) {
            curse.heartsPerTotem = fallback.heartsPerTotem;
            fellBack.accept("hardcore.curse.heartsPerTotem", "must be 0 or more");
        }
        if (curse.maxLostHearts < 0) {
            curse.maxLostHearts = fallback.maxLostHearts;
            fellBack.accept("hardcore.curse.maxLostHearts", "must be 0 or more");
        }
        if (curse.healHearts < 0) {
            curse.healHearts = fallback.healHearts;
            fellBack.accept("hardcore.curse.healHearts", "must be 0 or more");
        }
        if (!(curse.healEveryDays > 0)) {
            curse.healEveryDays = fallback.healEveryDays;
            fellBack.accept("hardcore.curse.healEveryDays", "must be above 0");
        }
        // At 0 no play time would count with countAfk off, so the curse would never heal
        if (curse.afkMinutes < 1) {
            curse.afkMinutes = fallback.afkMinutes;
            fellBack.accept("hardcore.curse.afkMinutes", "must be 1 or more");
        }
        if (curse.reviveRefundHearts < 0) {
            curse.reviveRefundHearts = fallback.reviveRefundHearts;
            fellBack.accept("hardcore.curse.reviveRefundHearts", "must be 0 or more");
        }
    }

    private static void nutrients(HardcoreConfig.NutrientsSection nutrients, HardcoreConfig.NutrientsSection fallback,
                                  BiConsumer<String, String> fellBack) {
        if (!(nutrients.gainPerPoint >= 0)) {
            nutrients.gainPerPoint = fallback.gainPerPoint;
            fellBack.accept("hardcore.nutrients.gainPerPoint", "must be 0 or more");
        }
        if (!(nutrients.decayPerPoint >= 0)) {
            nutrients.decayPerPoint = fallback.decayPerPoint;
            fellBack.accept("hardcore.nutrients.decayPerPoint", "must be 0 or more");
        }
        if (!isShare(nutrients.startLevel)) {
            nutrients.startLevel = fallback.startLevel;
            fellBack.accept("hardcore.nutrients.startLevel", "must be 0 to 1");
        }
        if (!isShare(nutrients.deficientBelow)) {
            nutrients.deficientBelow = fallback.deficientBelow;
            fellBack.accept("hardcore.nutrients.deficientBelow", "must be 0 to 1");
        }
        HardcoreConfig.PenaltiesSection penalties = nutrients.penalties;
        if (!(penalties.protein.healingMultiplier >= 0)) {
            penalties.protein.healingMultiplier = fallback.penalties.protein.healingMultiplier;
            fellBack.accept("hardcore.nutrients.penalties.protein.healingMultiplier", "must be 0 or more");
        }
        if (!isShare(penalties.sugar.sprintSlow)) {
            penalties.sugar.sprintSlow = fallback.penalties.sugar.sprintSlow;
            fellBack.accept("hardcore.nutrients.penalties.sugar.sprintSlow", "must be 0 to 1");
        }
        if (!isShare(penalties.vitamins.miningSlow)) {
            penalties.vitamins.miningSlow = fallback.penalties.vitamins.miningSlow;
            fellBack.accept("hardcore.nutrients.penalties.vitamins.miningSlow", "must be 0 to 1");
        }
        if (!allShares(nutrients.foods)) {
            nutrients.foods = new LinkedHashMap<>(fallback.foods);
            fellBack.accept("hardcore.nutrients.foods", "every share must be 0 to 1 and name protein, vitamins or sugar");
        }
        if (nutrients.unknownFood == null) {
            nutrients.unknownFood = fallback.unknownFood;
            fellBack.accept("hardcore.nutrients.unknownFood", "unknown value");
        }
    }

    /**
     * A notice, or null, when the curse can take a player below the hearts the limp needs to stop: such a player limps
     * until a curse heart comes back. A server may want that as a punishment, so it is allowed and only said.
     */
    public static @Nullable String curseOutlastsLimp(HardcoreConfig.HardcoreSection hardcore) {
        HardcoreConfig.CurseSection curse = hardcore.curse;
        HardcoreConfig.LimpSection limp = hardcore.limp;
        if (!curse.enabled || !limp.enabled || 10 - curse.maxLostHearts > limp.recoverAtHearts) {
            return null;
        }
        return "with hardcore.curse.maxLostHearts " + curse.maxLostHearts + ", a fully cursed player has "
                + Math.max(0, 10 - curse.maxLostHearts) + " hearts, not more than hardcore.limp.recoverAtHearts " + limp.recoverAtHearts
                + ", so they limp until a curse heart comes back. This is allowed; it is only a notice.";
    }

    // Written so NaN fails too
    private static boolean isShare(double value) {
        return value >= 0 && value <= 1;
    }

    private static boolean allShares(Map<String, Map<String, Double>> foods) {
        for (Map<String, Double> shares : foods.values()) {
            if (shares == null) {
                return false;
            }
            for (Map.Entry<String, Double> share : shares.entrySet()) {
                // A misspelt group would quietly give nothing
                if (!FOOD_GROUPS.contains(share.getKey()) || share.getValue() == null || !isShare(share.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean mobValues(Map<String, Map<String, Double>> overrides) {
        for (Map<String, Double> values : overrides.values()) {
            if (values == null) {
                return false;
            }
            for (Map.Entry<String, Double> value : values.entrySet()) {
                // A misspelt name would quietly change nothing
                Double number = value.getValue();
                if (!MOB_VALUES.contains(value.getKey()) || number == null || !(number >= 0)
                        || value.getKey().equals("health") && !(number > 0)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean noneNegative(Map<String, Double> values) {
        for (Double value : values.values()) {
            if (value == null || !(value >= 0)) {
                return false;
            }
        }
        return true;
    }
}
