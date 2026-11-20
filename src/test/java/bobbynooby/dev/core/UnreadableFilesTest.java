package bobbynooby.dev.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UnreadableFilesTest {
    @TempDir
    Path dir;

    // A read-only folder, where no copy can be made. Skipped where the folder stays writable (as for root).
    private void inReadOnlyDir(Runnable test) {
        assumeTrue(dir.toFile().setWritable(false) && !Files.isWritable(dir), "the folder can't be made read-only here");
        try {
            test.run();
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    // A store loads from these while the server starts, so a copy that can't be made is only logged: the load goes on
    // and the file is left as it was
    @Test
    void copyFailureIsLoggedNotThrown() throws IOException {
        Path file = dir.resolve("graves.dat");
        Files.writeString(file, "what this version can't read");
        inReadOnlyDir(() -> {
            assertEquals(Optional.empty(), assertDoesNotThrow(() -> UnreadableFiles.keepCopy(file, false, "grave", "graves")));
            assertEquals(Optional.empty(), assertDoesNotThrow(() -> UnreadableFiles.keepNewerCopy(file, "grave")));
            assertEquals(Optional.empty(), assertDoesNotThrow(() -> UnreadableFiles.keepPartlyReadCopy(file, "grave")));
        });
        assertEquals(List.of("graves.dat"), names());
        assertEquals("what this version can't read", Files.readString(file));
    }

    // Only a file that is there but wasn't read needs a copy: one that was read is saved over with all it held, and a
    // missing one has nothing to lose
    @Test
    void keepCopyOnlyWhenTheFileWasNotRead() throws IOException {
        Path file = dir.resolve("graves.dat");
        assertEquals(Optional.empty(), UnreadableFiles.keepCopy(file, false, "grave", "graves"));
        assertEquals(List.of(), names());

        Files.writeString(file, "saved graves");
        assertEquals(Optional.empty(), UnreadableFiles.keepCopy(file, true, "grave", "graves"));
        assertEquals(List.of("graves.dat"), names());

        Path copy = UnreadableFiles.keepCopy(file, false, "grave", "graves").orElseThrow();
        assertTrue(copy.getFileName().toString().matches("graves\\.dat\\.unreadable-\\d+"), copy.toString());
        assertEquals(dir, copy.getParent());
        assertEquals("saved graves", Files.readString(copy));
        assertEquals("saved graves", Files.readString(file));
    }

    private static Optional<Path> keep(boolean newer, Path file) {
        return newer ? UnreadableFiles.keepNewerCopy(file, "body") : UnreadableFiles.keepPartlyReadCopy(file, "body");
    }

    // A restart before the store is saved again finds the same file, so the same bytes make no second copy. A changed
    // file gets one of its own. The first copy is given an older time first, as one from an earlier start would have,
    // so the second can't land on its name within the same millisecond.
    @Test
    void copiesAreKeptOncePerContent() throws IOException {
        Path file = dir.resolve("bodies.dat");
        for (String prefix : List.of("bodies.dat.newer-", "bodies.dat.unreadable-")) {
            boolean newer = prefix.contains("newer");
            Files.writeString(file, "first " + prefix);
            Path first = keep(newer, file).orElseThrow(() -> new AssertionError("no first copy for " + prefix));
            assertTrue(first.getFileName().toString().startsWith(prefix), first.toString());
            Path older = Files.move(first, dir.resolve(prefix + "1"));

            assertEquals(Optional.empty(), keep(newer, file), "a second copy of the same bytes for " + prefix);

            Files.writeString(file, "second " + prefix);
            Path second = keep(newer, file).orElseThrow(() -> new AssertionError("no copy of the changed file for " + prefix));
            assertNotEquals(older, second);
            assertEquals("second " + prefix, Files.readString(second));
            assertEquals("first " + prefix, Files.readString(older));
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(5, files.count(), names().toString());
        }
    }
}
