package bobbynooby.dev.visibility;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MotdPickerTest {
    private static final int PICKS = 10_000;

    @Test
    void emptyListKeepsTheServerMotd() {
        MotdPicker picker = new MotdPicker(new Random(1));
        assertNull(picker.next(List.of()));
        assertNull(picker.next(List.of()));
    }

    @Test
    void oneLineIsAlwaysThatLine() {
        MotdPicker picker = new MotdPicker(new Random(2));
        for (int i = 0; i < 100; i++) {
            assertEquals("only", picker.next(List.of("only")));
        }
    }

    @Test
    void neverTheSameLineTwiceInARowAndEveryLineTurnsUp() {
        for (List<String> lines : List.of(List.of("a", "b"), List.of("a", "b", "c", "d", "e"))) {
            MotdPicker picker = new MotdPicker(new Random(3));
            Set<String> seen = new HashSet<>();
            String before = null;
            for (int i = 0; i < PICKS; i++) {
                String line = picker.next(lines);
                assertTrue(lines.contains(line), line + " is not one of " + lines);
                assertNotEquals(before, line, "the same line twice in a row at pick " + i);
                seen.add(line);
                before = line;
            }
            assertEquals(new HashSet<>(lines), seen);
        }
    }

    // A reload can leave only the line sent last, which is then the one there is
    @Test
    void changedListStillGivesALine() {
        MotdPicker picker = new MotdPicker(new Random(4));
        String first = picker.next(List.of("a", "b"));
        assertEquals(first, picker.next(List.of(first)));
        assertEquals("c", picker.next(List.of("c")));
        assertNull(picker.next(List.of()));
        // Nothing was sent for the empty list, so the next pick still steers clear of c
        assertEquals("d", picker.next(List.of("c", "d")));
    }
}
