package bobbynooby.dev.features;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.border.WorldBorder;

public class WorldBorders {

    public static void setupWorldBorder(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        ServerWorld nether = server.getWorld(ServerWorld.NETHER);
        ServerWorld end = server.getWorld(ServerWorld.END);

        WorldBorder overworldBorder = overworld.getWorldBorder();
        overworldBorder.setCenter(0, 0);
        overworldBorder.setSize(10000);

        if (nether != null) {


        }
    }
}
