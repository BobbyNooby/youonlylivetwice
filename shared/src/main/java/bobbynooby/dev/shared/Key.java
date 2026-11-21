package bobbynooby.dev.shared;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * One key of a settings file: its name, the rule its value must pass, and where a settings record keeps it. A value the
 * rule refuses gives one line, {@code <name> must be <rule>, kept <value>}, and the value it had is kept.
 *
 * @param <S> the settings record the file holds
 */
public final class Key<S> {
    private final String name;
    private final String rule;
    // The value a JSON primitive gives, or null when the rule refuses it
    private final Function<JsonPrimitive, @Nullable Object> accept;
    private final Function<S, Object> get;

    private Key(String name, String rule, Function<JsonPrimitive, @Nullable Object> accept, Function<S, Object> get) {
        this.name = name;
        this.rule = rule;
        this.accept = accept;
        this.get = get;
    }

    /** A key that is true or false. */
    public static <S> Key<S> flag(String name, Predicate<S> get) {
        return new Key<>(name, "true or false", value -> value.isBoolean() ? value.getAsBoolean() : null, get::test);
    }

    /** A key that is a whole number from min to max. A whole 5.0 counts as 5. */
    public static <S> Key<S> whole(String name, int min, int max, ToIntFunction<S> get) {
        return new Key<>(name, "a whole number from " + min + " to " + max, value -> {
            double number = value.isNumber() ? value.getAsDouble() : Double.NaN;
            // NaN equals nothing, so it fails the first test
            return number == Math.rint(number) && number >= min && number <= max ? Integer.valueOf((int) number) : null;
        }, get::applyAsInt);
    }

    /**
     * A key that is a number, which allowed must pass. rule puts allowed into words for the refusal line: "a number from
     * 0 to 1" gives {@code must be a number from 0 to 1}. A value that isn't a number reaches allowed as NaN, which a
     * range check refuses.
     */
    public static <S> Key<S> number(String name, DoublePredicate allowed, String rule, ToDoubleFunction<S> get) {
        return new Key<>(name, rule, value -> {
            double number = value.isNumber() ? value.getAsDouble() : Double.NaN;
            return allowed.test(number) ? Double.valueOf(number) : null;
        }, get::applyAsDouble);
    }

    public String name() {
        return name;
    }

    // The value in json, or the fallback's when the key is missing or refused. An absent or null key counts as missing.
    Object read(JsonObject json, S fallback, List<String> refused, List<String> missing) {
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) {
            missing.add(name);
            return get.apply(fallback);
        }
        // An object or array where a value belongs is a primitive of no type, which every rule refuses
        JsonPrimitive primitive = element.isJsonPrimitive() ? element.getAsJsonPrimitive() : new JsonPrimitive("");
        Object value = accept.apply(primitive);
        if (value == null) {
            Object kept = get.apply(fallback);
            refused.add(name + " must be " + rule + ", kept " + kept);
            return kept;
        }
        return value;
    }

    // A value is a Boolean, an Integer or a Double, as the three kinds of key box them
    JsonPrimitive json(S settings) {
        Object value = get.apply(settings);
        return value instanceof Boolean flag ? new JsonPrimitive(flag) : new JsonPrimitive((Number) value);
    }
}
