package bobbynooby.dev.graves;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The End's boxes, checked without a level. Past 46,341 out on an axis, or 32,768 on a diagonal, a distance squared no
// longer fits in an int, so these are the spots a wrong sum would count as inside.
class EndRebuildsTest {
    // Where the End GameTest's hole was in one CI run, which counted as the exit portal
    private static final int FAR_X = -3_222_127;
    private static final int FAR_Z = 4_872_929;
    // A spike the way vanilla makes the widest and tallest one, 42 out from the podium
    private static final int SPIKE_X = 42;
    private static final int SPIKE_Z = 0;
    private static final int SPIKE_RADIUS = 5;
    private static final int SPIKE_TOP = 103;

    @Test
    void farEndSpotIsInNoBox() {
        for (int y = 0; y < 256; y++) {
            assertInNoBox(new BlockPos(FAR_X, y, FAR_Z));
        }
    }

    @Test
    void farOnEachAxisIsInNoBox() {
        for (int d = 32_768; d <= 65_536; d++) {
            assertInNoBox(new BlockPos(d, 64, 0));
            assertInNoBox(new BlockPos(-d, 64, 0));
            assertInNoBox(new BlockPos(0, 64, d));
            assertInNoBox(new BlockPos(0, 64, -d));
        }
    }

    @Test
    void farOnEachDiagonalIsInNoBox() {
        for (int d = 32_768; d <= 65_536; d++) {
            assertInNoBox(new BlockPos(d, 64, d));
            assertInNoBox(new BlockPos(-d, 64, d));
            assertInNoBox(new BlockPos(d, 64, -d));
            assertInNoBox(new BlockPos(-d, 64, -d));
        }
    }

    // Without these the checks above would pass for boxes that hold nothing at all
    @Test
    void eachBoxHoldsItsOwnSpots() {
        assertTrue(EndRebuilds.inFixedBox(new BlockPos(100, 49, 0)), "arrival platform");
        assertTrue(EndRebuilds.inFixedBox(new BlockPos(1, 200, 2)), "exit portal ring");
        assertTrue(EndRebuilds.inFixedBox(new BlockPos(96, 75, 0)), "first gateway");
        assertTrue(EndRebuilds.inSpikeBox(new BlockPos(SPIKE_X, SPIKE_TOP, SPIKE_Z), SPIKE_X, SPIKE_Z, SPIKE_RADIUS, SPIKE_TOP),
                "spike top");
        assertFalse(EndRebuilds.inFixedBox(EndRebuilds.BESIDE_EXIT_PORTAL.atY(64)), "the End's fallback spot");
        assertFalse(EndRebuilds.inSpikeBox(new BlockPos(SPIKE_X + 11, 64, SPIKE_Z), SPIKE_X, SPIKE_Z, SPIKE_RADIUS, SPIKE_TOP),
                "just past the spike's reach");
    }

    private static void assertInNoBox(BlockPos pos) {
        assertFalse(EndRebuilds.inFixedBox(pos), "in a fixed box at " + pos);
        assertFalse(EndRebuilds.inSpikeBox(pos, SPIKE_X, SPIKE_Z, SPIKE_RADIUS, SPIKE_TOP), "in the spike's box at " + pos);
    }
}
