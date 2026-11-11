package bobbynooby.dev.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** Keeps a copy of a store file this version couldn't fully read, so the next save doesn't overwrite what is in it. */
public final class UnreadableFiles {
    private UnreadableFiles() {}

    /**
     * A store that wasn't read from its file while the file is there means the file couldn't be read, and the next
     * save would overwrite it with nothing. So a copy is kept next to it. Returns the copy, if one was made.
     * "what" and "plural" name the kind ("grave" and "graves"), for the log text.
     */
    public static Optional<Path> keepCopy(Path file, boolean fromDisk, String what, String plural) {
        if (fromDisk || !Files.exists(file)) {
            return Optional.empty();
        }
        Path copy = file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
        } catch (IOException e) {
            Yolt.LOGGER.error("Could not read the {} file {}, and could not copy it to {}; {} start empty "
                    + "and the next save overwrites it", what, file, copy, plural, e);
            return Optional.empty();
        }
        Yolt.LOGGER.error("Could not read the {} file {}, so {} start empty. A copy of it is kept at {}",
                what, file, plural, copy);
        return Optional.of(copy);
    }

    /**
     * A file from a newer YOLT or Minecraft loads only what this version can read, and the next save would write only
     * that. So a copy is kept next to it first, unless one with the same bytes is already there: a restart before the
     * store is saved again finds the same file. Returns the copy, if one was made.
     */
    public static Optional<Path> keepNewerCopy(Path file, String what) {
        String prefix = file.getFileName() + ".newer-";
        Optional<Path> kept = sameCopy(file, prefix);
        if (kept.isPresent()) {
            Yolt.LOGGER.warn("The {} file {} is from a newer version; a copy of it is already kept at {}", what, file, kept.get());
            return Optional.empty();
        }
        Path copy = file.resolveSibling(prefix + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
        } catch (IOException e) {
            Yolt.LOGGER.error("The {} file {} is from a newer version and could not be copied to {}; the next save "
                    + "drops what this version can't read", what, file, copy, e);
            return Optional.empty();
        }
        Yolt.LOGGER.warn("The {} file {} is from a newer version, so a copy of it is kept at {} before it is saved over",
                what, file, copy);
        return Optional.of(copy);
    }

    /**
     * A store that left out part of its file, a record or an item it couldn't read, would drop that part for good on
     * its next save. So a copy is kept next to the file first, unless one with the same bytes is already there: a
     * restart before the store is saved again finds the same file. Returns the copy, if one was made.
     */
    public static Optional<Path> keepPartlyReadCopy(Path file, String what) {
        String prefix = file.getFileName() + ".unreadable-";
        Optional<Path> kept = sameCopy(file, prefix);
        if (kept.isPresent()) {
            Yolt.LOGGER.warn("Part of the {} file {} could not be read; a copy of it is already kept at {}", what, file, kept.get());
            return Optional.empty();
        }
        Path copy = file.resolveSibling(prefix + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
        } catch (IOException e) {
            Yolt.LOGGER.error("Part of the {} file {} could not be read, and the file could not be copied to {}; the next "
                    + "save drops that part", what, file, copy, e);
            return Optional.empty();
        }
        Yolt.LOGGER.warn("Part of the {} file {} could not be read and was left out, so a copy of it is kept at {} before "
                + "it is saved over", what, file, copy);
        return Optional.of(copy);
    }

    // A copy that can't be compared counts as missing, since one copy too many is the safe side
    private static Optional<Path> sameCopy(Path file, String prefix) {
        try (Stream<Path> siblings = Files.list(file.toAbsolutePath().getParent())) {
            for (Path sibling : siblings.toList()) {
                if (sibling.getFileName().toString().startsWith(prefix) && Files.mismatch(file, sibling) == -1) {
                    return Optional.of(sibling);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
