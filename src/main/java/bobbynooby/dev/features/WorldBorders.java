package bobbynooby.dev.features;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

public class WorldBorders {
    public static void setupWorldBorder(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            WorldBorder worldBorder = level.getWorldBorder();

            if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
                worldBorder.setSize(Config.getWorldDiameter()); // Set the world border size for overworld and nether
            } else if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
                worldBorder.setSize(Config.getWorldDiameter() * Config.getEndBorderScale()); // Scaled border for the end
            }
        }
    }
}
