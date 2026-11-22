package bobbynooby.dev.coords;

/** What the coordinate offset is set to: on, each player's client is sent its own shifted X and Z. */
public record CoordinateSettings(boolean enabled) {
    public static final CoordinateSettings DEFAULTS = new CoordinateSettings(true);

    /** The line /yolt-coordinates shows, and /yolt-coordinates reload after it reads the file. */
    public String describe() {
        return enabled ? "Coordinate offset on: each player's X and Z are shifted" : "The coordinate offset is off";
    }
}
