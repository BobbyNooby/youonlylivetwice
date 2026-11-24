package bobbynooby.dev.mending;

import net.minecraft.server.level.ServerPlayer;

/**
 * For the mod's client tests, which sit in a package of their own: what they swap or read of the mod that only this
 * package can reach. Each call is made on the server thread, from a runOnServer or computeOnServer.
 */
public final class ClientBridge {
    private ClientBridge() {}

    /**
     * Puts these settings in use, and returns what puts back the settings in use before. A server start reads the file
     * again, so a test swaps after its server is up and puts back before it stops.
     */
    public static Runnable use(MendingSettings settings) {
        MendingSettings before = YoltMending.swapForTesting(settings);
        return () -> YoltMending.swapForTesting(before);
    }

    /** The part of a point the mod keeps for this player's next pickup. */
    public static double carried(ServerPlayer player) {
        return MendingRepair.carried(player.getUUID());
    }
}
