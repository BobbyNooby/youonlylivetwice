package bobbynooby.dev.core;

import java.util.Arrays;

/** Keeps a failure in one of our handlers from escaping into the vanilla code that called it. */
public final class Guard {
    private Guard() {}

    /** Runs the task; a RuntimeException is logged as an error with the message and args, and swallowed. */
    public static void run(Runnable task, String message, Object... args) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // SLF4J takes a trailing Throwable as the exception, not as an argument
            Object[] withError = Arrays.copyOf(args, args.length + 1);
            withError[args.length] = e;
            Yolt.LOGGER.error(message, withError);
        }
    }
}
