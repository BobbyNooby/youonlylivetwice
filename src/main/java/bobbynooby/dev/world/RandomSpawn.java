package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.hardcore.Cold;
import bobbynooby.dev.mixin.PlayerSpawnFinderInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Random;

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
    // After a search that found no ground, the rest of it is vanilla's for this many ticks: vanilla tries its candidates
    // one chunk load at a time, which can run past a tick, and each would otherwise try every column again
    public static final int NO_GROUND_TICKS = 100;
    // The server whose search found no ground and the tick its skip ends. Spawn searches only run on the server thread.
    private static @Nullable MinecraftServer noGroundServer;
    private static int noGroundUntil;

    record Column(int x, int z) {}

    /** Where random spawn picks: whole blocks from each min edge up to each max edge. */
    public record Area(double minX, double maxX, double minZ, double maxZ) {}

    // Only the overworld spawn is randomised, other dimensions keep vanilla spawning
    public static boolean appliesTo(ServerLevel level) {
        return !checkingColumn && Settings.on(c -> c.randomSpawn) && level.dimension() == Level.OVERWORLD
                && !(level.getServer() == noGroundServer && level.getServer().getTickCount() < noGroundUntil);
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
     * wherever there is ground. Null when that column has no ground at all: the search is then vanilla's for the next
     * NO_GROUND_TICKS ticks, which ends it at the world spawn.
     */
    public static @Nullable BlockPos pick(ServerLevel level) {
        return pickIn(level, liveArea(level));
    }

    /** True when this spot's column is one a pick could give now, inside the live border and YOLT's diameter. */
    public static boolean insidePickArea(ServerLevel level, Vec3 spot) {
        return canPick(liveArea(level), Mth.floor(spot.x), Mth.floor(spot.z));
    }

    /**
     * Where a player joining for the first time is placed. Their spot is picked as they log in, and they are placed
     * there only once their chunks have loaded and their client is ready. In between they aren't online for the border
     * commands to check, so a border changed meanwhile can leave the spot outside. A spot a pick could no longer give is
     * picked again. If no new spot is found, they are placed at the old one, as vanilla would place them.
     */
    public static Vec3 firstJoinSpot(ServerLevel level, Vec3 picked, String name) {
        if (!placesFirstJoins(level.getServer(), level) || insidePickArea(level, picked)) {
            return picked;
        }
        BlockPos from = BlockPos.containing(picked);
        BlockPos spot = pick(level);
        if (spot == null) {
            Yolt.LOGGER.warn("{}'s first spawn at {} is outside the random spawn area now, and no new spot was found, so they"
                    + " join there", name, from.toShortString());
            return picked;
        }
        Yolt.LOGGER.info("Moved {}'s first spawn from {} to {}, since the old spot is outside the random spawn area now", name,
                from.toShortString(), spot.toShortString());
        return Vec3.atBottomCenterOf(spot);
    }

    // The area a pick reads from the live border and the config when it starts
    private static Area liveArea(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        return area(border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ(),
                border.getCenterX(), border.getCenterZ(), Settings.get().worldBorder.diameter);
    }

    /** The same inside the given area. Public for the tests, which give it an area of their own. */
    public static @Nullable BlockPos pickIn(ServerLevel level, Area area) {
        return pickIn(level, area, true);
    }

    // With the hardcore cold on, a spot cold enough to snow is refused too. When no try was taken and the cold refused
    // at least one safe spot, as in a snowy world, the normal search runs once more: a safe cold spawn beats the
    // last-resort surface, which can be in a fluid.
    private static @Nullable BlockPos pickIn(ServerLevel level, Area area, boolean refuseCold) {
        boolean sawCold = false;
        for (int attempt = 1; ; attempt++) {
            Column column = pickColumn(random, area);
            BlockPos spot = spotAt(level, column.x(), column.z());
            if (spot != null) {
                if (!(refuseCold && Cold.refusesSpawn(level, spot))) {
                    return spot;
                }
                sawCold = true;
            }
            if (attempt == ATTEMPTS && sawCold) {
                return pickIn(level, area, false);
            }
            if (attempt == ATTEMPTS) {
                // This spot can be in a fluid
                int y = level.getChunk(SectionPos.blockToSectionCoord(column.x()), SectionPos.blockToSectionCoord(column.z()))
                        .getHeight(Heightmap.Types.WORLD_SURFACE, column.x(), column.z()) + 1;
                BlockPos fallback = new BlockPos(column.x(), y, column.z());
                // An empty column, as on a void map: a spot over the void kills, so vanilla's own spawn is better. The
                // line says nothing of what comes next, which is the caller's: vanilla's search, or the first join
                // check keeping the spot it had, which says so itself.
                if (y <= level.getMinY()) {
                    Yolt.LOGGER.warn("No safe random spawn found in {} columns, and the last one has no ground", ATTEMPTS);
                    noGroundServer = level.getServer();
                    noGroundUntil = level.getServer().getTickCount() + NO_GROUND_TICKS;
                    return null;
                }
                Yolt.LOGGER.warn("No safe random spawn found in {} columns, using {}", ATTEMPTS, fallback.toShortString());
                return fallback;
            }
        }
    }

    /** For the tests only: random spawn answers again at once, so a failed test can't leave it off for the next ones. */
    public static void endNoGroundForTesting() {
        noGroundServer = null;
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

    // Draws only among whole blocks: the middle block of a narrower area is taken without a draw
    private static int pickInside(Random random, double lowEdge, double highEdge) {
        Span span = Span.between(lowEdge, highEdge);
        return span.whole() ? span.low() + random.nextInt(span.high() - span.low() + 1) : span.low();
    }

    // True for a column pickColumn can give in this area
    static boolean canPick(Area area, int x, int z) {
        return canPickInside(x, area.minX(), area.maxX()) && canPickInside(z, area.minZ(), area.maxZ());
    }

    private static boolean canPickInside(int block, double lowEdge, double highEdge) {
        Span span = Span.between(lowEdge, highEdge);
        return span.low() <= block && block <= span.high();
    }

    // The blocks a pick can give between two edges. Only whole blocks inside: from the first block starting at or after
    // the low edge to the last one ending at or before the high edge, so a decimal centre can't put a spawn half a block
    // outside. An area under a block wide holds no whole block, so it is the block at its middle alone.
    private record Span(int low, int high, boolean whole) {
        static Span between(double lowEdge, double highEdge) {
            int low = (int) Math.ceil(lowEdge);
            int high = (int) Math.floor(highEdge - 1);
            if (high < low) {
                int middle = (int) Math.floor((lowEdge + highEdge) / 2);
                return new Span(middle, middle, false);
            }
            return new Span(low, high, true);
        }
    }
}
