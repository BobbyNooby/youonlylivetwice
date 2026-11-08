package bobbynooby.dev.features;


import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;


public class RandomSpawn {

    public static BlockPos findRandomSpawn(ServerLevel level) {
        int attempts = 0;
        int attemptLimit = 50;
        while (true) {

            // Get random coordinates within world border
            int borderRadius = (int) (level.getWorldBorder().getSize() / 2);
            Random random = new Random();
            int x = random.nextInt(borderRadius * 2) - borderRadius;
            int z = random.nextInt(borderRadius * 2) - borderRadius;
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
}
