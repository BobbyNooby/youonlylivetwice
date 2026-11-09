package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.util.function.BiConsumer;

/** Sizes the overworld, nether and end borders from the config. */
public final class WorldBorders {
    private WorldBorders() {}

    /** Startup and reload: a timed vanilla /worldborder change keeps running, since setSize would stop it. */
    public static void apply(MinecraftServer server) {
        apply(server, false);
    }

    /** force: set every border now, ending a moving one, as vanilla's /worldborder set does. For the admin commands. */
    public static void apply(MinecraftServer server, boolean force) {
        if (!Settings.on(c -> c.worldBorder.enabled)) {
            return;
        }
        YoltConfig.WorldBorderSection config = Settings.get().worldBorder;

        forEachBorder(server, (level, isEnd) -> {
            WorldBorder worldBorder = level.getWorldBorder();
            if (!force && worldBorder.getStatus() != BorderStatus.STATIONARY) {
                return;
            }

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
