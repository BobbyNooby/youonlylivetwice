package bobbynooby.dev.visibility;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Picks the server list's MOTD line for each ping: one at random, never the line it picked last while another is left.
 * Pings are answered on network threads, several at once, so a pick and the line it remembers change together.
 */
public final class MotdPicker {
    private final RandomGenerator random;
    private @Nullable String last;

    public MotdPicker(RandomGenerator random) {
        this.random = random;
    }

    /** A line from lines, or null for an empty list, which keeps the server's own MOTD. */
    public synchronized @Nullable String next(List<String> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        // Compared by text, not by place, since a reload can change the list between two pings
        List<String> others = lines.stream().filter(line -> !line.equals(last)).toList();
        List<String> from = others.isEmpty() ? lines : others;
        last = from.get(random.nextInt(from.size()));
        return last;
    }
}
