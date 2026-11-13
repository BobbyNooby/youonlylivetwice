package bobbynooby.dev.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes a file so a crash mid-write leaves the old file whole rather than half a new one. */
public final class AtomicFiles {
    private AtomicFiles() {}

    @FunctionalInterface
    public interface IoAction {
        void run(Path path) throws IOException;
    }

    /** Runs writeTo on a temp file next to the target, forces it to disk, then moves it over the target. */
    public static void write(Path file, IoAction writeTo) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            writeTo.run(tmp);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
