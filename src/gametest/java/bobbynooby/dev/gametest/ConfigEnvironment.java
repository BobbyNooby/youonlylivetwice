package bobbynooby.dev.gametest;

import bobbynooby.dev.config.ConfigKey;
import bobbynooby.dev.config.ConfigKeys;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import com.google.gson.Gson;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A test environment that holds config keys on the live config for a whole batch, and the framework puts them back when
 * the batch ends, whether its tests passed, failed or timed out. Only the live config changes, never the file. A function
 * environment can't do this: its commands are read when the datapack loads, before /yolt exists for anyone.
 */
record ConfigEnvironment(Map<String, String> values) implements TestEnvironmentDefinition<Map<ConfigKey, Object>> {
    static final MapCodec<ConfigEnvironment> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("values").forGetter(ConfigEnvironment::values)
    ).apply(i, ConfigEnvironment::new));
    private static final Gson GSON = new Gson();

    /** Before any datapack loads, so the environment files can name this type. */
    static void register() {
        Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE,
                Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "config"), CODEC);
    }

    @Override
    public Map<ConfigKey, Object> setup(ServerLevel level) {
        Map<ConfigKey, Object> before = new LinkedHashMap<>();
        YoltConfig config = Settings.get();
        for (Map.Entry<String, String> value : values.entrySet()) {
            ConfigKey key = ConfigKeys.find(value.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("No config key is named " + value.getKey()));
            if (key.kind() != ConfigKey.Kind.BOOLEAN && key.kind() != ConfigKey.Kind.INT && key.kind() != ConfigKey.Kind.DOUBLE) {
                throw new IllegalArgumentException(key.path() + " is not a switch or a number");
            }
            Object parsed;
            try {
                parsed = GSON.fromJson(key.parse(value.getValue()), key.field().getGenericType());
            } catch (ConfigKey.BadValue e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            // The first value is the one to put back, should a key be named twice
            before.putIfAbsent(key, get(config, key));
            set(config, key, parsed);
        }
        return before;
    }

    // Onto the config live now, which a test that reloads the file may have swapped in
    @Override
    public void teardown(ServerLevel level, Map<ConfigKey, Object> before) {
        YoltConfig config = Settings.get();
        before.forEach((key, value) -> set(config, key, value));
    }

    @Override
    public MapCodec<ConfigEnvironment> codec() {
        return CODEC;
    }

    private static Object get(YoltConfig config, ConfigKey key) {
        try {
            return key.field().get(section(config, key));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read config key " + key.path(), e);
        }
    }

    private static void set(YoltConfig config, ConfigKey key, Object value) {
        try {
            key.field().set(section(config, key), value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't set config key " + key.path(), e);
        }
    }

    // The object that holds the key's field: hardcore.weight for hardcore.weight.enabled
    private static Object section(YoltConfig config, ConfigKey key) throws ReflectiveOperationException {
        Object section = config;
        String[] parts = key.path().split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            section = section.getClass().getField(parts[i]).get(section);
        }
        return section;
    }
}
