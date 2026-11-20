package bobbynooby.dev.skins;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinsTest {
    // Mojang is only asked about a name an account can have: 1 to 16 of a-z, A-Z, 0-9 and _. An offline-mode player can
    // join under any other name, and asking about it would be a wasted lookup.
    @Test
    void couldBeAccountMatchesOnlyMojangNameShapes() {
        for (String name : List.of("a", "A_1", "Notch", "abcdefghijklmnop")) {
            assertTrue(Skins.couldBeAccount(name), name);
        }
        for (String name : List.of("", "abcdefghijklmnopq", "no.such", "a-b", "a b", "名前")) {
            assertFalse(Skins.couldBeAccount(name), name);
        }
    }
}
