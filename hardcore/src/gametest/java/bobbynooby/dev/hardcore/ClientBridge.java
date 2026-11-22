package bobbynooby.dev.hardcore;

import net.minecraft.server.MinecraftServer;
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

    /** The features on, as /yolt-hardcore and the start line word them. */
    public static String features() {
        return HardcoreCommands.features(Host.settings());
    }

    /** Where the mod keeps the hardcore states of this server's world. */
    public static Path storeFile(MinecraftServer server) {
        return HardcoreStates.storeFile(server.getWorldPath(LevelResource.ROOT));
    }
}
