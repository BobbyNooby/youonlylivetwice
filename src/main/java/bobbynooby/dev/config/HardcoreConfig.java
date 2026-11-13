package bobbynooby.dev.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** The hardcore layer's settings, under "hardcore" in the config. Every feature ships off. */
public final class HardcoreConfig {
    private HardcoreConfig() {}

    public static class HardcoreSection {
        public SlowSection slow = new SlowSection();
        public WeightSection weight = new WeightSection();
        public LegsSection legs = new LegsSection();
    }

    // Shared by every slowdown: they add up, stop at cap and change only in whole steps, so the speed doesn't flicker
    public static class SlowSection {
        public double cap = 0.5;
        public double step = 0.05;
    }

    // Weights are stack weights, the weight of a full stack, except itemsEach and the per-item keys
    public static class WeightSection {
        public boolean enabled = false;
        public double freeLoad = 16.0;
        public double fullLoad = 56.0;
        public double maxSlow = 0.5;
        // "default" in the spec, which Java can't name a field
        public double defaultWeight = 1.0;
        public double unstackableWeight = 0.25;
        public double toolWeight = 0.5;
        // An item in more than one tag takes the first in this order, so specific tags come before general ones
        public Map<String, Double> groups = numbers(
                "c:ores", 3.0, "c:raw_materials", 3.0, "c:ingots", 3.0, "c:stones", 3.0,
                "c:cobblestones", 3.0, "c:obsidians", 3.0, "minecraft:stone_bricks", 3.0,
                "minecraft:logs", 3.0,
                "c:storage_blocks/iron", 27.0, "c:storage_blocks/gold", 27.0,
                "c:storage_blocks/copper", 27.0, "c:storage_blocks/netherite", 27.0,
                "c:storage_blocks/raw_iron", 27.0, "c:storage_blocks/raw_gold", 27.0,
                "c:storage_blocks/raw_copper", 27.0, "c:storage_blocks", 9.0,
                "c:feathers", 0.25, "c:strings", 0.25);
        public Map<String, Double> items = numbers("minecraft:paper", 0.25, "minecraft:anvil", 9.0);
        public Map<String, Double> itemsEach = numbers("minecraft:elytra", 1.5, "minecraft:mace", 2.0);
        public Map<String, Double> armourFactors = numbers(
                "leather", 0.15, "copper", 0.25, "chainmail", 0.25, "gold", 0.25,
                "iron", 0.3, "turtle_scute", 0.3, "diamond", 0.35, "netherite", 0.45);
        public boolean countContainers = true;
        public double containerContents = 1.0;
        public int containerDepth = 3;
        public boolean countCursorAndGrid = true;
        public double heavyJumpExhaustion = 0.2;
    }

    // Damage is what is left after armour and protection enchantments; ticks are per heal point, and 0 means none
    public static class LegsSection {
        public boolean enabled = false;
        public double breakAtDamage = 6.0;
        public boolean heavyLandings = false;
        public double breakAtDamageFullLoad = 4.0;
        public boolean includeElytraCrash = false;
        public double slow = 0.2;
        public int healPoints = 30;
        public int healTicks = 80;
        public int healTicksSaturated = 40;
        public double exhaustionPerPoint = 3.0;
        public boolean healthFirst = true;
        public boolean regenEffectHeals = true;
        public int healTicksNoRegenRule = 1200;
        public boolean bodiesCanBreakLegs = true;
    }

    // Name, value, name, value..., kept in the order given
    private static Map<String, Double> numbers(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return map;
    }
}
