package bobbynooby.dev.shared;

import java.util.Map;

/** The value read for each key, by key name. */
public final class Values {
    private final Map<String, Object> values;

    Values(Map<String, Object> values) {
        this.values = values;
    }

    /** The value of a {@link Key#flag} key. */
    public boolean flag(String name) {
        return value(name, Boolean.class);
    }

    /** The value of a {@link Key#whole} key. */
    public int whole(String name) {
        return value(name, Integer.class);
    }

    /** The value of a {@link Key#number} key. */
    public double number(String name) {
        return value(name, Double.class);
    }

    // A name that isn't a key of that kind is a mistake in the mod, not in the file, so it stops the read loudly
    private <T> T value(String name, Class<T> kind) {
        Object value = values.get(name);
        if (!kind.isInstance(value)) {
            throw new IllegalArgumentException("the file has no " + kind.getSimpleName() + " key called " + name);
        }
        return kind.cast(value);
    }
}
