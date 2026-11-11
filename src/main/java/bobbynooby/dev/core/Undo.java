package bobbynooby.dev.core;

import java.util.List;

/** Takes back what a failed multi-step change already did. */
public final class Undo {
    private Undo() {}

    /**
     * Runs every step, since one failing must not leave another's changes in place. A failure is logged with
     * what (a message with one {} for who) and never thrown.
     */
    public static void runAll(List<Runnable> steps, String what, String who) {
        for (Runnable step : steps) {
            Guard.run(step, what, who);
        }
    }
}
