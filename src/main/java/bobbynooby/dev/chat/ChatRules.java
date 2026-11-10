package bobbynooby.dev.chat;

import bobbynooby.dev.config.YoltConfig;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Decides who hears a chat message or a whisper. No Minecraft classes, so it is unit-tested. */
public final class ChatRules {
    private ChatRules() {}

    public record Listener(UUID id, String dimension, double x, double y, double z, boolean adminView) {}

    public static Set<UUID> chatRecipients(Listener sender, Collection<Listener> everyone,
                                           YoltConfig.ChatMode mode, int radius) {
        Set<UUID> heard = new HashSet<>();
        heard.add(sender.id());
        for (Listener l : everyone) {
            boolean hears = switch (mode) {
                case GLOBAL -> true;
                case SELF_ONLY -> l.adminView();
                case PROXIMITY -> l.adminView() || inRange(sender, l, radius);
            };
            if (hears) heard.add(l.id());
        }
        return heard;
    }

    // Global chat reaches everyone, so a whisper there has no range either
    public static boolean whisperArrives(Listener sender, Listener target, YoltConfig.ChatMode mode, int radius) {
        return mode == YoltConfig.ChatMode.GLOBAL || inRange(sender, target, radius);
    }

    private static boolean inRange(Listener a, Listener b, int radius) {
        if (!a.dimension().equals(b.dimension())) return false;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        // compare squared so the boundary stays exact
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }
}
