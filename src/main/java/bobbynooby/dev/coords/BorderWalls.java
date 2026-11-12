package bobbynooby.dev.coords;

import net.minecraft.util.Mth;

/**
 * The border a client with an offset is shown. A plainly shifted border would give the offset away, since the real
 * centre is known. So only the walls the player is near are the real ones, shifted; every other wall goes out past the
 * world's edge, where the client clamps it.
 */
public final class BorderWalls {
    // About a second of elytra travel on top of the view distance, as the walls are only rechecked once a second
    public static final int MARGIN = 128;

    private BorderWalls() {}

    /** A border as the client is sent it: a centre and a size, which the client clamps to the world's edge. */
    public record Square(double centerX, double centerZ, double size, boolean plain) {
        public double minX(int absoluteMax) {
            return Mth.clamp(centerX - size / 2.0, -absoluteMax, absoluteMax);
        }

        public double maxX(int absoluteMax) {
            return Mth.clamp(centerX + size / 2.0, -absoluteMax, absoluteMax);
        }

        public double minZ(int absoluteMax) {
            return Mth.clamp(centerZ - size / 2.0, -absoluteMax, absoluteMax);
        }

        public double maxZ(int absoluteMax) {
            return Mth.clamp(centerZ + size / 2.0, -absoluteMax, absoluteMax);
        }
    }

    /** How close a wall has to be to show: a client draws walls only within its render distance, which this caps. */
    public static double near(int viewDistance) {
        return viewDistance * 16 + MARGIN;
    }

    /**
     * The border to send a client whose player stands at the given real spot. Within near of the player every block is
     * on the same side of the walls as it really is. With both walls of one axis near, the border is small enough that
     * hiding it would show anyway, so it goes out plainly shifted.
     */
    public static Square forClient(double minX, double maxX, double minZ, double maxZ, double playerX, double playerZ,
                                   double near, int absoluteMax, Offset offset) {
        boolean nearMinX = playerX - minX <= near;
        boolean nearMaxX = maxX - playerX <= near;
        boolean nearMinZ = playerZ - minZ <= near;
        boolean nearMaxZ = maxZ - playerZ <= near;
        if ((nearMinX && nearMaxX) || (nearMinZ && nearMaxZ)) {
            return new Square((minX + maxX) / 2.0 + offset.x(), (minZ + maxZ) / 2.0 + offset.z(), maxX - minX, true);
        }
        // A square twice the world's edge, set so its one near wall is the real one and the far wall lands past the edge
        double centerX = nearMaxX ? (maxX + offset.x()) - absoluteMax : nearMinX ? (minX + offset.x()) + absoluteMax : 0.0;
        double centerZ = nearMaxZ ? (maxZ + offset.z()) - absoluteMax : nearMinZ ? (minZ + offset.z()) + absoluteMax : 0.0;
        return new Square(centerX, centerZ, 2.0 * absoluteMax, false);
    }
}
