package bobbynooby.dev.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuardTest {
    // A failure in one of YOLT's handlers is logged and kept out of the vanilla code that called it. An Error, such as a
    // failed test assertion or running out of memory, is never swallowed.
    @Test
    void runtimeExceptionIsSwallowedButAnErrorIsNot() {
        assertDoesNotThrow(() -> Guard.run(() -> {
            throw new IllegalStateException("handler broke");
        }, "A handler failed for {}", "Ghost"));

        AssertionError error = new AssertionError("must reach the caller");
        assertSame(error, assertThrows(AssertionError.class, () -> Guard.run(() -> {
            throw error;
        }, "A handler failed for {}", "Ghost")));

        boolean[] ran = {false};
        Guard.run(() -> ran[0] = true, "A handler failed");
        assertTrue(ran[0]);
    }
}
