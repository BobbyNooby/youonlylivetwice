package bobbynooby.dev.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        public AnvilSection anvil = new AnvilSection();
        public ElytraSection elytra = new ElytraSection();
        public TougherMobsSection tougherMobs = new TougherMobsSection();
        public RelentlessMobsSection relentlessMobs = new RelentlessMobsSection();
        public BloodMoonSection bloodMoon = new BloodMoonSection();
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

    // One flat price per anvil use, set by its biggest job, which never grows with repeated use; breakChance is the chance
    // per use that the anvil wears down a step, vanilla's 12%
    public static class AnvilSection {
        public boolean enabled = false;
        public int enchantCost = 6;
        public int repairCost = 5;
        public int renameCost = 1;
        public double breakChance = 0.12;
    }

    // Lighter gravity while gliding means a slower, floatier glide; rockets stop pushing past the cut-off in m/s, 0 for never
    public static class ElytraSection {
        public boolean enabled = false;
        public double glideGravityMultiplier = 0.6;
        public double rocketCutoffSpeed = 20.0;
    }

    // Multipliers on hostile mobs, set as each mob loads; overrides name only the values that differ for that mob
    public static class TougherMobsSection {
        public boolean enabled = false;
        @Applies(Applies.When.MOBS_LOAD) public double health = 1.5;
        // Read as each hit lands, so a change applies at once
        public double damage = 1.25;
        @Applies(Applies.When.MOBS_LOAD) public double speed = 1.1;
        public DamageAppliesTo damageAppliesTo = DamageAppliesTo.PLAYERS;
        @Applies(Applies.When.MOBS_LOAD) public boolean includeSpawners = true;
        @Applies(Applies.When.MOBS_LOAD) public boolean includeTrialSpawners = false;
        @Applies(Applies.When.MOBS_LOAD) public boolean includeCommandsAndEggs = false;
        public List<String> exclude = new ArrayList<>(List.of(
                "minecraft:wither", "minecraft:ender_dragon", "minecraft:elder_guardian", "minecraft:warden"));
        public Map<String, Map<String, Double>> overrides = overrides();
    }

    // Players covers logout bodies too, so a body takes what its owner would
    public enum DamageAppliesTo {
        @SerializedName("players") PLAYERS,
        @SerializedName("all") ALL
    }

    // Ghasts are an easy kill otherwise, phantoms are hard enough with no night skip, and a creeper's blast hits hard already
    private static Map<String, Map<String, Double>> overrides() {
        Map<String, Map<String, Double>> overrides = new LinkedHashMap<>();
        overrides.put("minecraft:ghast", numbers("health", 2.0));
        overrides.put("minecraft:phantom", numbers("health", 1.0, "damage", 1.0));
        overrides.put("minecraft:creeper", numbers("damage", 1.0));
        return overrides;
    }

    // The warden senses through walls, so a longer reach would make it find everyone; tougherMobs.exclude is left alone too.
    // A broken door drops as one item, as in vanilla, unless dropBrokenDoor is off, when it is gone with no drop.
    public static class RelentlessMobsSection {
        public boolean enabled = false;
        @Applies(Applies.When.MOBS_LOAD) public double followRange = 1.5;
        public boolean doorsAnyDifficulty = true;
        @Applies(Applies.When.MOBS_LOAD) public double doorBreakerChance = 0.25;
        public boolean dropBrokenDoor = true;
        public List<String> exclude = new ArrayList<>(List.of("minecraft:warden"));
    }

    // Ticks are times of day, 0 to 23999; a night rolls from the world seed and its day number. The multipliers go on top
    // of tougher mobs' own, and the chances are the least a mob spawned in it gets.
    public static class BloodMoonSection {
        public boolean enabled = false;
        public double chance = 0.1;
        public int minNightsBetween = 5;
        public boolean fullMoonsOnly = false;
        public int graceDays = 8;
        public int startTick = 12542;
        public int endTick = 23460;
        public int warnTick = 9000;
        @Applies(Applies.When.MOBS_LOAD) public double health = 1.25;
        public double damage = 1.0;
        @Applies(Applies.When.MOBS_LOAD) public double speed = 1.1;
        public double spawnCap = 2.0;
        public boolean capBoostNearPlayerBodies = false;
        public boolean canSleepThrough = false;
        public double armourChance = 0.35;
        public double enchantChance = 0.25;
        public double xp = 2.0;
        public int extraLootRolls = 1;
        public double chargedCreeperChance = 0.0;
        public boolean bossBar = true;
        public boolean darkenSky = true;
        public boolean fog = false;
        public WarningSection warning = new WarningSection();
    }

    // What the warning before a blood moon sends each player in the overworld
    public static class WarningSection {
        public boolean title = true;
        public boolean chat = true;
        public boolean sound = true;
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
