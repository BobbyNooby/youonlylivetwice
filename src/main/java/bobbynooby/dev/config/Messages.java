package bobbynooby.dev.config;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills in and checks the texts of the messages section, each with the placeholders its @Placeholders names. */
public final class Messages {
    // A line of chat or a kick screen's worth, as a tab header or MOTD line is
    static final int MAX_LENGTH = 256;
    private static final String SECTION = "messages.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private Messages() {}

    /**
     * The text with each {name} in names (name, value, name, value...) filled in, in one pass, so a value that holds
     * braces, such as a death message naming a mob called {x}, is never filled in again. Any other {word} stays as typed.
     */
    public static String fill(String text, String... names) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < names.length; i += 2) {
            values.put(names[i], names[i + 1]);
        }
        return PLACEHOLDER.matcher(text).replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }

    /** The section's texts in the order the file has them, so a new one is checked without more code. */
    static List<Field> texts() {
        List<Field> texts = new ArrayList<>();
        for (Field field : YoltConfig.MessagesSection.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                texts.add(field);
            }
        }
        return texts;
    }

    /** Why this text can't be used: too long, or without a placeholder it must keep. Null when it is fine. */
    static @Nullable String problem(Field text, @Nullable String value) {
        if (value == null) {
            return "must be text";
        }
        if (value.length() > MAX_LENGTH) {
            return "must be at most " + MAX_LENGTH + " characters";
        }
        Placeholders placeholders = text.getAnnotation(Placeholders.class);
        if (placeholders == null) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (String required : placeholders.required()) {
            if (!value.contains("{" + required + "}")) {
                missing.add("{" + required + "}");
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        String listed = missing.size() == 1 ? missing.getFirst()
                : String.join(", ", missing.subList(0, missing.size() - 1)) + " and " + missing.getLast();
        return "must keep " + listed;
    }

    /**
     * A notice for each {word} in a text that isn't one of its placeholders, most likely a misspelt one. It is sent as
     * typed, so this is only said, never refused: "messages.graveAt: has {dim}, which it doesn't fill in".
     */
    public static List<String> unknownPlaceholders(YoltConfig config) {
        List<String> notices = new ArrayList<>();
        for (Field text : texts()) {
            Placeholders placeholders = text.getAnnotation(Placeholders.class);
            List<String> known = placeholders == null ? List.of() : List.of(placeholders.value());
            String value = (String) get(text, config.messages);
            if (value == null) {
                continue;
            }
            Matcher match = PLACEHOLDER.matcher(value);
            while (match.find()) {
                if (!known.contains(match.group(1))) {
                    notices.add(SECTION + text.getName() + ": has " + match.group() + ", which it doesn't fill in, so it shows as typed");
                }
            }
        }
        return notices;
    }

    /** Puts back, from fallback, each text that can't be used, and says so the way the other checks do. */
    static void check(YoltConfig.MessagesSection messages, YoltConfig.MessagesSection fallback, BiConsumer<String, String> fellBack) {
        for (Field text : texts()) {
            String problem = problem(text, (String) get(text, messages));
            if (problem != null) {
                set(text, messages, get(text, fallback));
                fellBack.accept(SECTION + text.getName(), problem);
            }
        }
    }

    private static @Nullable Object get(Field text, YoltConfig.MessagesSection messages) {
        try {
            return text.get(messages);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("can't read messages." + text.getName(), e);
        }
    }

    private static void set(Field text, YoltConfig.MessagesSection messages, @Nullable Object value) {
        try {
            text.set(messages, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("can't set messages." + text.getName(), e);
        }
    }
}
