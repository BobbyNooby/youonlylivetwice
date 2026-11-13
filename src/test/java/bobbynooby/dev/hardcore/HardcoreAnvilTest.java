package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreAnvilTest {
    private static final int ENCHANT = 6;
    private static final int REPAIR = 5;
    private static final int RENAME = 1;

    // Extras in the same use come free
    @Test
    void anvil1TheBiggestJobSetsThePrice() {
        assertEquals(6, AnvilPrices.price(true, true, true, ENCHANT, REPAIR, RENAME));
        assertEquals(5, AnvilPrices.price(false, true, true, ENCHANT, REPAIR, RENAME));
        assertEquals(1, AnvilPrices.price(false, false, true, ENCHANT, REPAIR, RENAME));
        assertEquals(6, AnvilPrices.price(true, false, false, ENCHANT, REPAIR, RENAME));
        assertEquals(0, AnvilPrices.price(false, false, false, ENCHANT, REPAIR, RENAME));
    }
}
