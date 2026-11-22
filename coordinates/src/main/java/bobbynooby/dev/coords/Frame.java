package bobbynooby.dev.coords;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * What the rules shift positions by for one client: the frame's offset for a spot in the frame's own dimension, and for a
 * spot in any other dimension that dimension's away amount. A spot elsewhere has no place in the frame, and the frame's
 * offset is easy to learn (the End's always is), so it would give the spot away.
 */
public record Frame(Offset offset, @Nullable ResourceKey<Level> dimension, Function<ResourceKey<Level>, Offset> away) {
    public static final Frame NONE = new Frame(Offset.ZERO, null, target -> Offset.ZERO);

    /** Nothing moves: no frame shifts anything, in any dimension. */
    public boolean isZero() {
        return offset.isZero();
    }

    /** What a spot in this dimension is shifted by. */
    public Offset offsetIn(ResourceKey<Level> target) {
        return offset.isZero() || target.equals(dimension) ? offset : away.apply(target);
    }

    public GlobalPos shift(GlobalPos pos) {
        return offsetIn(pos.dimension()).shift(pos);
    }

    /** The frame that takes the shift off again, for what the client sends back. */
    public Frame negate() {
        return new Frame(offset.negate(), dimension, target -> away.apply(target).negate());
    }
}
