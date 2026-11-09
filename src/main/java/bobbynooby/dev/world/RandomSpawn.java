package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/** Picks a random spot inside the world border for new and respawning players. */
public final class RandomSpawn {
    private RandomSpawn() {}

    private static final Random RANDOM = new Random();

    record Column(int x, int z) {}

    // Only the overworld spawn is randomised, other dimensions keep vanilla spawning
    public static boolean appliesTo(ServerLevel level) {
        return Settings.on(c -> c.randomSpawn) && level.dimension() == Level.OVERWORLD;
    }

    public static BlockPos findRandomSpawn(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        int attempts = 0;
        int attemptLimit = 50;
        while (true) {

            Column column = pickColumn(RANDOM, border.getCenterX(), border.getCenterZ(), border.getSize(), Settings.get().worldBorder.diameter);
            int x = column.x();
            int z = column.z();
            int y = level.getChunkAt(new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z)).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;

            // Check if it's a water block that the player spawns on
            BlockState blockBelow = level.getBlockState(new BlockPos(x, y - 1, z));

            // If it's a water block then regenerate a spawn
            if (!blockBelow.getFluidState().isEmpty() && attempts < attemptLimit) {
                attempts++;
                continue;
            }

            // After exceeding the attempt limit, just return the spawn even if it's in water.
            return new BlockPos(x, y, z);
        }
    }

    /**
     * A random column inside the smaller of the live border and YOLT's diameter, around the live border's centre.
     * The diameter counts even when YOLT's border isn't applied: a new world picks its spawn before the server starts.
     */
    static Column pickColumn(Random random, double centerX, double centerZ, double borderSize, int diameter) {
        // nextInt(0) throws, so a border under 2 blocks still gets a radius of 1
        int radius = Math.max(1, (int) (Math.min(borderSize, diameter) / 2));
        int x = (int) Math.floor(centerX) + random.nextInt(radius * 2) - radius;
        int z = (int) Math.floor(centerZ) + random.nextInt(radius * 2) - radius;
        return new Column(x, z);
    }
}
