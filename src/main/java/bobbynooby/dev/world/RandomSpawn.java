package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.mixin.PlayerSpawnFinderInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.Random;

import static bobbynooby.dev.core.Yolt.LOGGER;

/**
 * Picks a random spot inside the world border for new and respawning players. The search runs on the server thread,
 * and a column in an ungenerated chunk is generated on the spot, so pre-generating the world keeps it quick.
 */
public final class RandomSpawn {
    private RandomSpawn() {}

    static final int ATTEMPTS = 50;
    private static final Random DEFAULT_RANDOM = new Random();
    // A test can swap in a seeded one from its own thread
    private static volatile Random random = DEFAULT_RANDOM;
    // Set while spotAt runs vanilla's column check, which is the method our hook replaces, so the hook lets it through.
    // Spawn searches only run on the server thread.
    private static boolean checkingColumn;
    // The tick whose search found no ground, so the rest of it is vanilla's. Spawn searches only run on the server thread.
    private static int noGroundTick = -1;

    record Column(int x, int z) {}

    /** Where random spawn picks: whole blocks from each min edge up to each max edge. */
    public record Area(double minX, double maxX, double minZ, double maxZ) {}

    // Only the overworld spawn is randomised, other dimensions keep vanilla spawning
    public static boolean appliesTo(ServerLevel level) {
        return !checkingColumn && Settings.on(c -> c.randomSpawn) && level.dimension() == Level.OVERWORLD
                && level.getServer().getTickCount() != noGroundTick;
    }

    /**
     * True when a new player's first spot comes from here rather than the world spawn itself. Vanilla searches around the
     * world spawn for that spot, and the hook answers the search in the overworld; in adventure mode vanilla skips the
     * search and takes the spawn as it is.
     */
    public static boolean placesFirstJoins(MinecraftServer server, ServerLevel spawnLevel) {
        return appliesTo(spawnLevel) && server.getWorldData().getGameType() != GameType.ADVENTURE;
    }

    /**
     * A random column that passes spotAt. If none does, the block above the last column's surface, so a spawn exists
     * wherever there is ground. Null when that column has no ground at all: the search is then vanilla's for the rest
     * of this tick, which ends it at the world spawn.
     */
    public static @Nullable BlockPos pick(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        return pickIn(level, area(border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ(),
                border.getCenterX(), border.getCenterZ(), Settings.get().worldBorder.diameter));
    }

    /** The same inside the given area. Public for the tests, which give it an area of their own. */
    public static @Nullable BlockPos pickIn(ServerLevel level, Area area) {
        for (int attempt = 1; ; attempt++) {
            Column column = pickColumn(random, area);
            BlockPos spot = spotAt(level, column.x(), column.z());
            if (spot != null) {
                return spot;
            }
            if (attempt == ATTEMPTS) {
                // This spot can be in a fluid
                int y = level.getChunk(SectionPos.blockToSectionCoord(column.x()), SectionPos.blockToSectionCoord(column.z()))
                        .getHeight(Heightmap.Types.WORLD_SURFACE, column.x(), column.z()) + 1;
                BlockPos fallback = new BlockPos(column.x(), y, column.z());
                // An empty column, as on a void map: a spot over the void kills, so vanilla's own spawn is better
                if (y <= level.getMinY()) {
                    LOGGER.warn("No safe random spawn found in {} columns, and the last one has no ground, so vanilla picks the spawn",
                            ATTEMPTS);
                    noGroundTick = level.getServer().getTickCount();
                    return null;
                }
                LOGGER.warn("No safe random spawn found in {} columns, using {}", ATTEMPTS, fallback.toShortString());
                return fallback;
            }
        }
    }

    /**
     * For the tests only: columns come from this generator from now on, so a seeded one gives the same spot every run.
     * Null puts the default one back.
     */
    public static void useRandomForTesting(@Nullable Random testRandom) {
        random = testRandom == null ? DEFAULT_RANDOM : testRandom;
    }

    /**
     * Where a player could spawn in this column, or null. Runs the check vanilla gives each spawn candidate: standing on a
     * block with a full top face, not in a fluid, with room for a player. Vanilla's check sees no collision in powder
     * snow, fire, sweet berry bushes or wither roses and doesn't look underfoot, so those and magma are refused here.
     */
    public static @Nullable BlockPos spotAt(ServerLevel level, int x, int z) {
        BlockPos spot;
        checkingColumn = true;
        try {
            spot = PlayerSpawnFinderInvoker.yoltLevelRespawnPos(level, x, z);
        } finally {
            checkingColumn = false;
        }
        if (spot == null || !PlayerSpawnFinderInvoker.yoltNoCollisionNoLiquid(level, spot)) {
            return null;
        }
        if (hurts(level.getBlockState(spot)) || hurts(level.getBlockState(spot.above()))
                || level.getBlockState(spot.below()).is(Blocks.MAGMA_BLOCK)) {
            return null;
        }
        return spot;
    }

    // Blocks a player can stand in that freeze, burn, prick or wither them
    private static boolean hurts(BlockState state) {
        return state.is(Blocks.POWDER_SNOW) || state.is(BlockTags.FIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }

    /**
     * The square YOLT's diameter makes around the live border's centre, cut down to the border's real edges. The
     * diameter counts even when YOLT's border isn't applied: a new world picks its spawn before the server starts.
     * The edges count because max-world-size can pull them in from the border's own size.
     */
    static Area area(double minX, double maxX, double minZ, double maxZ, double centerX, double centerZ, int diameter) {
        double half = diameter / 2.0;
        return new Area(Math.max(minX, centerX - half), Math.min(maxX, centerX + half),
                Math.max(minZ, centerZ - half), Math.min(maxZ, centerZ + half));
    }

    static Column pickColumn(Random random, Area area) {
        return new Column(pickInside(random, area.minX(), area.maxX()), pickInside(random, area.minZ(), area.maxZ()));
    }

    // Only whole blocks inside: from the first block starting at or after the low edge to the last one ending at or
    // before the high edge, so a decimal centre can't put a spawn half a block outside
    private static int pickInside(Random random, double lowEdge, double highEdge) {
        int low = (int) Math.ceil(lowEdge);
        int high = (int) Math.floor(highEdge - 1);
        // An area under a block wide holds no whole block, so the block at its middle is used
        if (high < low) {
            return (int) Math.floor((lowEdge + highEdge) / 2);
        }
        return low + random.nextInt(high - low + 1);
    }
}
