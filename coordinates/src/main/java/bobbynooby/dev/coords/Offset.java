package bobbynooby.dev.coords;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.random.RandomGenerator;

/**
 * The shift a player's client sees on X and Z: client = real + offset. Y is never shifted. Both values are whole chunks,
 * so chunk contents stay where they are inside their chunk, and stay under a million blocks, so a host's north compass
 * target keeps inside the world's edge.
 */
public record Offset(int x, int z) {
    public static final int LIMIT = 999_984;
    public static final Offset ZERO = new Offset(0, 0);
    // Every shifted X and Z lands on this grid, 2^-27 of a block. A double far from 0 has coarser steps than one near
    // it, so a real position shifted to near 0 as it is would keep the steps of where it really was, and its unused low
    // bits would tell how far out that is. The grid is the step at the world's edge, so no position anywhere is finer.
    private static final double GRID = 0x1.0p27;

    public Offset {
        if (!fits(x) || !fits(z)) {
            throw new IllegalArgumentException("Offset (" + x + ", " + z + ") is not whole chunks within " + LIMIT);
        }
    }

    // Not Math.abs, which is negative for Integer.MIN_VALUE
    private static boolean fits(int value) {
        return value % 16 == 0 && value >= -LIMIT && value <= LIMIT;
    }

    /** The offset that puts the real spot inside client chunk 0,0, so the first position a player sees says nothing. */
    public static Offset at(double realX, double realZ) {
        return new Offset(toChunkZero(realX), toChunkZero(realZ));
    }

    // In a long, so a far-out spot can't overflow before the clamp
    private static int toChunkZero(double real) {
        long chunkStart = (long) (Mth.floor(real) >> 4) << 4;
        return Math.clamp(-chunkStart, -LIMIT, LIMIT);
    }

    public static Offset random(RandomGenerator random) {
        return new Offset(16 * random.nextInt(-LIMIT / 16, LIMIT / 16 + 1), 16 * random.nextInt(-LIMIT / 16, LIMIT / 16 + 1));
    }

    public boolean isZero() {
        return x == 0 && z == 0;
    }

    public Offset negate() {
        return new Offset(-x, -z);
    }

    public BlockPos shift(BlockPos pos) {
        return pos.offset(x, 0, z);
    }

    public Vec3 shift(Vec3 pos) {
        return new Vec3(shiftX(pos.x), pos.y, shiftZ(pos.z));
    }

    public GlobalPos shift(GlobalPos pos) {
        return GlobalPos.of(pos.dimension(), shift(pos.pos()));
    }

    public ChunkPos shift(ChunkPos pos) {
        return new ChunkPos(shiftChunkX(pos.x()), shiftChunkZ(pos.z()));
    }

    public SectionPos shift(SectionPos pos) {
        return SectionPos.of(shiftChunkX(pos.x()), pos.y(), shiftChunkZ(pos.z()));
    }

    public double shiftX(double realX) {
        return onGrid(realX + x);
    }

    public double shiftZ(double realZ) {
        return onGrid(realZ + z);
    }

    // Exact for anything inside the world, where a double's own step is no coarser than the grid's. Past that, and for
    // a value that isn't a number, there is nothing finer to hide.
    private static double onGrid(double value) {
        double scaled = value * GRID;
        return Math.abs(scaled) < 0x1.0p52 ? Math.rint(scaled) / GRID : value;
    }

    public int shiftChunkX(int chunkX) {
        return chunkX + (x >> 4);
    }

    public int shiftChunkZ(int chunkZ) {
        return chunkZ + (z >> 4);
    }

    // The sound packet sends positions as eighths of a block, so whole blocks shift it exactly
    public int shiftFixedX(int eighths) {
        return eighths + 8 * x;
    }

    public int shiftFixedZ(int eighths) {
        return eighths + 8 * z;
    }
}
