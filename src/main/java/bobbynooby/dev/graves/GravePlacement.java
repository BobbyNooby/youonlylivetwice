package bobbynooby.dev.graves;

import bobbynooby.dev.core.Yolt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Picks the block a grave head goes on: near the death spot, never on another grave, always reachable. */
public final class GravePlacement {
    private static final int VOID_SEARCH_RADIUS = 16;
    private static final int UP_SEARCH = 8;
    private static final int RING_RADIUS = 3;
    private static final int RING_UP = 3;
    // How far out a new grave looks for firm ground when nothing close is safe, such as in the middle of a lava lake
    private static final int WIDE_RADIUS = 16;
    // An item is a quarter block wide, so one dropped at a block's centre with no push falls straight down this column
    private static final VoxelShape ITEM_COLUMN = Block.column(4.0, 0.0, 16.0);
    // The replaceable blocks that drop themselves, or sticks, when broken by hand; a head set over one deletes that
    // drop. Grass and ferns only now and then drop a seed, so they stay usable.
    private static final Set<Block> DROPS_ITEMS = Set.of(Blocks.RESIN_CLUMP, Blocks.LEAF_LITTER, Blocks.CRIMSON_ROOTS,
            Blocks.WARPED_ROOTS, Blocks.DEAD_BUSH, Blocks.RED_SHRUB);
    // Every column the void search reaches, nearest first. The sort keeps the scan order for ties, so the pick never varies.
    private static final List<BlockPos> NEAREST_FIRST = nearestFirst(VOID_SEARCH_RADIUS);

    private GravePlacement() {
    }

    /** Where a new grave goes. Always inside the world border, and below a dimension's roof unless the death was above it. */
    public static BlockPos find(ServerLevel level, BlockPos deathPos, GraveStore store) {
        BlockPos start = start(level, deathPos, store);
        return search(level, start, store)
                .or(() -> wideSearch(level, start, store))
                .orElseGet(() -> lastResort(level, start, store));
    }

    /** The spot find would pick near pos, or nothing when only its wider search or last resort is left. */
    static Optional<BlockPos> nearestSafe(ServerLevel level, BlockPos pos, GraveStore store) {
        return search(level, start(level, pos, store), store);
    }

    /**
     * True when a head can go at pos: the block there gives way, holds nothing and drops nothing, the head would be
     * in the way of no block entity, and items dropped there last.
     */
    static boolean fits(ServerLevel level, BlockPos pos, GraveStore store) {
        return free(level, pos, store) && safeForItems(level, pos, store);
    }

    // Everything fits asks except whether dropped items would last
    private static boolean free(ServerLevel level, BlockPos pos, GraveStore store) {
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() && !state.hasBlockEntity() && !DROPS_ITEMS.contains(state.getBlock())
                && !destroysItems(state) && !onBlockEntity(level, pos.below(), store) && !inFrontOfShulkerLid(level, pos);
    }

    // A head right on top of a block entity can be in its way, for example it keeps a shulker box's lid shut. Another
    // grave is fine, so the graves of one death still stack.
    private static boolean onBlockEntity(ServerLevel level, BlockPos floor, GraveStore store) {
        BlockState state = level.getBlockState(floor);
        return state.hasBlockEntity() && !isGrave(level, floor, state, store);
    }

    // A shulker box opens only when the half block in front of its lid is clear, so a head there keeps it shut
    private static boolean inFrontOfShulkerLid(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(side));
            if (state.getBlock() instanceof ShulkerBoxBlock && state.getValue(ShulkerBoxBlock.FACING) == side.getOpposite()) {
                return true;
            }
        }
        return false;
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
            if (holdsItems(level, below, state) && !isGrave(level, below, state, store)) {
                return true;
            }
        }
        // Nothing to land on, so the items would fall out of the world
        return false;
    }

    // A falling item comes to rest on this block
    private static boolean holdsItems(ServerLevel level, BlockPos pos, BlockState state) {
        return Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos), ITEM_COLUMN, BooleanOp.AND);
    }

    // Nothing there to bump into, and no fluid
    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    // Lava and fire burn an item, a cactus breaks it, and an end portal takes it to another dimension
    private static boolean destroysItems(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.LAVA_CAULDRON)
                || state.is(Blocks.CACTUS) || state.is(Blocks.END_PORTAL);
    }

    // Clamped into the world and inside the border, and moved off a column with nothing below to stand on: to the nearest
    // land, else to a fixed spot
    private static BlockPos start(ServerLevel level, BlockPos pos, GraveStore store) {
        BlockPos start = clamped(level, pos);
        return isVoidColumn(level, start) ? nearestLand(level, start, store).orElseGet(() -> fallback(level, start)) : start;
    }

    private static BlockPos clamped(ServerLevel level, BlockPos pos) {
        int y = Math.clamp(pos.getY(), level.getMinY(), level.getMaxY() - 1);
        return insideBorder(level, pos.atY(y));
    }

    // Down from an open spot to the first one over a block that holds items or over a fluid
    private static Optional<BlockPos> landingBelow(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos at = from.mutable();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        while (at.getY() > level.getMinY()) {
            below.setWithOffset(at, Direction.DOWN);
            BlockState state = level.getBlockState(below);
            if (!state.getFluidState().isEmpty() || holdsItems(level, below, state)) {
                return Optional.of(at.immutable());
            }
            at.move(Direction.DOWN);
        }
        return Optional.empty();
    }

    // The highest block a grave may take. With a roof, such as the Nether's bedrock, that is under the roof, where the
    // owner can dig to it, unless the death itself was up on top. Vanilla's portal search stops at the same height.
    private static int topY(ServerLevel level, int fromY) {
        int top = level.getMaxY() - 1;
        int roof = level.getMinY() + level.getLogicalHeight() - 1;
        return fromY <= roof ? Math.min(top, roof) : top;
    }

    private static Optional<BlockPos> search(ServerLevel level, BlockPos start, GraveStore store) {
        int minY = level.getMinY();
        int topY = topY(level, start.getY());
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

        // The surface above whatever blocks the death spot. Under a roof the heightmap is the roof's own top, which the
        // cap leaves out.
        int surface = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ()), minY);
        for (int y = surface; y <= topY; y++) {
            BlockPos pos = start.atY(y);
            if (usable(level, pos, store)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    // Further rings out, from the start's height to a few blocks above it, for the shore of a lava lake or sea
    private static Optional<BlockPos> wideSearch(ServerLevel level, BlockPos start, GraveStore store) {
        int topY = topY(level, start.getY());
        for (int r = RING_RADIUS + 1; r <= WIDE_RADIUS; r++) {
            for (int dy = 0; dy <= UP_SEARCH && start.getY() + dy <= topY; dy++) {
                for (BlockPos pos : ring(start.above(dy), r)) {
                    if (loaded(level, pos) && usable(level, pos, store)) {
                        return Optional.of(pos);
                    }
                }
            }
        }
        return Optional.empty();
    }

    // This far out a spot can be in a chunk that isn't loaded, and reading it would load it on the server thread. The
    // checks also read the blocks beside the spot, which can be in the next chunk.
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!level.isLoaded(pos.relative(side))) {
                return false;
            }
        }
        return true;
    }

    // Nothing near is safe. A safe spot anywhere up the start column comes first, such as a cave above a lava sea.
    // Failing that the first free block of the column, which can be over lava: the grave keeps its items, and
    // removal won't drop them there. Never another grave, never in lava or fire, and never a block that matters.
    private static BlockPos lastResort(ServerLevel level, BlockPos start, GraveStore store) {
        int topY = topY(level, start.getY());
        for (int y = start.getY(); y <= topY; y++) {
            BlockPos pos = start.atY(y);
            if (usable(level, pos, store)) {
                return pos;
            }
        }
        // A column the End rebuilds all the way up still has to take the grave, so its blocks come last
        BlockPos pos = firstFree(level, start, topY, store, true)
                .or(() -> firstFree(level, start, topY, store, false))
                .orElseThrow(() -> new IllegalStateException(
                        "No free block for a grave in the column at " + level.dimension().identifier() + " " + start));
        Yolt.LOGGER.warn("No spot near {} {} keeps a grave's items safe, so the grave goes at {}, over something that "
                + "would destroy them if they were dropped", level.dimension().identifier(), start, pos);
        return pos;
    }

    // Up the column from the start, then down from just below it
    private static Optional<BlockPos> firstFree(ServerLevel level, BlockPos start, int topY, GraveStore store,
                                                boolean outOfRebuilds) {
        for (int y = start.getY(); y <= topY; y++) {
            BlockPos pos = start.atY(y);
            if (freeForNewGrave(level, pos, store, outOfRebuilds)) {
                return Optional.of(pos);
            }
        }
        for (int y = start.getY() - 1; y >= level.getMinY(); y--) {
            BlockPos pos = start.atY(y);
            if (freeForNewGrave(level, pos, store, outOfRebuilds)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    private static boolean freeForNewGrave(ServerLevel level, BlockPos pos, GraveStore store, boolean outOfRebuilds) {
        return free(level, pos, store) && store.at(level.dimension(), pos).isEmpty()
                && !(outOfRebuilds && EndRebuilds.wipes(level, pos));
    }

    // Past the border nobody could reach the grave to open it, and where the End rebuilds, its head would be deleted
    private static boolean usable(ServerLevel level, BlockPos pos, GraveStore store) {
        return level.getWorldBorder().isWithinBounds(pos) && fits(level, pos, store)
                && store.at(level.dimension(), pos).isEmpty() && !EndRebuilds.wipes(level, pos);
    }

    // The nearest block the live border counts as inside: a block is inside when its low corner is at or past the
    // border's low edge and short of its high edge
    private static BlockPos insideBorder(ServerLevel level, BlockPos pos) {
        WorldBorder border = level.getWorldBorder();
        if (border.isWithinBounds(pos)) {
            return pos;
        }
        return new BlockPos(insideOn(pos.getX(), border.getMinX(), border.getMaxX()), pos.getY(),
                insideOn(pos.getZ(), border.getMinZ(), border.getMaxZ()));
    }

    // A border narrower than one block has no block fully inside, so its middle is used
    private static int insideOn(int coordinate, double min, double max) {
        int low = Mth.ceil(min);
        int high = Mth.ceil(max) - 1;
        return low <= high ? Math.clamp(coordinate, low, high) : Mth.floor((min + max) / 2.0);
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

    // The nearest column out to 16 blocks with ground to stand on that items would last on, its own column included.
    // Only loaded chunks are read, since reading one that isn't would load it on the server thread: a column there
    // counts as unknown.
    private static Optional<BlockPos> nearestLand(ServerLevel level, BlockPos from, GraveStore store) {
        WorldBorder border = level.getWorldBorder();
        int cap = topY(level, from.getY());
        for (BlockPos offset : NEAREST_FIRST) {
            int x = from.getX() + offset.getX();
            int z = from.getZ() + offset.getZ();
            if (!border.isWithinBounds(x, z) || !loadedAround(level, x, z)) {
                continue;
            }
            Optional<BlockPos> ground = groundIn(level, x, z, cap).filter(pos -> safeForItems(level, pos, store));
            if (ground.isPresent()) {
                return ground;
            }
        }
        return Optional.empty();
    }

    // The column's chunk and every chunk the search from it reads: its rings and the blocks beside them
    private static boolean loadedAround(ServerLevel level, int x, int z) {
        int reach = RING_RADIUS + 1;
        for (int cx = SectionPos.blockToSectionCoord(x - reach); cx <= SectionPos.blockToSectionCoord(x + reach); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(z - reach); cz <= SectionPos.blockToSectionCoord(z + reach); cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // The highest spot in the column, at or under cap, where someone could stand: open, over a block that holds items or
    // over a fluid. The heightmap skips the open sky, but under a roof it is the roof's own top, so the scan starts no
    // higher than cap.
    private static Optional<BlockPos> groundIn(ServerLevel level, int x, int z, int cap) {
        int top = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), cap);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        // Up past a block the heightmap leaves out though it can be stood on, such as deep snow or a head
        while (pos.getY() < cap && !open(level, pos)) {
            pos.move(Direction.UP);
        }
        if (!open(level, pos)) {
            // At the cap and still in something, which is a roof: down out of it
            pos.setY(top);
            while (pos.getY() > level.getMinY() && !open(level, pos)) {
                pos.move(Direction.DOWN);
            }
        }
        return open(level, pos) ? landingBelow(level, pos) : Optional.empty();
    }

    // No ground near, so one fixed column, whose chunk may be loaded for it. That is the world spawn in the overworld,
    // unless the spawn was set in another dimension; the main island beside the exit portal in the End; else 0, 0.
    private static BlockPos fallback(ServerLevel level, BlockPos from) {
        BlockPos column = insideBorder(level, fallbackColumn(level).atY(from.getY()));
        int x = column.getX();
        int z = column.getZ();
        level.getChunk(column);
        return groundIn(level, x, z, topY(level, from.getY())).orElseGet(() -> {
            // Not even room under the roof, so the column's top as before, roof or not
            int top = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), level.getMinY() + 1);
            return new BlockPos(x, Math.min(top, level.getMaxY() - 1), z);
        });
    }

    private static BlockPos fallbackColumn(ServerLevel level) {
        if (level.dimension() == Level.END) {
            return EndRebuilds.BESIDE_EXIT_PORTAL;
        }
        LevelData.RespawnData spawn = level.getRespawnData();
        return level.dimension() == Level.OVERWORLD && spawn.dimension() == Level.OVERWORLD ? spawn.pos() : BlockPos.ZERO;
    }

    private static List<BlockPos> nearestFirst(int radius) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new BlockPos(dx, 0, dz));
            }
        }
        offsets.sort(Comparator.comparingInt((BlockPos offset) -> offset.getX() * offset.getX() + offset.getZ() * offset.getZ()));
        return List.copyOf(offsets);
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
