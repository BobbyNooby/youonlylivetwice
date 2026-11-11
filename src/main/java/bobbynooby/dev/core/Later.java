package bobbynooby.dev.core;

import net.minecraft.server.MinecraftServer;

/** Work that has to wait until the current tick is done. */
public final class Later {
    private Later() {}

    /**
     * Queues the task for the server thread. execute() would run it straight away when already on that thread;
     * schedule() always queues it.
     */
    public static void afterTick(MinecraftServer server, Runnable task) {
        server.schedule(server.wrapRunnable(task));
    }
}
