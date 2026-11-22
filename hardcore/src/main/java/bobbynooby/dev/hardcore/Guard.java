package bobbynooby.dev.hardcore;

import java.util.Arrays;

/** Keeps a failure in one of the mod's handlers from escaping into the vanilla code that called it. */
final class Guard {
    private Guard() {}

    /** Runs the task; a RuntimeException is logged as an error with the message and args, and swallowed. */
    static void run(Runnable task, String message, Object... args) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // SLF4J takes a trailing Throwable as the exception, not as an argument
            Object[] withError = Arrays.copyOf(args, args.length + 1);
            withError[args.length] = e;
            YoltHardcore.LOGGER.error(message, withError);
        }
    }
}
