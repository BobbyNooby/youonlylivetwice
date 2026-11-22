package bobbynooby.dev.coords;

import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * For the mod's client tests, which sit in a package of their own: what they swap or read of the mod that only this
 * package can reach. Each call but configFile is made on the server thread, from a runOnServer or computeOnServer.
 */
public final class ClientBridge {
    private ClientBridge() {}

    /**
     * Puts these settings in use, and returns what puts back the settings in use before. A server start reads the file
     * again, so a test swaps after its server is up and puts back before it stops.
     */
    public static Runnable use(CoordinateSettings settings) {
        CoordinateSettings before = YoltCoordinates.swapForTesting(settings);
        return () -> YoltCoordinates.swapForTesting(before);
    }

    /** True while the mod runs on the server that runs now. */
    public static boolean active() {
        return YoltCoordinates.active();
    }

    /** True when a frame that starts now gives this player real coordinates. */
    public static boolean seesReal(ServerPlayer player) {
        return YoltCoordinates.seesReal(player);
    }

    /** Takes the player off the list /yolt-coordinates real keeps, whatever they turned on. */
    public static void forgetReal(ServerPlayer player) {
        YoltCoordinates.forgetRealForTesting(player);
    }

    /** The offset what the player's client sends is read in now, which after a new frame switches at the accept. */
    public static Offset incomingOffset(ServerPlayer player) {
        return CoordinateOffsets.incomingOffset(player);
    }

    /** Where the mod reads and writes its file: config/yolt-coordinates.json in the client's run folder. */
    public static Path configFile() {
        return YoltCoordinates.configFile();
    }
}
