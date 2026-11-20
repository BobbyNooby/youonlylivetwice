package bobbynooby.dev.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UndoTest {
    // Taking back a failed change must not stop half way: a step that throws is logged, and the steps after it still run
    @Test
    void everyStepRunsWhenOneThrows() {
        List<String> ran = new ArrayList<>();
        assertDoesNotThrow(() -> Undo.runAll(List.of(
                () -> ran.add("first"),
                () -> {
                    ran.add("second");
                    throw new IllegalStateException("could not put it back");
                },
                () -> ran.add("third")), "Could not undo a change for {}", "Ghost"));
        assertEquals(List.of("first", "second", "third"), ran);
    }
}
