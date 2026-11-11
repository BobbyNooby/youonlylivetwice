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

    record Column(int x, int z) {}

    // Only the overworld spawn is randomised, other dimensions keep vanilla spawning
    public static boolean appliesTo(ServerLevel level) {
        return !checkingColumn && Settings.on(c -> c.randomSpawn) && level.dimension() == Level.OVERWORLD;
    }

    /**
     * True when a new player's first spot comes from here rather than the world spawn itself. Vanilla searches around the
     * world spawn for that spot, and the hook answers the search in the overworld; in adventure mode vanilla skips the
     * search and takes the spawn as it is.
     */
    public static boolean placesFirstJoins(MinecraftServer server, ServerLevel spawnLevel) {
        return appliesTo(spawnLevel) && server.getWorldData().getGameType() != GameType.ADVENTURE;
    }

    /** A random column that passes spotAt. If none does, the block above the last column's surface, so a spawn always exists. */
    public static BlockPos pick(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        for (int attempt = 1; ; attempt++) {
            Column column = pickColumn(random, border.getCenterX(), border.getCenterZ(), border.getSize(), Settings.get().worldBorder.diameter);
            BlockPos spot = spotAt(level, column.x(), column.z());
            if (spot != null) {
                return spot;
            }
            if (attempt == ATTEMPTS) {
                // This spot can be in a fluid or over the void
                int y = level.getChunk(SectionPos.blockToSectionCoord(column.x()), SectionPos.blockToSectionCoord(column.z()))
                        .getHeight(Heightmap.Types.WORLD_SURFACE, column.x(), column.z()) + 1;
                BlockPos fallback = new BlockPos(column.x(), y, column.z());
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
     * snow, fire or sweet berry bushes and doesn't look underfoot, so those and magma are refused here.
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

    // Blocks a player can stand in that freeze, burn or prick them
    private static boolean hurts(BlockState state) {
        return state.is(Blocks.POWDER_SNOW) || state.is(BlockTags.FIRE) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    /**
     * A random column inside the smaller of the live border and YOLT's diameter, around the live border's centre.
     * The diameter counts even when YOLT's border isn't applied: a new world picks its spawn before the server starts.
     */
    static Column pickColumn(Random random, double centerX, double centerZ, double borderSize, int diameter) {
        double half = Math.min(borderSize, diameter) / 2;
        return new Column(pickInside(random, centerX, half), pickInside(random, centerZ, half));
    }

    // Only whole blocks inside: from the first block starting at or after the low edge to the last one ending at or
    // before the high edge, so a decimal centre can't put a spawn half a block outside
    private static int pickInside(Random random, double center, double half) {
        int low = (int) Math.ceil(center - half);
        int high = (int) Math.floor(center + half - 1);
        // A border under a block wide holds no whole block, so the block at the centre is used
        if (high < low) {
            return (int) Math.floor(center);
        }
        return low + random.nextInt(high - low + 1);
    }
}
