package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreWeightTest {
    private static final double CLOSE = 1e-9;

    @Test
    void weight1OneItemIsItsShareOfTheStack() {
        assertEquals(0.046875, WeightTable.each(3.0, 64), CLOSE);
        assertEquals(0.00390625, WeightTable.each(0.25, 64), CLOSE);
        assertEquals(0.0625, WeightTable.each(1.0, 16), CLOSE);
    }

    @Test
    void weight2ArmourIsPointsTimesFactor() {
        // A chestplate of 6 points, then one of 8, at factors of their own
        assertEquals(1.8, WeightTable.armour(6, 0.30), CLOSE);
        assertEquals(3.6, WeightTable.armour(8, 0.45), CLOSE);
        double leatherSet = WeightTable.armour(1, 0.15) + WeightTable.armour(2, 0.15) + WeightTable.armour(3, 0.15)
                + WeightTable.armour(1, 0.15);
        assertEquals(1.05, leatherSet, CLOSE);
        assertEquals(9.0, WeightTable.armour(20, 0.45), CLOSE);
    }

    @Test
    void weight3LoadToSlowdown() {
        assertEquals(0.0, WeightTable.slow(16, 16, 56, 0.5), CLOSE);
        assertEquals(0.25, WeightTable.slow(36, 16, 56, 0.5), CLOSE);
        assertEquals(0.19375, WeightTable.slow(31.5, 16, 56, 0.5), CLOSE);
        assertEquals(0.5, WeightTable.slow(56, 16, 56, 0.5), CLOSE);
        assertEquals(0.5, WeightTable.slow(81, 16, 56, 0.5), CLOSE);
        assertEquals(0.0, WeightTable.slow(0, 16, 56, 0.5), CLOSE);
    }

    @Test
    void weight3LoadShare() {
        assertEquals(0.0, WeightTable.loadShare(16, 16, 56), CLOSE);
        assertEquals(0.5, WeightTable.loadShare(36, 16, 56), CLOSE);
        assertEquals(1.0, WeightTable.loadShare(56, 16, 56), CLOSE);
        assertEquals(1.0, WeightTable.loadShare(200, 16, 56), CLOSE);
    }

    // The balance sheet's headline loadouts, on the default numbers. Item facts are vanilla's: a full stack is 64, iron
    // armour has 2/6/5/2 points and diamond 3/8/6/3, and cobblestone, stone, logs, ingots and string sit in their tags.
    @Test
    void weight8BalanceSheetLoadouts() {
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        double cobblestone = cfg.groups.get("c:cobblestones");
        double log = cfg.groups.get("minecraft:logs");
        double ingot = cfg.groups.get("c:ingots");
        double string = cfg.groups.get("c:strings");
        double stone = cfg.groups.get("c:stones");
        double plain = cfg.defaultWeight;
        double tools = 4 * cfg.toolWeight;

        // Adventurer, half an inventory: iron armour, a shield, four iron tools, a bow, a water bucket, a bed, then
        // arrows, torches, 32 cooked beef, a crafting table, 2 stacks of cobblestone, dirt, logs and 2 of planks
        double adventurer = armour(cfg, "iron", 2, 6, 5, 2) + tools + 4 * cfg.unstackableWeight
                + stacks(plain, 64 + 64 + 32 + 1) + 2 * cobblestone + plain + log + 2 * plain;
        assertEquals(17.415625, adventurer, CLOSE);
        assertSlowdown(cfg, adventurer, 0.00552978515625, 0.0);

        // A full inventory of ordinary things: diamond armour, a shield, four diamond tools, a bow, a water bucket,
        // 4 stacks of cobblestone, 2 of logs, 32 iron ingots, 32 string, and plain things: 16 full stacks, 3 half
        // stacks, 48 rotten flesh, a crafting table and 16 chests
        double ordinary = armour(cfg, "diamond", 3, 8, 6, 3) + tools + 3 * cfg.unstackableWeight
                + 4 * cobblestone + 2 * log + stacks(ingot, 32) + stacks(string, 32)
                + stacks(plain, 64 * 16 + 32 * 3 + 48 + 1 + 16);
        assertEquals(42.440625, ordinary, CLOSE);
        assertSlowdown(cfg, ordinary, 0.10328369140625, 0.1);

        // Bulk hauler: diamond armour, a pickaxe, and 4, 9 or 27 shulker boxes of 27 stacks of stone each
        double shulker = cfg.unstackableWeight + cfg.containerContents * 27 * stone;
        double hauler = armour(cfg, "diamond", 3, 8, 6, 3) + cfg.toolWeight;
        assertEquals(327.65, hauler + 4 * shulker, CLOSE);
        for (int boxes : new int[]{4, 9, 27}) {
            assertSlowdown(cfg, hauler + boxes * shulker, 0.5, 0.5);
        }
    }

    private static double armour(HardcoreConfig.WeightSection cfg, String material, int... points) {
        double weight = 0.0;
        for (int piece : points) {
            weight += WeightTable.armour(piece, cfg.armourFactors.get(material));
        }
        return weight;
    }

    // count items of something whose full stack of 64 weighs stackWeight
    private static double stacks(double stackWeight, int count) {
        return WeightTable.each(stackWeight, 64) * count;
    }

    private static void assertSlowdown(HardcoreConfig.WeightSection cfg, double load, double raw, double applied) {
        double slow = WeightTable.slow(load, cfg.freeLoad, cfg.fullLoad, cfg.maxSlow);
        assertEquals(raw, slow, CLOSE);
        HardcoreConfig.SlowSection steps = new HardcoreConfig.SlowSection();
        assertEquals(applied, SlowBudget.total(steps.cap, steps.step, slow), CLOSE);
    }
}
