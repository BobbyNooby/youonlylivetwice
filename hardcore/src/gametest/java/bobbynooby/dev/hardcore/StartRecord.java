package bobbynooby.dev.hardcore;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * For the tests: the mod's config file as the server's start left it. The tests that write the file put back its text
 * but not its time, so only a look taken before any test runs tells whether this start wrote it.
 */
public class StartRecord implements ModInitializer {
    private static volatile @Nullable String text;
    private static volatile long changedAt = -1;

    @Override
    public void onInitialize() {
        // After every SERVER_STARTING listener, the mod's own start among them, and before any test runs
        ServerLifecycleEvents.SERVER_STARTED.register(server -> look(YoltHardcore.configFile()));
    }

    /** The file's text after the start, or null when there was none to read. */
    static @Nullable String text() {
        return text;
    }

    /** When the file last changed before the start ended, in milliseconds, or -1 when there was none to read. */
    static long changedAt() {
        return changedAt;
    }

    private static void look(Path file) {
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
            changedAt = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            text = null;
            changedAt = -1;
        }
    }
}
