package bobbynooby.dev.config;

import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Every config key, found from the config's own fields, so a new key can be read and set without more code. */
public final class ConfigKeys {
    // The file's layout, not a setting
    private static final String VERSION = "configVersion";
    // Maps of names to numbers, read whole from the file: the file's object replaces the default rather than adding to it
    private static final Set<String> NUMBER_MAPS = Set.of("hardcore.weight.groups", "hardcore.weight.items",
            "hardcore.weight.itemsEach", "hardcore.weight.armourFactors");
    // The same, one level deeper: each name maps to an object of numbers
    private static final Set<String> NUMBER_MAP_MAPS = Set.of("hardcore.nutrients.foods", "hardcore.tougherMobs.overrides");
    // One typed value can't hold a list or a map, so these are changed in the file
    private static final Set<String> FILE_ONLY = union(Set.of("serverList.hoverLines", "hide.tabHeaderLines",
                    "hardcore.tougherMobs.exclude"),
            NUMBER_MAPS, NUMBER_MAP_MAPS);
    private static final Pattern LOG_FORM = Pattern.compile("([\\w.]+): (.+)");

    private ConfigKeys() {}

    /** YoltConfig's keys, in the order the file has them. */
    public static List<ConfigKey> all() {
        return Holder.ALL;
    }

    public static Optional<ConfigKey> find(String path) {
        return all().stream().filter(key -> key.path().equals(path)).findFirst();
    }

    /** True for a setting that has no key here because it is changed only in the file, then reloaded. */
    public static boolean fileOnly(String path) {
        return FILE_ONLY.contains(path);
    }

    /** True for a map of names to numbers, which the loader reads whole. */
    static boolean numberMap(String path) {
        return NUMBER_MAPS.contains(path);
    }

    /** True for a map of names to objects of numbers, which the loader reads whole. */
    static boolean numberMapOfMaps(String path) {
        return NUMBER_MAP_MAPS.contains(path);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new HashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }

    /**
     * A problem from the config, in its log form "<key>: <problem>", written as a reply: "<key> <problem>.", or the
     * problem alone when another key is its subject, as in the End cap's. A plain word at the start gets a capital, and a
     * key's name keeps its case. Keys are told by their names, since some have no dot. An empty problem stays empty.
     */
    public static String asReply(String problem) {
        if (problem.isEmpty()) {
            return problem;
        }
        String text = problem;
        Matcher logForm = LOG_FORM.matcher(problem);
        if (logForm.matches()) {
            String rest = logForm.group(2);
            text = isKey(firstWord(rest)) ? rest : logForm.group(1) + " " + rest;
        }
        if (Character.isLowerCase(text.charAt(0)) && !isKey(firstWord(text))) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        return text.endsWith(".") ? text : text + ".";
    }

    private static boolean isKey(String word) {
        return find(word).isPresent() || fileOnly(word);
    }

    private static String firstWord(String text) {
        return text.split(" ", 2)[0];
    }

    /**
     * The keys of a config class: its public instance fields, with each nested *Section class's own fields under a
     * dotted prefix. A field of a type no key kind covers throws, so a new key of a new type fails the tests at once.
     */
    public static List<ConfigKey> of(Class<?> root) {
        List<ConfigKey> keys = new ArrayList<>();
        collect(root, "", keys);
        return List.copyOf(keys);
    }

    private static void collect(Class<?> type, String prefix, List<ConfigKey> keys) {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
                continue;
            }
            String path = prefix + field.getName();
            if (path.equals(VERSION) || FILE_ONLY.contains(path)) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (fieldType.isMemberClass() && fieldType.getSimpleName().endsWith("Section")) {
                collect(fieldType, path + ".", keys);
                continue;
            }
            Applies applies = field.getAnnotation(Applies.class);
            Applies.When when = applies == null ? Applies.When.NOW : applies.value();
            ConfigKey.Kind kind = kindOf(fieldType, path);
            keys.add(new ConfigKey(path, field, kind, choices(kind, fieldType), when));
        }
    }

    private static ConfigKey.Kind kindOf(Class<?> type, String path) {
        if (type == boolean.class) {
            return ConfigKey.Kind.BOOLEAN;
        }
        if (type == int.class) {
            return ConfigKey.Kind.INT;
        }
        if (type == double.class) {
            return ConfigKey.Kind.DOUBLE;
        }
        if (type.isEnum()) {
            return ConfigKey.Kind.ENUM;
        }
        if (type == String.class) {
            return ConfigKey.Kind.STRING;
        }
        if (type == BodyDuration.class) {
            return ConfigKey.Kind.BODY_DURATION;
        }
        throw new IllegalStateException("config key " + path + " is a " + type.getName() + ", which no config key kind reads");
    }

    private static List<String> choices(ConfigKey.Kind kind, Class<?> type) {
        return switch (kind) {
            case BOOLEAN -> List.of("true", "false");
            case ENUM -> enumNames(type);
            case BODY_DURATION -> List.of("offline");
            default -> List.of();
        };
    }

    // The names the file uses, which Gson takes from @SerializedName
    private static List<String> enumNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            try {
                SerializedName serialized = type.getField(name).getAnnotation(SerializedName.class);
                names.add(serialized == null ? name : serialized.value());
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
        return List.copyOf(names);
    }

    // Built once, on first use
    private static final class Holder {
        static final List<ConfigKey> ALL = of(YoltConfig.class);
    }
}
