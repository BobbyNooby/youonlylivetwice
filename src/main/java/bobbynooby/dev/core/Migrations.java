package bobbynooby.dev.core;

import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/** Ordered upgrade steps for a saved file, so a file from an older YOLT can be brought up to the current layout. */
public final class Migrations {
    private Migrations() {
    }

    /** Moves data saved at version {@code from} to version {@code from + 1}. */
    public record Step<T>(int from, UnaryOperator<T> apply) {
    }

    /**
     * Runs every step from the file's version up to {@code to}, in version order, and returns the upgraded data.
     * A version with no step only changes the number. Data from a newer version throws before any step runs: this
     * YOLT can't know what changed, so the caller keeps the data as it is. Only the steps are walked, never each
     * number in between, so a damaged version far below them costs nothing.
     */
    public static <T> T run(String what, int from, int to, List<Step<T>> steps, T data) {
        if (from > to) {
            throw new IllegalArgumentException(what + " is v" + from + ", newer than this version of YOLT reads (v" + to + ")");
        }
        List<Step<T>> due = steps.stream()
                .filter(step -> step.from() >= from && step.from() < to)
                .sorted(Comparator.comparingInt(Step::from))
                .toList();
        T upgraded = data;
        for (Step<T> step : due) {
            upgraded = step.apply().apply(upgraded);
            Yolt.LOGGER.info("Upgraded {} from v{} to v{}", what, step.from(), step.from() + 1);
        }
        return upgraded;
    }
}
