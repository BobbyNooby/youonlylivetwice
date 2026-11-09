package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.util.function.BiConsumer;

public class WorldBorders {
    public static void apply(MinecraftServer server) {
        if (!Settings.on(c -> c.worldBorder.enabled)) {
            return;
        }
        YoltConfig.WorldBorderSection config = Settings.get().worldBorder;

        forEachBorder(server, (level, isEnd) -> {
            WorldBorder worldBorder = level.getWorldBorder();

            if (isEnd) {
                worldBorder.setSize(config.diameter * config.endScale);
            } else {
                worldBorder.setSize(config.diameter);
            }
        });
    }

    /** Runs for each level whose border YOLT sets: the overworld and the nether (false), and the end (true). */
    public static void forEachBorder(MinecraftServer server, BiConsumer<ServerLevel, Boolean> action) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD)
                    || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
                action.accept(level, false);
            } else if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
                action.accept(level, true);
            }
        }
    }
}
