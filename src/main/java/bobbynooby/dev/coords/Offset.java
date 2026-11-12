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
 * so chunk contents stay where they are inside their chunk, and stay under a million blocks, so the north compass's
 * target keeps inside the world's edge.
 */
public record Offset(int x, int z) {
    public static final int LIMIT = 999_984;
    public static final Offset ZERO = new Offset(0, 0);

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
        return new Vec3(pos.x + x, pos.y, pos.z + z);
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
        return realX + x;
    }

    public double shiftZ(double realZ) {
        return realZ + z;
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
