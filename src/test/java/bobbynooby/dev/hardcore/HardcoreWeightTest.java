package bobbynooby.dev.hardcore;

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
        // An iron chestplate, then a netherite one
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
}
