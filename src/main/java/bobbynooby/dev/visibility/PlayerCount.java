package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import net.minecraft.server.players.NameAndId;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** The player counts the server list shows. */
public final class PlayerCount {
    // Clients show "0/1" for a server that has a slot but nobody on it, which tells nothing about the real numbers
    public static final int SHOWN_MAX = 1;
    public static final int SHOWN_ONLINE = 0;

    private PlayerCount() {}

    /** The online count a server list, ping or query shows. */
    public static int online(int real) {
        return hides() ? SHOWN_ONLINE : real;
    }

    /** The slot count a server list, ping or query shows. */
    public static int max(int real) {
        return hides() ? SHOWN_MAX : real;
    }

    /** The player names the query port lists. */
    public static String[] names(String[] real) {
        return hides() ? new String[0] : real;
    }

    /** The names the server list's hover shows: the configured lines if there are any, otherwise vanilla's rule. */
    public static List<NameAndId> sample(List<NameAndId> real) {
        List<String> lines = Settings.get().serverList.hoverLines;
        if (Settings.active() && !lines.isEmpty()) {
            // The client shows each entry's name as plain text, so a line is a fake player. Its id comes from the text,
            // so it stays the same from one ping to the next.
            return lines.stream().map(line -> new NameAndId(UUID.nameUUIDFromBytes(line.getBytes(StandardCharsets.UTF_8)), line)).toList();
        }
        return hides() ? List.of() : real;
    }

    /** True when the server list must show the fake counts and no names. */
    public static boolean hides() {
        return Settings.on(c -> c.hide.serverPlayerCount);
    }
}
