package bobbynooby.dev.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir
    Path dir;

    @Test
    void failedWriteLeavesTheOldFileWhole() throws IOException {
        Path file = dir.resolve("store.dat");
        Files.writeString(file, "old contents");
        assertThrows(IOException.class, () -> AtomicFiles.write(file, tmp -> {
            Files.writeString(tmp, "half a new");
            throw new IOException("cut off");
        }));
        assertEquals("old contents", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("store.dat.tmp")), "the temp file was left behind");
    }

    @Test
    void writeReplacesTheContentAndLeavesNoTempFile() throws IOException {
        Path file = dir.resolve("store.dat");
        Files.writeString(file, "old contents");
        AtomicFiles.write(file, tmp -> Files.writeString(tmp, "new contents", StandardCharsets.UTF_8));
        assertEquals("new contents", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("store.dat.tmp")), "the temp file was left behind");
    }

    // A crash between the temp write and the move leaves the temp file behind, longer than the next write. The next
    // write must start it afresh, not write into it, or the old tail would end up in the target.
    @Test
    void staleTempFileFromACrashIsReplacedNotAppended() throws IOException {
        Path file = dir.resolve("store.dat");
        Files.writeString(file, "old contents");
        Files.writeString(dir.resolve("store.dat.tmp"), "a much longer temp file left behind by a crash");
        AtomicFiles.write(file, tmp -> Files.writeString(tmp, "new"));
        assertEquals("new", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("store.dat.tmp")), "the temp file was left behind");
    }

    // A writer can fail with an unchecked exception too, such as an encoder that throws. It reaches the caller as it
    // was, and the old file stays whole with no temp file beside it.
    @Test
    void runtimeExceptionInTheWriterLeavesTheOldFileAndNoTemp() throws IOException {
        Path file = dir.resolve("store.dat");
        Files.writeString(file, "old contents");
        IllegalStateException thrown = new IllegalStateException("encoder broke");
        IllegalStateException caught = assertThrows(IllegalStateException.class, () -> AtomicFiles.write(file, tmp -> {
            Files.writeString(tmp, "half a new");
            throw thrown;
        }));
        assertSame(thrown, caught);
        assertEquals("old contents", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("store.dat.tmp")), "the temp file was left behind");
    }

    @Test
    void writeMakesAFileThatWasNotThere() throws IOException {
        Path file = dir.resolve("store.dat");
        AtomicFiles.write(file, tmp -> Files.writeString(tmp, "first"));
        assertEquals("first", Files.readString(file));
    }
}
