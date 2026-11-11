package bobbynooby.dev.config;

import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Every config key, found from the config's own fields, so a new key can be read and set without more code. */
public final class ConfigKeys {
    // The version is the file's layout, not a setting, and a list can't be typed as one value, so both stay in the file
    private static final Set<String> FILE_ONLY = Set.of("configVersion", "serverList.hoverLines");

    private ConfigKeys() {}

    /** YoltConfig's keys, in the order the file has them. */
    public static List<ConfigKey> all() {
        return Holder.ALL;
    }

    public static Optional<ConfigKey> find(String path) {
        return all().stream().filter(key -> key.path().equals(path)).findFirst();
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
            if (FILE_ONLY.contains(path)) {
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
