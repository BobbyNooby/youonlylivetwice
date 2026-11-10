package bobbynooby.dev.skins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedSkinTest {
    private static final long HOUR = 60L * 60 * 1000;
    private static final long FETCHED = 1_000_000_000_000L;

    // A join asks Mojang again only once the saved answer is refreshHours old, a no-account answer included
    @Test
    void cacheRefreshAfterHours() {
        CachedSkin skin = CachedSkin.found("Notch", new SkinTextures("value", "signature"), FETCHED);
        CachedSkin nobody = CachedSkin.noAccount("Nobody", FETCHED);

        assertTrue(skin.fresh(FETCHED, 24), "fresh the moment it was fetched");
        assertTrue(skin.fresh(FETCHED + 24 * HOUR - 1, 24), "fresh just under 24 hours on");
        assertFalse(skin.fresh(FETCHED + 24 * HOUR, 24), "still fresh at 24 hours");
        assertFalse(skin.fresh(FETCHED + 25 * HOUR, 24), "still fresh after 25 hours");
        assertTrue(nobody.fresh(FETCHED + 23 * HOUR, 24), "a no-account answer was asked again within the hours");
        assertFalse(nobody.fresh(FETCHED + 24 * HOUR, 24), "a no-account answer was kept past the hours");
        assertFalse(skin.fresh(FETCHED, 0), "0 hours kept a skin instead of asking on every join");
        assertTrue(skin.fresh(FETCHED + 2 * HOUR, 3), "a shorter refresh wasn't followed");
        assertFalse(skin.fresh(FETCHED + 3 * HOUR, 3), "a shorter refresh wasn't followed");
        // A clock moved back can't keep an entry fresh for however long that was
        assertFalse(skin.fresh(FETCHED - HOUR, 24), "a skin from the future counted as fresh");
        // A huge setting still works out without overflowing
        assertTrue(skin.fresh(FETCHED + 1000 * HOUR, Integer.MAX_VALUE), "the largest refresh overflowed");
    }

    @Test
    void noAccountHasNoTextures() {
        assertFalse(CachedSkin.noAccount("Nobody", FETCHED).exists(), "no-account answer has a skin");
        assertTrue(CachedSkin.found("Notch", new SkinTextures("value", null), FETCHED).exists(), "found skin has none");
    }
}
