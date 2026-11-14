package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;

import java.util.Random;

/**
 * The MOTD a server-list ping is answered with: a line from serverList.motdLines, new for every ping. Vanilla rebuilds
 * its status only every few seconds, so the line goes on each answer as it is sent, on the network thread.
 */
public final class ServerListMotd {
    private static final MotdPicker PICKER = new MotdPicker(new Random());

    private ServerListMotd() {}

    /** The status one ping is sent: vanilla's own with no lines, otherwise a copy with a picked line as its MOTD. */
    public static ServerStatus forPing(ServerStatus status) {
        if (!Settings.active()) {
            return status;
        }
        String line = PICKER.next(Settings.get().serverList.motdLines);
        if (line == null) {
            return status;
        }
        // Plain text, as the hover lines and the tab header are, so the client reads § codes itself
        return new ServerStatus(Component.literal(line), status.players(), status.version(), status.favicon(),
                status.enforcesSecureChat());
    }
}
