package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Every graves.recheckSeconds, puts back the head of each grave in a loaded chunk whose head has gone, for example to
 * an End rebuild or /setblock, so it doesn't wait for the chunk's next load. With graves switched off too, since the
 * switch only stops new graves.
 */
public final class GraveRecheck {
    private static final int TICKS_PER_SECOND = 20;
    // Server thread only, like the rest
    private static long ticksLeft;
    // Graves whose restore failed since their chunk last loaded. Searching again would fail the same way until
    // something round them changes, so a pass leaves them to that chunk's next load.
    private static final Set<UUID> FAILING = new HashSet<>();
    // Each grave's failures logged since the server started, one line per kind
    private static final Set<Logged> LOGGED = new HashSet<>();

    /** Why the re-check couldn't put a grave back. */
    enum Failure {
        NO_SPOT,
        NOT_SAVED
    }

    private record Logged(UUID grave, Failure failure) {
    }

    private GraveRecheck() {
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(GraveRecheck::tick);
        // Static, so it would outlive the server in a JVM that starts another one
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            FAILING.clear();
            LOGGED.clear();
            ticksLeft = 0;
        });
    }

    /** Starts the wait for the next pass again from the live interval, so a reload's new value counts from now. */
    public static void apply(MinecraftServer server) {
        ticksLeft = Settings.get().graves.recheckSeconds * (long) TICKS_PER_SECOND;
    }

    private static void tick(MinecraftServer server) {
        int seconds = Settings.get().graves.recheckSeconds;
        // 0 seconds turns the re-check off
        if (!Settings.active() || seconds <= 0 || --ticksLeft > 0) {
            return;
        }
        ticksLeft = seconds * (long) TICKS_PER_SECOND;
        pass(server);
    }

    // Each level reads only its own graves. A pass that puts every head back where it was saves nothing; only a moved
    // grave is saved, as on a chunk load.
    private static void pass(MinecraftServer server) {
        GraveStore store = GraveStore.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Grave grave : store.inDimension(level.dimension())) {
                recheck(level, store, grave);
            }
        }
    }

    /**
     * The pass's check of one grave in its own level: when its own head is gone, the head is put back, or the grave
     * moves if its spot is taken or the End builds over it. Never loads a chunk: a grave with one round it that isn't
     * loaded waits for a later pass, or for its chunk's next load. So does one whose restore failed already.
     */
    public static void recheck(ServerLevel level, GraveStore store, Grave grave) {
        BlockPos pos = grave.pos();
        if (!FAILING.contains(grave.id()) && GravePlacement.loadedAround(level, pos.getX(), pos.getZ())
                && !GraveBlocks.isOwnHead(level, pos, grave)) {
            GraveBlocks.restore(level, store, grave, true);
        }
    }

    // Leaves the grave to its chunk's next load. True the first time it failed this way since the server started.
    static boolean failed(Grave grave, Failure failure) {
        FAILING.add(grave.id());
        return LOGGED.add(new Logged(grave.id(), failure));
    }

    // Its chunk loaded, so the passes try it again
    static void tryAgain(Grave grave) {
        FAILING.remove(grave.id());
    }
}
