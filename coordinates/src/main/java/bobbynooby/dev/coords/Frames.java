package bobbynooby.dev.coords;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The offsets a connection is on: out for what the server sends, in for what the client sends back. After a new frame
 * starts, in keeps the old offset, and the old frame's dimension, until the client accepts the teleport with id switchAt.
 */
record Frames(Offset out, Offset in, int switchAt, @Nullable ResourceKey<Level> inDimension) {
    static final int NO_SWITCH = -2;
    static final int AT_NEXT_TELEPORT = -1;
    static final Frames NONE = new Frames(Offset.ZERO, Offset.ZERO, NO_SWITCH, null);
}
