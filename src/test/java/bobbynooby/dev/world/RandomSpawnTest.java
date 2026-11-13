package bobbynooby.dev.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RandomSpawnTest {
    private static final int PICKS = 10_000;

    // A border whose real edges are its size around its centre, as they are without max-world-size
    private static RandomSpawn.Column pick(Random random, double centerX, double centerZ, double borderSize, int diameter) {
        double half = borderSize / 2;
        return RandomSpawn.pickColumn(random, RandomSpawn.area(centerX - half, centerX + half, centerZ - half, centerZ + half,
                centerX, centerZ, diameter));
    }

    @Test
    void staysInsideBorderAroundNonZeroCentre() {
        Random random = new Random(1);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = pick(random, 5000, -3000, 1000, 10000);
            // The whole block has to be inside, so x + 1 is checked against the far edge
            assertTrue(column.x() >= 4500 && column.x() + 1 <= 5500, "x outside the border: " + column.x());
            assertTrue(column.z() >= -3500 && column.z() + 1 <= -2500, "z outside the border: " + column.z());
        }
    }

    // Vanilla's border is 60 million wide until YOLT applies its own
    @Test
    void usesConfigDiameterWhenSmallerThanBorder() {
        Random random = new Random(2);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = pick(random, 0, 0, 59_999_968, 10000);
            assertTrue(Math.abs(column.x()) <= 5000, "x outside the config diameter: " + column.x());
            assertTrue(Math.abs(column.z()) <= 5000, "z outside the config diameter: " + column.z());
        }
    }

    @Test
    void usesBorderWhenSmallerThanConfigDiameter() {
        Random random = new Random(3);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = pick(random, 0, 0, 200, 10000);
            assertTrue(Math.abs(column.x()) <= 100, "x outside the border: " + column.x());
            assertTrue(Math.abs(column.z()) <= 100, "z outside the border: " + column.z());
        }
    }

    // A centre of 100.5 puts the edges at 50.5 and 150.5, so blocks 50 and 150 are half outside
    @Test
    void decimalCentreKeepsWholeBlocksInside() {
        Random random = new Random(5);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = pick(random, 100.5, -20.25, 100, 10000);
            assertTrue(column.x() >= 50.5 && column.x() + 1 <= 150.5, "x not wholly inside the border: " + column.x());
            assertTrue(column.z() >= -70.25 && column.z() + 1 <= 29.75, "z not wholly inside the border: " + column.z());
        }
    }

    @Test
    void tinyBorderStillPicks() {
        RandomSpawn.Column column = assertDoesNotThrow(() -> pick(new Random(4), 10, 10, 1, 1));
        assertTrue(Math.abs(column.x() - 10) <= 1 && Math.abs(column.z() - 10) <= 1, "pick far from a 1-block border");
    }

    // max-world-size 1000 pulls vanilla's 60 million wide border in to ±1000, though its size stays the same
    @Test
    void staysInsideEdgesThatMaxWorldSizeCut() {
        Random random = new Random(6);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = RandomSpawn.pickColumn(random, RandomSpawn.area(-1000, 1000, -1000, 1000, 0, 0, 10000));
            assertTrue(column.x() >= -1000 && column.x() + 1 <= 1000, "x outside the world's edge: " + column.x());
            assertTrue(column.z() >= -1000 && column.z() + 1 <= 1000, "z outside the world's edge: " + column.z());
        }
    }

    // An edge cut on one side only, with the centre off it: the other side still ends at the diameter
    @Test
    void oneCutEdgeKeepsTheOtherSidesDiameter() {
        Random random = new Random(7);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = RandomSpawn.pickColumn(random,
                    RandomSpawn.area(-30_000_000, 1000, -30_000_000, 30_000_000, 800, 0, 10000));
            assertTrue(column.x() >= 800 - 5000 && column.x() + 1 <= 1000, "x outside the cut square: " + column.x());
            assertTrue(Math.abs(column.z()) <= 5000, "z outside the config diameter: " + column.z());
        }
    }
}
