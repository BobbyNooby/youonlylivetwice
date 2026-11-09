package bobbynooby.dev.visibility;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Whether a player sees everyone and every hidden message. Off for everyone unless turned on. */
public final class AdminView {
    // Not saved, so a restart turns it off for everyone
    private static final Set<UUID> ON = new HashSet<>();

    private AdminView() {}

    public static boolean isOn(ServerPlayer player) {
        return ON.contains(player.getUUID());
    }

    public static void set(ServerPlayer player, boolean on) {
        if (on) {
            ON.add(player.getUUID());
        } else {
            ON.remove(player.getUUID());
        }
    }
}
