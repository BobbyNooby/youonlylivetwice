package bobbynooby.dev.features;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.DimensionTypes;

public class WorldBorders {
    public static void setupWorldBorder(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            WorldBorder worldBorder = world.getWorldBorder();

            if (world.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD) || world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
                worldBorder.setSize(Config.getWorldDiameter()); // Set the world border size for overworld and nether
            } else if (world.getDimensionEntry().matchesKey(DimensionTypes.THE_END)) {
                worldBorder.setSize(Config.getWorldDiameter() * Config.getEndBorderScale()); // Scaled border for the end
            }
        }
    }
}
