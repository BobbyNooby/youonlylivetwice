package bobbynooby.dev.features;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

public class WorldBorders {
    public static void apply(MinecraftServer server) {
        if (!Settings.on(c -> c.worldBorder.enabled)) {
            return;
        }
        YoltConfig.WorldBorderSection config = Settings.get().worldBorder;

        for (ServerLevel level : server.getAllLevels()) {
            WorldBorder worldBorder = level.getWorldBorder();

            if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
                worldBorder.setSize(config.diameter);
            } else if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
                worldBorder.setSize(config.diameter * config.endScale);
            }
        }
    }
}
