package bobbynooby.dev.anvils;

import bobbynooby.dev.anvils.shared.TinyMod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The shared TinyMod, through this mod's copy of it, on a mod of its own so the real one is never hosted here
class TinyModTest {
    private static final AnvilSettings FIRST = new AnvilSettings(true, 9, 8, 7, 0.5);
    private static final AnvilSettings SECOND = new AnvilSettings(false, 1, 1, 1, 0.0);

    // A second host would silently take over from the first, which still thinks it sets the mod, so it is refused and
    // the first stays
    @Test
    void aSecondHostIsRefusedAndTheFirstStays() {
        TinyMod<AnvilSettings> mod = new TinyMod<>(YoltAnvils.MOD_ID, "YOLT Anvils", AnvilConfig.FORMAT,
                AnvilSettings.DEFAULTS, AnvilSettings::enabled, AnvilSettings::prices, AnvilSettings::describe);
        mod.hostedBy("First Host", () -> FIRST, player -> false);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mod.hostedBy("Second Host", () -> SECOND, player -> true));
        assertEquals("YOLT Anvils is hosted by First Host already, so Second Host can't host it too", thrown.getMessage());
        assertTrue(mod.hosted());
        assertSame(FIRST, mod.settings());
        assertEquals("the settings come from First Host", mod.reload().notes().getFirst());
    }
}
