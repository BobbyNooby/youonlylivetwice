package bobbynooby.dev.hardcore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * For the mod's client tests, which sit in a package of their own: what they swap or read of the mod that only this
 * package can reach. Each call is made on the server thread, from a runOnServer or computeOnServer.
 */
public final class ClientBridge {
    private ClientBridge() {}

    /**
     * Puts the defaults with a change of the test's in use, and returns what puts back the settings in use before. A
     * server start reads the file again, so a test swaps after its server is up and puts back before it stops.
     */
    public static Runnable use(Consumer<HardcoreConfig.HardcoreSection> change) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(change));
        return () -> YoltHardcore.swapForTesting(before);
    }

    /** True while the mod's rules apply on the server that runs now. */
    public static boolean active() {
        return Host.active();
    }

    /** Makes the player an op at the level /yolt-hardcore needs, as the server GameTests do. */
    public static void op(ServerPlayer player) {
        TestPlayers.op(player);
    }

    /**
     * Takes the op off again. The op list is a file in the client's run folder, which every later server of the run
     * reads.
     */
    public static void deop(ServerPlayer player) {
        TestPlayers.deop(player);
    }

    /** Where the mod keeps the hardcore states of this server's world. */
    public static Path storeFile(MinecraftServer server) {
        return HardcoreStates.storeFile(server.getWorldPath(LevelResource.ROOT));
    }
}
