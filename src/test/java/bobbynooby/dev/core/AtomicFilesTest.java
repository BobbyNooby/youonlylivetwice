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

    @Test
    void writeMakesAFileThatWasNotThere() throws IOException {
        Path file = dir.resolve("store.dat");
        AtomicFiles.write(file, tmp -> Files.writeString(tmp, "first"));
        assertEquals("first", Files.readString(file));
    }
}
