package bobbynooby.dev.lockout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeathLockoutTest {
    @TempDir
    Path dir;

    // The database folder can't be made where a file already is
    @Test
    void storeThatCannotOpenIsNull() throws IOException {
        Path blocker = Files.writeString(dir.resolve("database"), "");
        assertNull(assertDoesNotThrow(() -> DeathLockout.openStore(blocker.resolve("test.db"))));
    }

    @Test
    void storeOpensAndCloses() {
        DeathStore store = DeathLockout.openStore(dir.resolve("database/test.db"));
        assertNotNull(store);
        assertDoesNotThrow(() -> DeathLockout.closeStore(store));
    }

    @Test
    void closingNoStoreDoesNothing() {
        assertDoesNotThrow(() -> DeathLockout.closeStore(null));
    }
}
