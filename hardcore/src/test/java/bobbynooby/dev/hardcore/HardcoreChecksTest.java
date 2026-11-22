package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardcoreChecksTest {
    // One value a rule refuses, and the keys that rule names for it: a pair rule names both of its keys, and the blood
    // moon's order all three of its ticks. Every other value is left at its default, so no other rule speaks up.
    private record Bad(Consumer<HardcoreConfig.HardcoreSection> set, List<String> keys) {}

    private static Bad bad(Consumer<HardcoreConfig.HardcoreSection> set, String... keys) {
        return new Bad(set, List.of(keys));
    }

    private static final List<Bad> ONE_PER_RULE = List.of(
            bad(c -> c.slow.cap = 1.5, "hardcore.slow.cap"),
            bad(c -> c.slow.step = 0, "hardcore.slow.step"),
            bad(c -> {
                c.slow.cap = 0.1;
                c.slow.step = 0.2;
            }, "hardcore.slow.step", "hardcore.slow.cap"),
            bad(c -> c.weight.freeLoad = -1, "hardcore.weight.freeLoad"),
            bad(c -> c.weight.fullLoad = -1, "hardcore.weight.fullLoad"),
            bad(c -> c.weight.freeLoad = 200, "hardcore.weight.freeLoad", "hardcore.weight.fullLoad"),
            bad(c -> c.weight.maxSlow = 2, "hardcore.weight.maxSlow"),
            bad(c -> c.weight.defaultWeight = -1, "hardcore.weight.defaultWeight"),
            bad(c -> c.weight.unstackableWeight = -1, "hardcore.weight.unstackableWeight"),
            bad(c -> c.weight.toolWeight = -1, "hardcore.weight.toolWeight"),
            bad(c -> c.weight.groups.put("c:ores", -1.0), "hardcore.weight.groups"),
            bad(c -> c.weight.items.put("minecraft:paper", -1.0), "hardcore.weight.items"),
            bad(c -> c.weight.itemsEach.put("minecraft:mace", -1.0), "hardcore.weight.itemsEach"),
            bad(c -> c.weight.armourFactors.put("iron", -1.0), "hardcore.weight.armourFactors"),
            bad(c -> c.weight.containerContents = 2, "hardcore.weight.containerContents"),
            bad(c -> c.weight.containerDepth = HardcoreChecks.MAX_CONTAINER_DEPTH + 1,
                    "hardcore.weight.containerDepth"),
            bad(c -> c.weight.heavyJumpExhaustion = -1, "hardcore.weight.heavyJumpExhaustion"),
            bad(c -> c.legs.breakAtDamage = -1, "hardcore.legs.breakAtDamage"),
            bad(c -> c.legs.breakAtDamageFullLoad = -1, "hardcore.legs.breakAtDamageFullLoad"),
            bad(c -> c.legs.breakAtDamageFullLoad = 8,
                    "hardcore.legs.breakAtDamageFullLoad", "hardcore.legs.breakAtDamage"),
            bad(c -> c.legs.slow = 2, "hardcore.legs.slow"),
            bad(c -> c.legs.healPoints = -1, "hardcore.legs.healPoints"),
            bad(c -> c.legs.healTicks = -1, "hardcore.legs.healTicks"),
            bad(c -> c.legs.healTicksSaturated = -1, "hardcore.legs.healTicksSaturated"),
            bad(c -> c.legs.healTicksNoRegenRule = -1, "hardcore.legs.healTicksNoRegenRule"),
            bad(c -> c.legs.exhaustionPerPoint = -1, "hardcore.legs.exhaustionPerPoint"),
            bad(c -> c.limp.belowHearts = 0, "hardcore.limp.belowHearts"),
            bad(c -> c.limp.recoverAtHearts = 0, "hardcore.limp.recoverAtHearts"),
            bad(c -> c.limp.belowHearts = 4, "hardcore.limp.belowHearts", "hardcore.limp.recoverAtHearts"),
            bad(c -> c.limp.slow = 2, "hardcore.limp.slow"),
            bad(c -> c.bedRest.healEveryTicks = -1, "hardcore.bedRest.healEveryTicks"),
            bad(c -> c.bedRest.exhaustionPerHeal = -1, "hardcore.bedRest.exhaustionPerHeal"),
            bad(c -> c.bedRest.legHealSpeed = -1, "hardcore.bedRest.legHealSpeed"),
            bad(c -> c.curse.heartsPerDeath = -1, "hardcore.curse.heartsPerDeath"),
            bad(c -> c.curse.heartsPerTotem = -1, "hardcore.curse.heartsPerTotem"),
            bad(c -> c.curse.maxLostHearts = -1, "hardcore.curse.maxLostHearts"),
            bad(c -> c.curse.healHearts = -1, "hardcore.curse.healHearts"),
            bad(c -> c.curse.healEveryDays = 0, "hardcore.curse.healEveryDays"),
            bad(c -> c.curse.afkMinutes = 0, "hardcore.curse.afkMinutes"),
            bad(c -> c.curse.reviveRefundHearts = -1, "hardcore.curse.reviveRefundHearts"),
            bad(c -> c.nutrients.gainPerPoint = -1, "hardcore.nutrients.gainPerPoint"),
            bad(c -> c.nutrients.decayPerPoint = -1, "hardcore.nutrients.decayPerPoint"),
            bad(c -> c.nutrients.startLevel = 2, "hardcore.nutrients.startLevel"),
            bad(c -> c.nutrients.deficientBelow = 2, "hardcore.nutrients.deficientBelow"),
            bad(c -> c.nutrients.penalties.protein.healingMultiplier = -1,
                    "hardcore.nutrients.penalties.protein.healingMultiplier"),
            bad(c -> c.nutrients.penalties.sugar.sprintSlow = 2, "hardcore.nutrients.penalties.sugar.sprintSlow"),
            bad(c -> c.nutrients.penalties.vitamins.miningSlow = 2, "hardcore.nutrients.penalties.vitamins.miningSlow"),
            bad(c -> c.nutrients.foods.put("minecraft:apple", Map.of("fat", 0.5)), "hardcore.nutrients.foods"),
            bad(c -> c.nutrients.unknownFood = null, "hardcore.nutrients.unknownFood"),
            bad(c -> c.hungerTax.multiplier = -1, "hardcore.hungerTax.multiplier"),
            bad(c -> c.mending.durabilityPerXp = -1, "hardcore.mending.durabilityPerXp"),
            bad(c -> c.anvil.enchantCost = 0, "hardcore.anvil.enchantCost"),
            bad(c -> c.anvil.repairCost = HardcoreChecks.MAX_ANVIL_PRICE + 1, "hardcore.anvil.repairCost"),
            bad(c -> c.anvil.renameCost = 0, "hardcore.anvil.renameCost"),
            bad(c -> c.anvil.breakChance = 2, "hardcore.anvil.breakChance"),
            bad(c -> c.elytra.glideGravityMultiplier = -1, "hardcore.elytra.glideGravityMultiplier"),
            bad(c -> c.elytra.rocketCutoffSpeed = -1, "hardcore.elytra.rocketCutoffSpeed"),
            bad(c -> c.tougherMobs.health = 0, "hardcore.tougherMobs.health"),
            bad(c -> c.tougherMobs.damage = -1, "hardcore.tougherMobs.damage"),
            bad(c -> c.tougherMobs.speed = -1, "hardcore.tougherMobs.speed"),
            bad(c -> c.tougherMobs.damageAppliesTo = null, "hardcore.tougherMobs.damageAppliesTo"),
            bad(c -> c.tougherMobs.overrides.put("minecraft:zombie", Map.of("health", 0.0)),
                    "hardcore.tougherMobs.overrides"),
            bad(c -> c.relentlessMobs.followRange = 0, "hardcore.relentlessMobs.followRange"),
            bad(c -> c.relentlessMobs.doorBreakerChance = 2, "hardcore.relentlessMobs.doorBreakerChance"),
            bad(c -> c.bloodMoon.chance = 2, "hardcore.bloodMoon.chance"),
            bad(c -> c.bloodMoon.minNightsBetween = -1, "hardcore.bloodMoon.minNightsBetween"),
            bad(c -> c.bloodMoon.graceDays = -1, "hardcore.bloodMoon.graceDays"),
            bad(c -> c.bloodMoon.endTick = HardcoreChecks.LAST_TICK_OF_DAY + 1,
                    "hardcore.bloodMoon.warnTick", "hardcore.bloodMoon.startTick", "hardcore.bloodMoon.endTick"),
            bad(c -> c.bloodMoon.health = 0, "hardcore.bloodMoon.health"),
            bad(c -> c.bloodMoon.damage = -1, "hardcore.bloodMoon.damage"),
            bad(c -> c.bloodMoon.speed = -1, "hardcore.bloodMoon.speed"),
            bad(c -> c.bloodMoon.spawnCap = -1, "hardcore.bloodMoon.spawnCap"),
            bad(c -> c.bloodMoon.armourChance = 2, "hardcore.bloodMoon.armourChance"),
            bad(c -> c.bloodMoon.enchantChance = 2, "hardcore.bloodMoon.enchantChance"),
            bad(c -> c.bloodMoon.xp = -1, "hardcore.bloodMoon.xp"),
            bad(c -> c.bloodMoon.extraLootRolls = -1, "hardcore.bloodMoon.extraLootRolls"),
            bad(c -> c.bloodMoon.chargedCreeperChance = 2, "hardcore.bloodMoon.chargedCreeperChance"),
            bad(c -> c.bloodMoon.barColour = null, "hardcore.bloodMoon.barColour"),
            bad(c -> c.cold.chillPerSecond = -1, "hardcore.cold.chillPerSecond"),
            bad(c -> c.cold.warmPerSecond = -1, "hardcore.cold.warmPerSecond"),
            bad(c -> c.cold.shelteredBelowSkyLight = HardcoreChecks.MAX_SKY_LIGHT + 1,
                    "hardcore.cold.shelteredBelowSkyLight"),
            bad(c -> c.cold.heatRadius = HardcoreChecks.MAX_HEAT_RADIUS + 1, "hardcore.cold.heatRadius"),
            bad(c -> c.cold.leatherPerPiece = 2, "hardcore.cold.leatherPerPiece"),
            bad(c -> c.cold.wetMultiplier = -1, "hardcore.cold.wetMultiplier"),
            bad(c -> c.cold.waterMultiplier = -1, "hardcore.cold.waterMultiplier"),
            bad(c -> c.cold.maxSlow = 2, "hardcore.cold.maxSlow"),
            bad(c -> c.cold.damageEverySeconds = 0, "hardcore.cold.damageEverySeconds"),
            bad(c -> c.cold.shiverExhaustionPerSecond = -1, "hardcore.cold.shiverExhaustionPerSecond"));

    // The same keys as YOLT's config, so a warning reads the same whichever file the block is in
    @Test
    void everyRuleNamesItsKeyUnderHardcore() {
        for (Bad bad : ONE_PER_RULE) {
            HardcoreConfig.HardcoreSection section = new HardcoreConfig.HardcoreSection();
            bad.set().accept(section);
            List<String> named = new ArrayList<>();
            HardcoreChecks.check(section, new HardcoreConfig.HardcoreSection(), (path, problem) -> named.add(path));
            for (String path : named) {
                assertTrue(path.startsWith("hardcore."), path);
            }
            assertEquals(bad.keys(), named, bad.keys().getFirst());
        }
    }
}
