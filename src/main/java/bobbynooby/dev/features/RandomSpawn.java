package bobbynooby.dev.features;


import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Random;

public class RandomSpawn {

    public static BlockPos findRandomSpawn(ServerWorld world) {
        int attempts = 0;
        int attemptLimit = 50;
        while (true) {

            // Get random coordinates within world border
            int borderRadius = (int) (world.getWorldBorder().getSize() / 2);
            Random random = new Random();
            int x = random.nextInt(borderRadius * 2) - borderRadius;
            int z = random.nextInt(borderRadius * 2) - borderRadius;
            int y = world.getWorldChunk(new BlockPos(x, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z), z)).sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z) + 1;

            // Check if it's a water block that the player spawns on
            BlockState blockBelow = world.getBlockState(new BlockPos(x, y - 1, z));

            // If it's a water block then regenerate a spawn
            if (!blockBelow.getFluidState().isEmpty() && attempts < attemptLimit) {
                attempts++;
                continue;
            }

            // After exceeding the attempt limit, just return the spawn even if it's in water.
            return new BlockPos(x, y, z);
        }
    }
}
