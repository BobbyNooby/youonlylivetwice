package bobbynooby.dev.coords;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The keys are made here rather than taken from Level, whose class needs the registries bootstrapped (see OffsetTest)
class FrameTest {
    private static final ResourceKey<Registry<Level>> DIMENSION = ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("dimension"));
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(DIMENSION, Identifier.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(DIMENSION, Identifier.withDefaultNamespace("the_nether"));
    private static final ResourceKey<Level> END = ResourceKey.create(DIMENSION, Identifier.withDefaultNamespace("the_end"));
    private static final Offset OWN = new Offset(1600, -3200);
    private static final Offset NETHER_AWAY = new Offset(-48_000, 16);
    private static final Offset END_AWAY = new Offset(320, 777_760);

    private static Offset away(ResourceKey<Level> target) {
        return target.equals(NETHER) ? NETHER_AWAY : END_AWAY;
    }

    // A spot in the frame's own dimension takes the frame's offset, and one anywhere else that dimension's away amount,
    // so the frame's own offset never shows on it. A frame with no offset moves nothing anywhere.
    @Test
    void offsetInUsesTheAwayAmountForAnotherDimension() {
        Frame frame = new Frame(OWN, OVERWORLD, FrameTest::away);
        assertEquals(OWN, frame.offsetIn(OVERWORLD));
        assertEquals(NETHER_AWAY, frame.offsetIn(NETHER));
        assertEquals(END_AWAY, frame.offsetIn(END));

        Frame zero = new Frame(Offset.ZERO, OVERWORLD, FrameTest::away);
        for (ResourceKey<Level> dimension : List.of(OVERWORLD, NETHER, END)) {
            assertEquals(Offset.ZERO, zero.offsetIn(dimension), dimension.toString());
            assertEquals(Offset.ZERO, Frame.NONE.offsetIn(dimension), dimension.toString());
        }
    }

    // What the client sends back is taken off with the negated frame, which has to undo the shift in every dimension,
    // the away amounts included, or a click on a spot in another dimension would land somewhere else
    @Test
    void negateUndoesEveryDimension() {
        Frame frame = new Frame(OWN, NETHER, FrameTest::away);
        Frame back = frame.negate();
        BlockPos real = new BlockPos(12_345, 70, -6_789);
        for (ResourceKey<Level> dimension : List.of(OVERWORLD, NETHER, END)) {
            BlockPos shifted = frame.offsetIn(dimension).shift(real);
            assertEquals(real, back.offsetIn(dimension).shift(shifted), dimension.toString());
            assertEquals(frame.offsetIn(dimension).negate(), back.offsetIn(dimension), dimension.toString());
        }
        assertEquals(new Offset(-1600, 3200), back.offsetIn(NETHER));
        assertEquals(new Offset(-320, -777_760), back.offsetIn(END));
    }
}
