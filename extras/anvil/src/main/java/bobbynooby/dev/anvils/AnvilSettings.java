package bobbynooby.dev.anvils;

/**
 * What the flat anvil price is set to. One use costs the price of its biggest job: enchanting beats repairing, which
 * beats renaming. breakChance is the chance per use that the anvil wears down a step; vanilla's is 0.12.
 */
public record AnvilSettings(boolean enabled, int enchantCost, int repairCost, int renameCost, double breakChance) {
    /** The client shows "Too Expensive!" for a price of 40 or more, whatever the server allows, and 0 can't be paid. */
    public static final int MAX_PRICE = 39;
    public static final AnvilSettings DEFAULTS = new AnvilSettings(true, 6, 5, 1, 0.12);

    public static boolean isPrice(int price) {
        return price >= 1 && price <= MAX_PRICE;
    }

    // NaN fails both tests
    public static boolean isChance(double chance) {
        return chance >= 0 && chance <= 1;
    }

    /** The prices in the order a player meets them, as /status and the command show them. */
    public String prices() {
        return "rename " + renameCost + ", repair " + repairCost + ", enchant " + enchantCost;
    }

    /** The line /yolt-anvils shows, and /yolt-anvils reload after it reads the file. */
    public String describe() {
        if (!enabled) {
            return "Flat anvil prices are off";
        }
        return "Anvil prices: " + prices() + "; break chance " + breakChance;
    }
}
