package bobbynooby.dev.coords;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// GlobalPos is left to the GameTests: its codec loads Level, whose particles need the registries bootstrapped
class OffsetTest {
    @Test
    void atPutsTheAnchorInsideChunkZero() {
        Offset offset = Offset.at(11_207.5, -633.5);
        assertEquals(new Offset(-11_200, 640), offset);
        assertEquals(new BlockPos(7, 64, 6), offset.shift(new BlockPos(11_207, 64, -634)));
    }

    @Test
    void atRoundsNegativeCoordinatesDown() {
        assertEquals(new Offset(16, 16), Offset.at(-0.5, -16.0));
    }

    @Test
    void atClampsAtTheLimit() {
        assertEquals(new Offset(-999_984, 999_984), Offset.at(2_000_008.5, -3_000_000.0));
    }

    // The last chunk on each side whose start is the limit itself, and the first one past it, which clamps to the limit
    @Test
    void atClampsFromTheFirstChunkPastTheLimit() {
        assertEquals(new Offset(-999_984, 0), Offset.at(999_999.5, 0.0));
        assertEquals(new Offset(-999_984, 0), Offset.at(1_000_000.5, 0.0));
        assertEquals(new Offset(0, 999_984), Offset.at(0.0, -999_984.0));
        assertEquals(new Offset(0, 999_984), Offset.at(0.0, -999_984.5));
        assertEquals(new Offset(999_984, 0), Offset.at(-999_984.0, 0.0));
        assertEquals(new Offset(999_984, 0), Offset.at(-999_984.5, 0.0));
        assertEquals(new Offset(0, -999_984), Offset.at(0.0, 999_999.5));
        assertEquals(new Offset(0, -999_984), Offset.at(0.0, 1_000_000.5));
    }

    @Test
    void randomStaysOnTheGridUnderTheLimit() {
        Random random = new Random(1);
        boolean[] seen = new boolean[4];
        for (int i = 0; i < 10_000; i++) {
            Offset offset = Offset.random(random);
            assertEquals(0, offset.x() % 16, "x on the grid");
            assertEquals(0, offset.z() % 16, "z on the grid");
            assertTrue(Math.abs(offset.x()) <= 999_984 && Math.abs(offset.z()) <= 999_984, "under the limit: " + offset);
            seen[offset.x() < 0 ? 0 : 1] = true;
            seen[offset.z() < 0 ? 2 : 3] = true;
        }
        assertTrue(seen[0] && seen[1] && seen[2] && seen[3], "both signs on each axis");
    }

    @Test
    void constructorRefusesOffGridAndTooFar() {
        assertThrows(IllegalArgumentException.class, () -> new Offset(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new Offset(0, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new Offset(1_000_000, 0));
    }

    // A multiple of 16 whose Math.abs is negative
    @Test
    void constructorRefusesMinValue() {
        assertThrows(IllegalArgumentException.class, () -> new Offset(Integer.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Offset(0, Integer.MIN_VALUE));
    }

    @Test
    void shiftAndNegateRoundTrip() {
        Offset offset = new Offset(-4_096, 2_048);
        Offset back = offset.negate();
        BlockPos block = new BlockPos(123, -40, -987);
        assertEquals(block, back.shift(offset.shift(block)));
        assertEquals(-40, offset.shift(block).getY());
        Vec3 vec = new Vec3(12.25, 70.5, -3.75);
        assertEquals(vec, back.shift(offset.shift(vec)));
        assertEquals(70.5, offset.shift(vec).y);
        ChunkPos chunk = new ChunkPos(-7, 31);
        assertEquals(chunk, back.shift(offset.shift(chunk)));
        assertEquals(new ChunkPos(3 - 256, 4 + 128), offset.shift(new ChunkPos(3, 4)));
        SectionPos section = SectionPos.of(5, -2, -9);
        assertEquals(section, back.shift(offset.shift(section)));
        assertEquals(SectionPos.of(5 - 256, -2, -9 + 128), offset.shift(section));
    }

    @Test
    void fixedPointShiftIsExactFarOut() {
        Offset offset = new Offset(-999_984, 999_984);
        assertEquals(8_000_128, offset.shiftFixedX(16_000_000));
        assertEquals(-8_000_128, offset.shiftFixedZ(-16_000_000));
    }

    // Entities get rebuilt through the float constructor, so a packed rotation has to come back as the same byte
    @Test
    void packedRotationsSurviveTheRoundTrip() {
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte packed = (byte) i;
            assertEquals(packed, Mth.packDegrees(Mth.unpackDegrees(packed)), "byte " + i);
        }
    }
}
