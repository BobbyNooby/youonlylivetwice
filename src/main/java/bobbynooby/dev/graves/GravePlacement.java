package bobbynooby.dev.graves;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** Picks the block a grave head goes on: near the death spot, never on another grave, always reachable. */
public final class GravePlacement {
    private static final int VOID_SEARCH_RADIUS = 16;
    private static final int UP_SEARCH = 8;
    private static final int RING_RADIUS = 3;
    private static final int RING_UP = 3;

    private GravePlacement() {
    }

    public static BlockPos find(ServerLevel level, BlockPos deathPos, GraveStore store) {
        int minY = level.getMinY();
        int topY = level.getMaxY() - 1;
        BlockPos start = new BlockPos(deathPos.getX(), Math.clamp(deathPos.getY(), minY, topY), deathPos.getZ());
        if (isVoidColumn(level, start)) {
            start = reachableSpotNear(level, start);
        }

        if (usable(level, start, store)) {
            return start;
        }
        for (int dy = 1; dy <= UP_SEARCH && start.getY() + dy <= topY; dy++) {
            BlockPos pos = start.above(dy);
            if (usable(level, pos, store)) {
                return pos;
            }
        }
        for (int r = 1; r <= RING_RADIUS; r++) {
            for (int dy = 0; dy <= RING_UP && start.getY() + dy <= topY; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos pos = start.offset(dx, dy, dz);
                        if (usable(level, pos, store)) {
                            return pos;
                        }
                    }
                }
            }
        }

        int surface = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ()), minY);
        for (int y = surface; y <= topY; y++) {
            BlockPos pos = new BlockPos(start.getX(), y, start.getZ());
            if (usable(level, pos, store)) {
                return pos;
            }
        }

        // Nothing free: replace the block at the clamped spot, but never an existing grave
        BlockPos pos = start;
        while (store.at(level.dimension(), pos).isPresent() && pos.getY() < topY) {
            pos = pos.above();
        }
        return pos;
    }

    // Past the border nobody could reach the grave to open it
    private static boolean usable(ServerLevel level, BlockPos pos, GraveStore store) {
        return level.getBlockState(pos).canBeReplaced() && store.at(level.dimension(), pos).isEmpty()
                && level.getWorldBorder().isWithinBounds(pos);
    }

    // A grave over the void can't be reached, so a column with nothing solid below counts as one
    private static boolean isVoidColumn(ServerLevel level, BlockPos pos) {
        for (int y = pos.getY(); y >= level.getMinY(); y--) {
            BlockPos at = pos.atY(y);
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos reachableSpotNear(ServerLevel level, BlockPos from) {
        int minY = level.getMinY();
        for (int r = 1; r <= VOID_SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = from.getX() + dx;
                    int z = from.getZ() + dz;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    if (top > minY) {
                        return new BlockPos(x, Math.min(top, level.getMaxY() - 1), z);
                    }
                }
            }
        }

        // Nobody has been near enough to load chunks here, so load the spawn column before reading it
        BlockPos spawn = level.dimension() == Level.OVERWORLD ? level.getRespawnData().pos() : BlockPos.ZERO;
        level.getChunk(spawn);
        int top = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()), minY + 1);
        return new BlockPos(spawn.getX(), Math.min(top, level.getMaxY() - 1), spawn.getZ());
    }
}
