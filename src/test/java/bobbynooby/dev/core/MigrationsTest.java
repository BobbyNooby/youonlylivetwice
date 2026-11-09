package bobbynooby.dev.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationsTest {
    // Each step adds its own name, so the result shows which steps ran and in what order
    private static final List<Migrations.Step<List<String>>> STEPS = List.of(
            new Migrations.Step<List<String>>(2, data -> append(data, "2to3")),
            new Migrations.Step<List<String>>(1, data -> append(data, "1to2")));

    private static List<String> append(List<String> data, String step) {
        List<String> next = new ArrayList<>(data);
        next.add(step);
        return next;
    }

    @Test
    void stepsRunInOrderFromTheFileVersion() {
        assertEquals(List.of("1to2", "2to3"), Migrations.run("test data", 1, 3, STEPS, List.of()));
        assertEquals(List.of("2to3"), Migrations.run("test data", 2, 3, STEPS, List.of()));
    }

    @Test
    void currentVersionRunsNothing() {
        List<String> data = List.of("saved");
        assertSame(data, Migrations.run("test data", 3, 3, STEPS, data));
    }

    @Test
    void newerVersionRefuses() {
        List<String> data = new ArrayList<>(List.of("saved"));
        assertThrows(IllegalArgumentException.class, () -> Migrations.run("test data", 4, 3, STEPS, data));
        assertEquals(List.of("saved"), data);
    }
}
