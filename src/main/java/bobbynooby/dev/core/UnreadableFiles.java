package bobbynooby.dev.core;

import bobbynooby.dev.YouOnlyLiveTwice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Keeps a file the game couldn't read, so the next save doesn't overwrite it with an empty store. */
public final class UnreadableFiles {
    private UnreadableFiles() {
    }

    /**
     * A store that wasn't read from its file while the file is there means the file couldn't be read, and the next
     * save would overwrite it with nothing. So a copy is kept next to it. Returns the copy, if one was made.
     * "what" is the singular kind ("grave", "body"), used in the log text.
     */
    public static Optional<Path> keepCopy(Path file, boolean fromDisk, String what) {
        if (fromDisk || !Files.exists(file)) {
            return Optional.empty();
        }
        String plural = what.endsWith("y") ? what.substring(0, what.length() - 1) + "ies" : what + "s";
        Path copy = file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
        } catch (IOException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not read the {} file {}, and could not copy it to {}; {} start empty "
                    + "and the next save overwrites it", what, file, copy, plural, e);
            return Optional.empty();
        }
        YouOnlyLiveTwice.LOGGER.error("Could not read the {} file {}, so {} start empty. A copy of it is kept at {}",
                what, file, plural, copy);
        return Optional.of(copy);
    }
}
