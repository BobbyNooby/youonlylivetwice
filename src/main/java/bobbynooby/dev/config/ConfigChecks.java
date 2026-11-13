package bobbynooby.dev.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Range checks on a loaded config. A value that fails is replaced by the fallback's. */
final class ConfigChecks {
    // Vanilla's own sample never has more than 12 names. Past these, a long list could push the status reply over its size limit.
    static final int MAX_HOVER_LINES = 12;
    static final int MAX_HOVER_LINE_LENGTH = 256;
    // A player is sent only one line, so the count just keeps the list a sane size, with room over the 13 lines YOLT
    // ships with. A line is cut where a hover line is.
    static final int MAX_TAB_HEADER_LINES = 64;
    static final int MAX_TAB_HEADER_LINE_LENGTH = 256;
    // A login waits on a skin for at most this long. Vanilla ends a login 600 ticks after it began and a connection 30
    // seconds after the client's last packet, and online-mode checks and other mods' login steps come before the wait,
    // so this leaves two thirds of both for them.
    static final int MAX_SKIN_LOOKUP_SECONDS = 10;
    // A grave's first search already covers 3 blocks round the death, so the wide search starts 4 out, and a
    // searchRadius of 3 means no wide search. At 32 it checks up to 37,584 spots on the server thread (9 layers of the
    // rings 4 to 32 out), four times the 9,360 at the default 16.
    static final int MIN_GRAVE_SEARCH_RADIUS = 3;
    // A death over the void has no first search and goes straight to the land search, so a voidSearchRadius of 1 or 2
    // is a real, smaller search. At 32 it reads up to 4,225 columns.
    static final int MIN_VOID_SEARCH_RADIUS = 1;
    static final int MAX_GRAVE_SEARCH_RADIUS = 32;
    // Each level is one more pass over the contents of the containers inside, so the count stays cheap
    static final int MAX_CONTAINER_DEPTH = 8;
    // A day is 24,000 ticks, so a time of day runs 0 to this
    static final int LAST_TICK_OF_DAY = 23999;
    // An anvil price of 40 or more shows "Too Expensive!"
    static final int MAX_ANVIL_PRICE = 39;
    private static final Set<String> FOOD_GROUPS = Set.of("protein", "vitamins", "sugar");
    private static final Set<String> MOB_VALUES = Set.of("health", "damage", "speed");

    private ConfigChecks() {}

    /**
     * fallbackName ends each warning the way the loader's own do: "default" at startup, "the current value" on a reload.
     * Returns the keys put back to the fallback. The End cap isn't one: it keeps the largest border vanilla allows
     * instead of swapping in a fallback.
     */
    static List<String> validate(YoltConfig c, YoltConfig fallback, List<String> warnings, String fallbackName) {
        List<String> reset = new ArrayList<>();
        BiConsumer<String, String> fellBack = (path, problem) -> {
            reset.add(path);
            warnings.add(path + ": " + problem + ", using " + fallbackName);
        };
        if (c.worldBorder.diameter < 1 || c.worldBorder.diameter > ConfigLoader.MAX_BORDER_DIAMETER) {
            c.worldBorder.diameter = fallback.worldBorder.diameter;
            fellBack.accept("worldBorder.diameter", "must be 1 to " + ConfigLoader.MAX_BORDER_DIAMETER);
        }
        if (!Double.isFinite(c.worldBorder.endScale) || c.worldBorder.endScale <= 0) {
            c.worldBorder.endScale = fallback.worldBorder.endScale;
            fellBack.accept("worldBorder.endScale", "must be above 0");
        }
        // The End border is the overworld's times endScale, and vanilla's border can't be larger than its maximum
        double maxEndScale = (double) ConfigLoader.MAX_BORDER_DIAMETER / c.worldBorder.diameter;
        // The division can round up, which would put the End border a hair past the maximum
        if (c.worldBorder.diameter * maxEndScale > ConfigLoader.MAX_BORDER_DIAMETER) {
            maxEndScale = Math.nextDown(maxEndScale);
        }
        if (c.worldBorder.endScale > maxEndScale) {
            c.worldBorder.endScale = maxEndScale;
            warnings.add("worldBorder.endScale: worldBorder.diameter times endScale can't pass " + ConfigLoader.MAX_BORDER_DIAMETER
                    + ", using " + maxEndScale);
        }
        if (c.deathLockout.cooldownDays < 0) {
            c.deathLockout.cooldownDays = fallback.deathLockout.cooldownDays;
            fellBack.accept("deathLockout.cooldownDays", "must be 0 or more");
        }
        if (c.chat.radius < 1) {
            c.chat.radius = fallback.chat.radius;
            fellBack.accept("chat.radius", "must be 1 or more");
        }
        if (c.chat.whisperRadius < 0) {
            c.chat.whisperRadius = fallback.chat.whisperRadius;
            fellBack.accept("chat.whisperRadius", "must be 0 or more");
        }
        if (c.graves.enderChestOnDeath == null) {
            c.graves.enderChestOnDeath = fallback.graves.enderChestOnDeath;
            fellBack.accept("graves.enderChestOnDeath", "unknown value");
        }
        if (c.graves.recheckSeconds < 0) {
            c.graves.recheckSeconds = fallback.graves.recheckSeconds;
            fellBack.accept("graves.recheckSeconds", "must be 0 or more");
        }
        if (c.graves.tellLocation == null) {
            c.graves.tellLocation = fallback.graves.tellLocation;
            fellBack.accept("graves.tellLocation", "unknown value");
        }
        if (c.graves.tellLocationDistance < 0) {
            c.graves.tellLocationDistance = fallback.graves.tellLocationDistance;
            fellBack.accept("graves.tellLocationDistance", "must be 0 or more");
        }
        if (c.graves.searchRadius < MIN_GRAVE_SEARCH_RADIUS || c.graves.searchRadius > MAX_GRAVE_SEARCH_RADIUS) {
            c.graves.searchRadius = fallback.graves.searchRadius;
            fellBack.accept("graves.searchRadius", "must be " + MIN_GRAVE_SEARCH_RADIUS + " to " + MAX_GRAVE_SEARCH_RADIUS);
        }
        if (c.graves.voidSearchRadius < MIN_VOID_SEARCH_RADIUS || c.graves.voidSearchRadius > MAX_GRAVE_SEARCH_RADIUS) {
            c.graves.voidSearchRadius = fallback.graves.voidSearchRadius;
            fellBack.accept("graves.voidSearchRadius", "must be " + MIN_VOID_SEARCH_RADIUS + " to " + MAX_GRAVE_SEARCH_RADIUS);
        }
        if (c.chat.mode == null) {
            c.chat.mode = fallback.chat.mode;
            fellBack.accept("chat.mode", "unknown value");
        }
        if (c.bodies.type == null) {
            c.bodies.type = fallback.bodies.type;
            fellBack.accept("bodies.type", "unknown value");
        }
        if (c.bodies.pose == null) {
            c.bodies.pose = fallback.bodies.pose;
            fellBack.accept("bodies.pose", "unknown value");
        }
        if (c.bodies.duration == null) {
            c.bodies.duration = fallback.bodies.duration;
            fellBack.accept("bodies.duration", "expected \"offline\" or minutes of 1 or more");
        }
        if (c.skins.mode == null) {
            c.skins.mode = fallback.skins.mode;
            fellBack.accept("skins.mode", "unknown value");
        }
        if (c.skins.lookupTimeoutSeconds < 1 || c.skins.lookupTimeoutSeconds > MAX_SKIN_LOOKUP_SECONDS) {
            c.skins.lookupTimeoutSeconds = fallback.skins.lookupTimeoutSeconds;
            fellBack.accept("skins.lookupTimeoutSeconds", "must be 1 to " + MAX_SKIN_LOOKUP_SECONDS);
        }
        if (c.skins.refreshHours < 0) {
            c.skins.refreshHours = fallback.skins.refreshHours;
            fellBack.accept("skins.refreshHours", "must be 0 or more");
        }
        if (c.serverList.shownMaxPlayers < 0) {
            c.serverList.shownMaxPlayers = fallback.serverList.shownMaxPlayers;
            fellBack.accept("serverList.shownMaxPlayers", "must be 0 or more");
        }
        c.serverList.hoverLines = capped(c.serverList.hoverLines, MAX_HOVER_LINES, MAX_HOVER_LINE_LENGTH,
                "serverList.hoverLines", warnings);
        c.hide.tabHeaderLines = capped(c.hide.tabHeaderLines, MAX_TAB_HEADER_LINES, MAX_TAB_HEADER_LINE_LENGTH,
                "hide.tabHeaderLines", warnings);
        hardcore(c.hardcore, fallback.hardcore, fellBack);
        return reset;
    }

    private static void hardcore(HardcoreConfig.HardcoreSection c, HardcoreConfig.HardcoreSection fallback,
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
        // Both go back, since either one could be the typo
        if (slow.step > slow.cap) {
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
        if (!(c.mending.durabilityPerXp >= 0)) {
            c.mending.durabilityPerXp = fallback.mending.durabilityPerXp;
            fellBack.accept("hardcore.mending.durabilityPerXp", "must be 0 or more");
        }
        anvil(c.anvil, fallback.anvil, fellBack);
        if (!(c.elytra.glideGravityMultiplier >= 0)) {
            c.elytra.glideGravityMultiplier = fallback.elytra.glideGravityMultiplier;
            fellBack.accept("hardcore.elytra.glideGravityMultiplier", "must be 0 or more");
        }
        if (!(c.elytra.rocketCutoffSpeed >= 0)) {
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
        curseAndLimp(c, fallback, fellBack);
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
        return price >= 1 && price <= MAX_ANVIL_PRICE;
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

    // The curse must always leave more hearts than the limp needs to stop, or a cursed player could limp for good
    private static void curseAndLimp(HardcoreConfig.HardcoreSection c, HardcoreConfig.HardcoreSection fallback,
                                     BiConsumer<String, String> fellBack) {
        if (!limpsForGood(c)) {
            return;
        }
        // Both go back, since either one could be the typo
        c.curse.maxLostHearts = fallback.curse.maxLostHearts;
        c.limp.recoverAtHearts = fallback.limp.recoverAtHearts;
        fellBack.accept("hardcore.curse.maxLostHearts", "must leave more than hardcore.limp.recoverAtHearts of the 10 hearts");
        fellBack.accept("hardcore.limp.recoverAtHearts", "must be less than the 10 hearts hardcore.curse.maxLostHearts leaves");
        // The limp's own pair has to hold with the recovery line it got back
        if (!(c.limp.belowHearts < c.limp.recoverAtHearts)) {
            c.limp.belowHearts = fallback.limp.belowHearts;
            fellBack.accept("hardcore.limp.belowHearts", "must be less than hardcore.limp.recoverAtHearts");
        }
        // Numbers that were fine with one of the two off can still clash once it goes on, so the switch that turned it
        // on goes back too. That is how /yolt config refuses it.
        if (limpsForGood(c)) {
            String problem = "the curse would leave no more hearts than hardcore.limp.recoverAtHearts, so a cursed player"
                    + " could limp for good";
            if (c.curse.enabled != fallback.curse.enabled) {
                c.curse.enabled = fallback.curse.enabled;
                fellBack.accept("hardcore.curse.enabled", problem);
            }
            if (c.limp.enabled != fallback.limp.enabled) {
                c.limp.enabled = fallback.limp.enabled;
                fellBack.accept("hardcore.limp.enabled", problem);
            }
        }
    }

    private static boolean limpsForGood(HardcoreConfig.HardcoreSection c) {
        return c.curse.enabled && c.limp.enabled && !(10 - c.curse.maxLostHearts > c.limp.recoverAtHearts);
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

    // Like the End cap, what fits is kept rather than the whole list falling back. A list that fits comes back as it is.
    private static List<String> capped(List<String> lines, int maxLines, int maxLength, String path, List<String> warnings) {
        List<String> capped = new ArrayList<>();
        for (String line : lines.subList(0, Math.min(lines.size(), maxLines))) {
            capped.add(cut(line, maxLength));
        }
        if (capped.equals(lines)) {
            return lines;
        }
        warnings.add(path + ": at most " + maxLines + " lines of " + maxLength + " characters each, the rest was dropped");
        return capped;
    }

    // Never between the two halves of a character outside the basic range, such as an emoji
    private static String cut(String line, int maxLength) {
        if (line.length() <= maxLength) {
            return line;
        }
        boolean split = Character.isHighSurrogate(line.charAt(maxLength - 1));
        return line.substring(0, split ? maxLength - 1 : maxLength);
    }
}
