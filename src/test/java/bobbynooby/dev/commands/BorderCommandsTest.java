package bobbynooby.dev.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BorderCommandsTest {
    @Test
    void shortListIsShownWhole() {
        assertEquals("Alex, Steve's body, Bob's grave", BorderCommands.listed(List.of("Alex", "Steve's body", "Bob's grave")));
    }

    @Test
    void longListShowsTenAndHowManyMore() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            names.add("P" + i);
        }
        assertEquals("P1, P2, P3, P4, P5, P6, P7, P8, P9, P10 and 5 more", BorderCommands.listed(names));
    }
}
