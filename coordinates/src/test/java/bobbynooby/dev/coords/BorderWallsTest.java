package bobbynooby.dev.coords;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class BorderWallsTest {
    // The client's world edge for a border, WorldBorder's default absolute max
    private static final int A = 29_999_984;
    private static final double NEAR = BorderWalls.near(10);
    private static final Offset O = new Offset(-4_096, 2_048);

    private static BorderWalls.Square around5000(double playerX, double playerZ) {
        return BorderWalls.forClient(-5_000, 5_000, -5_000, 5_000, playerX, playerZ, NEAR, A, O);
    }

    @Test
    void noWallNearGivesTheWholeWorld() {
        assertEquals(288.0, NEAR);
        assertEquals(new BorderWalls.Square(0, 0, 59_999_968, false), around5000(0, 0));
    }

    @Test
    void nearEastWallIsTheRealWallShifted() {
        BorderWalls.Square square = around5000(4_800, 0);
        assertEquals(904.0, square.maxX(A));
        assertEquals(-29_999_984.0, square.minX(A));
        assertEquals(-29_999_984.0, square.minZ(A));
        assertEquals(29_999_984.0, square.maxZ(A));
        assertFalse(square.plain());
    }

    // A max-world-size well inside the world's edge: the far walls go out past that edge instead, so the client clamps
    // them there
    @Test
    void smallWorldSendsTheFarWallsPastItsOwnEdge() {
        int small = 10_000;
        BorderWalls.Square square = BorderWalls.forClient(-5_000, 5_000, -5_000, 5_000, 4_800, 0, NEAR, small, O);
        assertEquals(904.0, square.maxX(small));
        assertEquals(-10_000.0, square.minX(small));
        assertEquals(-10_000.0, square.minZ(small));
        assertEquals(10_000.0, square.maxZ(small));
    }

    // Outside the border a wall counts as near only within reach too, so a far one gives nothing away
    @Test
    void wallFarOutsideStaysHidden() {
        assertEquals(new BorderWalls.Square(0, 0, 59_999_968, false), around5000(6_000, 0));
        assertEquals(904.0, around5000(5_100, 0).maxX(A));
    }

    @Test
    void cornerShowsBothNearWalls() {
        BorderWalls.Square square = around5000(-4_900, 4_900);
        assertEquals(-9_096.0, square.minX(A));
        assertEquals(7_048.0, square.maxZ(A));
        assertEquals(29_999_984.0, square.maxX(A));
        assertEquals(-29_999_984.0, square.minZ(A));
    }

    @Test
    void oppositeWallsNearGiveThePlainBorder() {
        assertEquals(new BorderWalls.Square(-4_096, 2_048, 200, true),
                BorderWalls.forClient(-100, 100, -100, 100, 0, 0, NEAR, A, O));
    }

    // Digging, placing and collision on the client match the server's for every block the player could reach or see
    @Test
    void everyBlockNearThePlayerKeepsItsSide() {
        Random random = new Random(3);
        int reach = (int) NEAR - 16;
        for (int i = 0; i < 2_000; i++) {
            int centerX = random.nextInt(-1_000_000, 1_000_001);
            int centerZ = random.nextInt(-1_000_000, 1_000_001);
            // Half the borders small, so both walls of an axis are often near
            int size = 1 + random.nextInt(random.nextBoolean() ? 2_000 : 2_000_000);
            double minX = centerX - size / 2.0;
            double maxX = centerX + size / 2.0;
            double minZ = centerZ - size / 2.0;
            double maxZ = centerZ + size / 2.0;
            double playerX = minX + playerStep(random, size);
            double playerZ = minZ + playerStep(random, size);
            Offset offset = Offset.random(random);
            BorderWalls.Square square = BorderWalls.forClient(minX, maxX, minZ, maxZ, playerX, playerZ, NEAR, A, offset);
            for (int dx = -reach; dx <= reach; dx += 8) {
                for (int dz = -reach; dz <= reach; dz += 8) {
                    double x = playerX + dx;
                    double z = playerZ + dz;
                    boolean real = minX <= x && x < maxX && minZ <= z && z < maxZ;
                    double clientX = x + offset.x();
                    double clientZ = z + offset.z();
                    boolean client = square.minX(A) <= clientX && clientX < square.maxX(A)
                            && square.minZ(A) <= clientZ && clientZ < square.maxZ(A);
                    if (real != client) {
                        fail((real ? "outside" : "inside") + " on the client: border " + centerX + ", " + centerZ
                                + " size " + size + ", player " + playerX + ", " + playerZ + ", offset " + offset
                                + ", block " + x + ", " + z + ", " + square);
                    }
                }
            }
        }
    }

    // A spot inside the border, often close to one of its walls
    private static int playerStep(Random random, int size) {
        int step = random.nextInt(size);
        if (random.nextBoolean()) {
            int fromWall = Math.min(size - 1, random.nextInt(400));
            step = random.nextBoolean() ? fromWall : size - 1 - fromWall;
        }
        return step;
    }
}
