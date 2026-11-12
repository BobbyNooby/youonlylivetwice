package bobbynooby.dev.coords;

/**
 * The offsets a connection is on: out for what the server sends, in for what the client sends back. After a new frame
 * starts, in keeps the old offset until the client accepts the teleport with id switchAt.
 */
record Frames(Offset out, Offset in, int switchAt) {
    static final int NO_SWITCH = -2;
    static final int AT_NEXT_TELEPORT = -1;
    static final Frames NONE = new Frames(Offset.ZERO, Offset.ZERO, NO_SWITCH);
}
