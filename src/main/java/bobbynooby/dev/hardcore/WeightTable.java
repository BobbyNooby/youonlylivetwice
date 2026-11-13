package bobbynooby.dev.hardcore;

/** The sums behind carried weight, apart from the game so they can be checked on their own. */
public final class WeightTable {
    private WeightTable() {}

    /** One item's share of a full stack's weight. */
    public static double each(double stackWeight, int maxStackSize) {
        return stackWeight / Math.max(maxStackSize, 1);
    }

    /** One armour piece: its armour points times its material's factor. */
    public static double armour(int armourPoints, double factor) {
        return armourPoints * factor;
    }

    /** No slowdown up to the free load, rising evenly to maxSlow at the full load, and no further past it. */
    public static double slow(double load, double freeLoad, double fullLoad, double maxSlow) {
        return loadShare(load, freeLoad, fullLoad) * maxSlow;
    }

    /** How far the load is from the free load to the full one: 0 at or under the free load, 1 at or over the full one. */
    public static double loadShare(double load, double freeLoad, double fullLoad) {
        if (!(load > freeLoad)) {
            return 0.0;
        }
        // A full load at or under the free one leaves no range to rise through, so anything over is a full load
        if (!(fullLoad > freeLoad)) {
            return 1.0;
        }
        return Math.min((load - freeLoad) / (fullLoad - freeLoad), 1.0);
    }
}
