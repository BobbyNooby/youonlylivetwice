package bobbynooby.dev.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Field;
import java.util.List;

/**
 * One config key: its dotted path, the field behind it and what values it takes. choices are the values to suggest:
 * true and false, an enum's names (the only values it takes) or offline.
 */
public record ConfigKey(String path, Field field, Kind kind, List<String> choices, Applies.When when) {
    // No key is a string today; STRING is kept, with its test, for a later key that is
    public enum Kind { BOOLEAN, INT, DOUBLE, ENUM, STRING, BODY_DURATION }

    /** A text the key can't take. The message is a whole sentence for the admin who typed it. */
    public static final class BadValue extends Exception {
        public BadValue(String message) {
            super(message);
        }
    }

    // The same names and shapes the config file has
    private static final Gson GSON = new Gson();

    /** Reads a typed value the way the file would hold it. Range checks are left to ConfigChecks. */
    public JsonPrimitive parse(String text) throws BadValue {
        return switch (kind) {
            case BOOLEAN -> {
                if (text.equals("true") || text.equals("false")) {
                    yield new JsonPrimitive(Boolean.parseBoolean(text));
                }
                throw bad("true or false", text);
            }
            case INT -> {
                try {
                    yield new JsonPrimitive(Integer.parseInt(text));
                } catch (NumberFormatException e) {
                    throw bad("a whole number", text);
                }
            }
            case DOUBLE -> {
                double value;
                try {
                    value = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw bad("a number", text);
                }
                // 1e400 reads as infinity, which the file can't hold
                if (!Double.isFinite(value)) {
                    throw bad("a number", text);
                }
                yield new JsonPrimitive(value);
            }
            case ENUM -> {
                if (choices.contains(text)) {
                    yield new JsonPrimitive(text);
                }
                throw bad(choiceList(), text);
            }
            case STRING -> new JsonPrimitive(text);
            case BODY_DURATION -> {
                if (text.equals("offline")) {
                    yield new JsonPrimitive(text);
                }
                int minutes;
                try {
                    minutes = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    throw bad("offline or whole minutes of 1 or more", text);
                }
                if (minutes < 1) {
                    throw bad("offline or whole minutes of 1 or more", text);
                }
                yield new JsonPrimitive(minutes);
            }
        };
    }

    /** The value in this config, written as the file has it. */
    public String show(YoltConfig config) {
        JsonElement value = json(config);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    // The value in this config as the file's JSON, so it compares with what parse gives
    JsonElement json(YoltConfig config) {
        Object section = config;
        String[] parts = path.split("\\.");
        try {
            for (int i = 0; i < parts.length - 1; i++) {
                section = section.getClass().getField(parts[i]).get(section);
            }
            return GSON.toJsonTree(field.get(section), field.getGenericType());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read config key " + path, e);
        }
    }

    private BadValue bad(String takes, String text) {
        return new BadValue(path + " takes " + takes + ", not '" + text + "'.");
    }

    // "proximity, self_only or global"
    private String choiceList() {
        if (choices.size() == 1) {
            return choices.getFirst();
        }
        return String.join(", ", choices.subList(0, choices.size() - 1)) + " or " + choices.getLast();
    }
}
