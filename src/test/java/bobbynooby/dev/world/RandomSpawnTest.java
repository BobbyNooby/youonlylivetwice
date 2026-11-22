package bobbynooby.dev.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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

    // A seeded random gives the same columns every time, which is what useRandomForTesting relies on, and over a 10-wide
    // area every column can come up, the first and the last whole block included, and none past them
    @Test
    void seededPickReachesBothEdgesAndRepeats() {
        RandomSpawn.Area area = RandomSpawn.area(0, 10, 0, 10, 5, 5, 10000);
        List<RandomSpawn.Column> first = new ArrayList<>();
        List<RandomSpawn.Column> second = new ArrayList<>();
        Random once = new Random(42);
        Random again = new Random(42);
        for (int i = 0; i < 2000; i++) {
            first.add(RandomSpawn.pickColumn(once, area));
            second.add(RandomSpawn.pickColumn(again, area));
        }
        assertEquals(first, second);
        Set<Integer> xs = new HashSet<>();
        Set<Integer> zs = new HashSet<>();
        for (RandomSpawn.Column column : first) {
            xs.add(column.x());
            zs.add(column.z());
        }
        Set<Integer> everyColumn = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertEquals(everyColumn, xs);
        assertEquals(everyColumn, zs);
    }

    // A first join's spot is checked again when the player is placed, and every column a pick gives must pass that
    // check, or a spot just picked would be picked again
    @Test
    void everyPickedColumnPassesTheAreaCheck() {
        Random random = new Random(8);
        List<RandomSpawn.Area> areas = List.of(
                RandomSpawn.area(-500, 500, -500, 500, 0, 0, 10000),
                RandomSpawn.area(50.5, 150.5, -70.25, 29.75, 100.5, -20.25, 10000),
                RandomSpawn.area(-30_000_000, 1000, -30_000_000, 30_000_000, 800, 0, 10000),
                RandomSpawn.area(9.5, 10.5, 9.5, 10.5, 10, 10, 1));
        for (RandomSpawn.Area area : areas) {
            for (int i = 0; i < PICKS; i++) {
                RandomSpawn.Column column = RandomSpawn.pickColumn(random, area);
                assertTrue(RandomSpawn.canPick(area, column.x(), column.z()), column + " was picked in " + area + " but fails the check");
            }
        }
    }

    // The blocks just past each edge fail the check: half outside a decimal edge, and beside the one block a tiny border
    // picks
    @Test
    void areaCheckRefusesTheBlocksPastEachEdge() {
        RandomSpawn.Area whole = RandomSpawn.area(0, 10, 0, 10, 5, 5, 10000);
        assertTrue(RandomSpawn.canPick(whole, 0, 9));
        assertTrue(RandomSpawn.canPick(whole, 9, 0));
        assertFalse(RandomSpawn.canPick(whole, -1, 5));
        assertFalse(RandomSpawn.canPick(whole, 10, 5));
        assertFalse(RandomSpawn.canPick(whole, 5, -1));
        assertFalse(RandomSpawn.canPick(whole, 5, 10));

        RandomSpawn.Area decimal = RandomSpawn.area(50.5, 150.5, -70.25, 29.75, 100.5, -20.25, 10000);
        assertTrue(RandomSpawn.canPick(decimal, 51, -70));
        assertTrue(RandomSpawn.canPick(decimal, 149, 28));
        assertFalse(RandomSpawn.canPick(decimal, 50, 0));
        assertFalse(RandomSpawn.canPick(decimal, 150, 0));
        assertFalse(RandomSpawn.canPick(decimal, 100, -71));
        assertFalse(RandomSpawn.canPick(decimal, 100, 29));

        RandomSpawn.Area tiny = RandomSpawn.area(9.5, 10.5, 9.5, 10.5, 10, 10, 1);
        assertTrue(RandomSpawn.canPick(tiny, 10, 10));
        assertFalse(RandomSpawn.canPick(tiny, 9, 10));
        assertFalse(RandomSpawn.canPick(tiny, 11, 10));
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
