package bobbynooby.dev.config;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/** How long a body stays: a number of minutes, or "offline" (until the owner is back). */
@JsonAdapter(BodyDuration.Adapter.class)
public record BodyDuration(int minutes) {
    public static final BodyDuration OFFLINE = new BodyDuration(-1);

    public boolean isOffline() {
        return minutes < 0;
    }

    // Reads null for anything invalid so the loader can warn and use the default
    static final class Adapter extends TypeAdapter<BodyDuration> {
        @Override
        public void write(JsonWriter out, BodyDuration value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else if (value.isOffline()) {
                out.value("offline");
            } else {
                out.value(value.minutes());
            }
        }

        @Override
        public BodyDuration read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NUMBER) {
                double value = in.nextDouble();
                return value >= 1 && value <= Integer.MAX_VALUE && value == Math.floor(value)
                        ? new BodyDuration((int) value) : null;
            }
            if (token == JsonToken.STRING) {
                return "offline".equals(in.nextString()) ? OFFLINE : null;
            }
            in.skipValue();
            return null;
        }
    }
}
