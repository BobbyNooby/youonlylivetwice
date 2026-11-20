package bobbynooby.dev.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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

    // Counting up through every number from a damaged version would take billions of turns
    @Test
    void farOlderVersionOnlyWalksTheSteps() {
        assertTimeout(Duration.ofMillis(500), () ->
                assertEquals(List.of("1to2", "2to3"), Migrations.run("test data", Integer.MIN_VALUE, 3, STEPS, List.of())));
    }

    // A version with no step of its own, here 2 between the steps from 1 and from 3, only changes the number: the walk
    // goes on to the next step rather than stopping at the gap. A step from the target version itself never runs.
    @Test
    void versionWithNoStepOnlyChangesTheNumberAndLaterStepsStillRun() {
        List<Migrations.Step<List<String>>> gap = List.of(
                new Migrations.Step<List<String>>(1, data -> append(data, "1to2")),
                new Migrations.Step<List<String>>(3, data -> append(data, "3to4")));
        assertEquals(List.of("1to2", "3to4"), Migrations.run("test data", 1, 4, gap, List.of()));
        assertEquals(List.of("3to4"), Migrations.run("test data", 2, 4, gap, List.of()));
        assertEquals(List.of("1to2"), Migrations.run("test data", 1, 3, gap, List.of()));
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
