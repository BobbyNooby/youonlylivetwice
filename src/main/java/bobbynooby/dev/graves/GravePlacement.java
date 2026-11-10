package bobbynooby.dev.graves;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Picks the block a grave head goes on: near the death spot, never on another grave, always reachable. */
public final class GravePlacement {
    private static final int VOID_SEARCH_RADIUS = 16;
    private static final int UP_SEARCH = 8;
    private static final int RING_RADIUS = 3;
    private static final int RING_UP = 3;
    // An item is a quarter block wide, so one dropped at a block's centre with no push falls straight down this column
    private static final VoxelShape ITEM_COLUMN = Block.column(4.0, 0.0, 16.0);
    // The replaceable blocks that drop themselves, or sticks, when broken by hand; a head set over one deletes that
    // drop. Grass and ferns only now and then drop a seed, so they stay usable.
    private static final Set<Block> DROPS_ITEMS = Set.of(Blocks.RESIN_CLUMP, Blocks.LEAF_LITTER, Blocks.CRIMSON_ROOTS,
            Blocks.WARPED_ROOTS, Blocks.DEAD_BUSH, Blocks.RED_SHRUB);

    private GravePlacement() {
    }

    public static BlockPos find(ServerLevel level, BlockPos deathPos, GraveStore store) {
        BlockPos start = start(level, deathPos);
        return search(level, start, store).orElseGet(() -> lastResort(level, start, store));
    }

    /** The spot find would pick near pos, or nothing when only its last resort is left. */
    static Optional<BlockPos> nearestSafe(ServerLevel level, BlockPos pos, GraveStore store) {
        return search(level, start(level, pos), store);
    }

    /**
     * True when a head can go at pos: the block there gives way, holds nothing and drops nothing, nothing below it
     * could be blocked by the head, and items dropped there last.
     */
    static boolean fits(ServerLevel level, BlockPos pos, GraveStore store) {
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() && !state.hasBlockEntity() && !DROPS_ITEMS.contains(state.getBlock())
                && !onBlockEntity(level, pos.below(), store) && safeForItems(level, pos, store);
    }

    // A head right on top of a block entity can be in its way, for example it keeps a shulker box's lid shut. Another
    // grave is fine, so the graves of one death still stack.
    private static boolean onBlockEntity(ServerLevel level, BlockPos floor, GraveStore store) {
        BlockState state = level.getBlockState(floor);
        return state.hasBlockEntity() && !isGrave(level, floor, state, store);
    }

    private static boolean isGrave(ServerLevel level, BlockPos pos, BlockState state, GraveStore store) {
        return state.is(Blocks.PLAYER_HEAD) && store.at(level.dimension(), pos.immutable()).isPresent();
    }

    /**
     * True when stacks dropped at the centre of pos come to rest without being destroyed. Whatever stands at pos
     * itself is replaced by the head or cleared with it, so there only something that destroys items counts.
     */
    static boolean safeForItems(ServerLevel level, BlockPos pos, GraveStore store) {
        if (destroysItems(level.getBlockState(pos))) {
            return false;
        }
        BlockPos.MutableBlockPos below = pos.mutable();
        while (below.move(Direction.DOWN).getY() >= level.getMinY()) {
            BlockState state = level.getBlockState(below);
            if (destroysItems(state)) {
                return false;
            }
            // A grave's head goes when the grave does, so it is no floor
            if (Shapes.joinIsNotEmpty(state.getCollisionShape(level, below), ITEM_COLUMN, BooleanOp.AND)
                    && !isGrave(level, below, state, store)) {
                return true;
            }
        }
        // Nothing to land on, so the items would fall out of the world
        return false;
    }

    // Lava and fire burn an item, a cactus breaks it, and an end portal takes it to another dimension
    private static boolean destroysItems(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.LAVA_CAULDRON)
                || state.is(Blocks.CACTUS) || state.is(Blocks.END_PORTAL);
    }

    // Clamped into the world, and moved off a column with nothing below to stand on
    private static BlockPos start(ServerLevel level, BlockPos pos) {
        BlockPos start = new BlockPos(pos.getX(), Math.clamp(pos.getY(), level.getMinY(), level.getMaxY() - 1), pos.getZ());
        return isVoidColumn(level, start) ? reachableSpotNear(level, start) : start;
    }

    private static Optional<BlockPos> search(ServerLevel level, BlockPos start, GraveStore store) {
        int minY = level.getMinY();
        int topY = level.getMaxY() - 1;
        if (usable(level, start, store)) {
            return Optional.of(start);
        }
        for (int dy = 1; dy <= UP_SEARCH && start.getY() + dy <= topY; dy++) {
            BlockPos pos = start.above(dy);
            if (usable(level, pos, store)) {
                return Optional.of(pos);
            }
        }
        for (int r = 1; r <= RING_RADIUS; r++) {
            for (int dy = 0; dy <= RING_UP && start.getY() + dy <= topY; dy++) {
                for (BlockPos pos : ring(start.above(dy), r)) {
                    if (usable(level, pos, store)) {
                        return Optional.of(pos);
                    }
                }
            }
        }

        int surface = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ()), minY);
        for (int y = surface; y <= topY; y++) {
            BlockPos pos = new BlockPos(start.getX(), y, start.getZ());
            if (usable(level, pos, store)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    // Nothing free: replace the block at the start spot, but never an existing grave, and never lava or fire
    private static BlockPos lastResort(ServerLevel level, BlockPos start, GraveStore store) {
        int topY = level.getMaxY() - 1;
        BlockPos pos = start;
        while ((store.at(level.dimension(), pos).isPresent() || destroysItems(level.getBlockState(pos))) && pos.getY() < topY) {
            pos = pos.above();
        }
        return pos;
    }

    // Past the border nobody could reach the grave to open it
    private static boolean usable(ServerLevel level, BlockPos pos, GraveStore store) {
        return fits(level, pos, store) && store.at(level.dimension(), pos).isEmpty()
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
            for (BlockPos column : ring(from, r)) {
                int x = column.getX();
                int z = column.getZ();
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (top > minY) {
                    return new BlockPos(x, Math.min(top, level.getMaxY() - 1), z);
                }
            }
        }

        // Nobody has been near enough to load chunks here, so load the spawn column before reading it
        BlockPos spawn = level.dimension() == Level.OVERWORLD ? level.getRespawnData().pos() : BlockPos.ZERO;
        level.getChunk(spawn);
        int top = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()), minY + 1);
        return new BlockPos(spawn.getX(), Math.min(top, level.getMaxY() - 1), spawn.getZ());
    }

    // Every block exactly r out from the centre on its layer, always in the same order
    private static List<BlockPos> ring(BlockPos centre, int r) {
        List<BlockPos> ring = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                    ring.add(centre.offset(dx, 0, dz));
                }
            }
        }
        return ring;
    }
}
