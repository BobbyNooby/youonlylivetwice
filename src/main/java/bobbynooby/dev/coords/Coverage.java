package bobbynooby.dev.coords;

/** What the packet tables do with a packet type, so a test can check that none is left out. */
public enum Coverage {
    /** A translator shifts its positions. */
    TRANSLATED,
    /** It carries no position. */
    NO_POSITIONS,
    /** Its positions are dealt with where they are used, not in the packet, as typed commands are. */
    ELSEWHERE
}
