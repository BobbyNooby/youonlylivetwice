package bobbynooby.dev.hardcore;

/** The flat anvil price: one use pays for its biggest kind of job only, however much it does. */
public final class AnvilPrices {
    private AnvilPrices() {}

    /** Enchanting beats repairing, which beats renaming; 0 when the use does none of them. */
    public static int price(boolean enchants, boolean repairs, boolean renames, int enchantCost, int repairCost, int renameCost) {
        if (enchants) {
            return enchantCost;
        }
        if (repairs) {
            return repairCost;
        }
        if (renames) {
            return renameCost;
        }
        return 0;
    }
}
