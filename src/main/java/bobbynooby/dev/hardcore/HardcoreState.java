package bobbynooby.dev.hardcore;

import bobbynooby.dev.core.Yolt;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** One player's hidden hardcore state. Plain data, kept in the hardcore store as JSON. */
public final class HardcoreState {
    /** A nutrient not started yet: the player hasn't been given their starting values. */
    public static final double UNSET = -1;
    // Written into every row, so a later build can tell which format it is reading
    static final int VERSION = 1;
    // A NaN that slipped into a value is written as is rather than failing the whole save
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    public boolean legsBroken;
    public double legHealPoints;
    public int curseHeartsLost;
    public int curseLastDeathAdded;
    public long curseOnlineTicks;
    public double protein = UNSET, vitamins = UNSET, sugar = UNSET;
    public double chill;

    public String encode() {
        JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
        json.addProperty("version", VERSION);
        return GSON.toJson(json);
    }

    /** Fields the row doesn't have keep their defaults and unknown ones are skipped; a row that can't be read gives a fresh state. */
    public static HardcoreState decode(String json) {
        try {
            HardcoreState state = GSON.fromJson(json, HardcoreState.class);
            if (state != null) {
                return state;
            }
        } catch (RuntimeException e) {
            Yolt.LOGGER.warn("Could not read a stored hardcore state, starting it fresh: {}", e.toString());
            return new HardcoreState();
        }
        Yolt.LOGGER.warn("A stored hardcore state was empty, starting it fresh");
        return new HardcoreState();
    }

    HardcoreState copy() {
        return decode(encode());
    }
}
