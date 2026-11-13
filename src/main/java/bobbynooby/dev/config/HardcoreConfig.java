package bobbynooby.dev.config;

import com.google.gson.annotations.SerializedName;

import java.util.LinkedHashMap;
import java.util.Map;

/** The hardcore layer's settings, under "hardcore" in the config. Every feature ships off. */
public final class HardcoreConfig {
    private HardcoreConfig() {}

    public static class HardcoreSection {
        public SlowSection slow = new SlowSection();
        public WeightSection weight = new WeightSection();
        public LegsSection legs = new LegsSection();
        public LimpSection limp = new LimpSection();
        public BedRestSection bedRest = new BedRestSection();
        public CurseSection curse = new CurseSection();
        public NutrientsSection nutrients = new NutrientsSection();
        public HungerTaxSection hungerTax = new HungerTaxSection();
        public MendingSection mending = new MendingSection();
        public ElytraSection elytra = new ElytraSection();
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

    // Hearts, not health points; the gap between the two keeps a slowly healing player from flickering in and out
    public static class LimpSection {
        public boolean enabled = false;
        public double belowHearts = 2.0;
        public double recoverAtHearts = 3.0;
        public double slow = 0.2;
        public boolean countAbsorption = false;
    }

    // Ticks per health point healed in bed, 0 for none; legHealSpeed multiplies the broken legs' own pace
    public static class BedRestSection {
        public boolean enabled = false;
        public boolean allowDaytime = true;
        public int healEveryTicks = 50;
        public double exhaustionPerHeal = 0.0;
        public double legHealSpeed = 2.0;
    }

    // Hearts, two health points each; a heal comes after healEveryDays of the player's own online play, 24,000 ticks a day
    public static class CurseSection {
        public boolean enabled = false;
        @Applies(Applies.When.NEW_DEATHS) public int heartsPerDeath = 1;
        @Applies(Applies.When.NEW_DEATHS) public int heartsPerTotem = 1;
        public int maxLostHearts = 2;
        public int healHearts = 1;
        public double healEveryDays = 5.0;
        public boolean countAfk = true;
        public int reviveRefundHearts = 0;
        public boolean unkillRefunds = true;
    }

    // Levels and shares are 0 to 1; gainPerPoint is spread over a food's groups by their shares
    public static class NutrientsSection {
        public boolean enabled = false;
        public double gainPerPoint = 0.03;
        public double decayPerPoint = 0.01;
        public double startLevel = 0.6;
        public double deficientBelow = 0.25;
        public PenaltiesSection penalties = new PenaltiesSection();
        // An item id, or # and an item tag; an id beats a tag
        public Map<String, Map<String, Double>> foods = foods();
        public UnknownFood unknownFood = UnknownFood.NONE;
    }

    public static class PenaltiesSection {
        public ProteinSection protein = new ProteinSection();
        public SugarSection sugar = new SugarSection();
        public VitaminsSection vitamins = new VitaminsSection();
    }

    // How fast health and broken legs heal while protein is low
    public static class ProteinSection {
        public double healingMultiplier = 0.5;
    }

    public static class SugarSection {
        public double sprintSlow = 0.10;
    }

    public static class VitaminsSection {
        public double miningSlow = 0.15;
    }

    // What a food the table doesn't list gives: nothing, all to one group, or a third to each
    public enum UnknownFood {
        @SerializedName("none") NONE,
        @SerializedName("protein") PROTEIN,
        @SerializedName("vitamins") VITAMINS,
        @SerializedName("sugar") SUGAR,
        @SerializedName("even") EVEN
    }

    // Every vanilla food. Cake is eaten from the block, a slice at a time.
    private static Map<String, Map<String, Double>> foods() {
        Map<String, Map<String, Double>> foods = new LinkedHashMap<>();
        for (String cooked : new String[]{"cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
                "cooked_rabbit", "cooked_cod", "cooked_salmon"}) {
            foods.put("minecraft:" + cooked, numbers("protein", 1.0));
        }
        for (String raw : new String[]{"beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon",
                "tropical_fish", "pufferfish"}) {
            foods.put("minecraft:" + raw, numbers("protein", 0.75));
        }
        foods.put("minecraft:rotten_flesh", numbers("protein", 0.5));
        foods.put("minecraft:spider_eye", numbers("protein", 0.5));
        for (String plant : new String[]{"apple", "carrot", "golden_carrot", "potato", "baked_potato", "beetroot",
                "beetroot_soup", "mushroom_stew", "suspicious_stew", "melon_slice", "sweet_berries", "glow_berries",
                "chorus_fruit", "dried_kelp"}) {
            foods.put("minecraft:" + plant, numbers("vitamins", 1.0));
        }
        for (String sweet : new String[]{"bread", "cookie", "honey_bottle", "cake"}) {
            foods.put("minecraft:" + sweet, numbers("sugar", 1.0));
        }
        foods.put("minecraft:rabbit_stew", numbers("protein", 0.5, "vitamins", 0.5));
        foods.put("minecraft:pumpkin_pie", numbers("sugar", 0.5, "vitamins", 0.5));
        foods.put("minecraft:golden_apple", numbers("vitamins", 0.6, "protein", 0.2, "sugar", 0.2));
        foods.put("minecraft:enchanted_golden_apple", numbers("vitamins", 0.6, "protein", 0.2, "sugar", 0.2));
        foods.put("minecraft:poisonous_potato", numbers());
        return foods;
    }

    // Scales the hunger that actions cost; 1.0 is vanilla, so it has no switch of its own
    public static class HungerTaxSection {
        public double multiplier = 1.0;
    }

    // Durability that one XP point repairs; vanilla's is 2, and 0 sends every point to the bar
    public static class MendingSection {
        public boolean enabled = false;
        public double durabilityPerXp = 0.4;
    }

    // Lighter gravity while gliding means a slower, floatier glide; rockets stop pushing past the cut-off in m/s, 0 for never
    public static class ElytraSection {
        public boolean enabled = false;
        public double glideGravityMultiplier = 0.6;
        public double rocketCutoffSpeed = 20.0;
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
