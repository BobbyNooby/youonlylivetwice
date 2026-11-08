package bobbynooby.dev.features;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RandomSpawnTest {
    private static final int PICKS = 10_000;

    @Test
    void staysInsideBorderAroundNonZeroCentre() {
        Random random = new Random(1);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = RandomSpawn.pickColumn(random, 5000, -3000, 1000, 10000);
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
            RandomSpawn.Column column = RandomSpawn.pickColumn(random, 0, 0, 59_999_968, 10000);
            assertTrue(Math.abs(column.x()) <= 5000, "x outside the config diameter: " + column.x());
            assertTrue(Math.abs(column.z()) <= 5000, "z outside the config diameter: " + column.z());
        }
    }

    @Test
    void usesBorderWhenSmallerThanConfigDiameter() {
        Random random = new Random(3);
        for (int i = 0; i < PICKS; i++) {
            RandomSpawn.Column column = RandomSpawn.pickColumn(random, 0, 0, 200, 10000);
            assertTrue(Math.abs(column.x()) <= 100, "x outside the border: " + column.x());
            assertTrue(Math.abs(column.z()) <= 100, "z outside the border: " + column.z());
        }
    }

    @Test
    void tinyBorderStillPicks() {
        RandomSpawn.Column column = assertDoesNotThrow(() -> RandomSpawn.pickColumn(new Random(4), 10, 10, 1, 1));
        assertTrue(Math.abs(column.x() - 10) <= 1 && Math.abs(column.z() - 10) <= 1, "pick far from a 1-block border");
    }
}
