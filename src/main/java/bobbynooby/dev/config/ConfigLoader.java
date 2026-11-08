package bobbynooby.dev.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the config key by key, so one bad value only resets that value. */
public final class ConfigLoader {
    public record Result(YoltConfig config, List<String> warnings) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DURATION_PATH = "bodies.duration";

    private ConfigLoader() {}

    public static Result load(Path file) {
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(file)) {
            return writeDefaults(file, warnings);
        }

        JsonObject user;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("root is not an object");
            }
            user = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            backup(file);
            warnings.add("config is not valid JSON (" + e.getMessage() + "), old file saved as "
                    + file.getFileName() + ".bak, using defaults");
            return writeDefaults(file, warnings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        JsonObject defaults = GSON.toJsonTree(new YoltConfig()).getAsJsonObject();
        JsonObject merged = merge(defaults, user, "", warnings);

        YoltConfig config;
        try {
            config = GSON.fromJson(merged, YoltConfig.class);
        } catch (JsonParseException e) {
            // merge already checked the types, so this should not happen
            warnings.add("config could not be read (" + e.getMessage() + "), using defaults");
            config = new YoltConfig();
        }
        validate(config, warnings);
        write(file, config);
        return new Result(config, warnings);
    }

    public static void write(Path file, YoltConfig config) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Result writeDefaults(Path file, List<String> warnings) {
        YoltConfig config = new YoltConfig();
        write(file, config);
        return new Result(config, warnings);
    }

    private static void backup(Path file) {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject merge(JsonObject defaults, JsonObject user, String prefix, List<String> warnings) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            String key = entry.getKey();
            String path = prefix + key;
            JsonElement def = entry.getValue();
            JsonElement value = user.get(key);

            if (value == null) {
                result.add(key, def);
            } else if (def.isJsonObject()) {
                if (value.isJsonObject()) {
                    result.add(key, merge(def.getAsJsonObject(), value.getAsJsonObject(), path + ".", warnings));
                } else {
                    result.add(key, def);
                    warnings.add(path + ": expected an object, using default");
                }
            } else {
                String problem = primitiveProblem(path, def, value);
                if (problem == null) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    warnings.add(path + ": " + problem + ", using default");
                }
            }
        }
        for (String key : user.keySet()) {
            if (!defaults.has(key)) {
                warnings.add(prefix + key + ": unknown key, ignored");
            }
        }
        return result;
    }

    /** Returns why the user's value can't replace the default, or null if it can. */
    private static String primitiveProblem(String path, JsonElement def, JsonElement value) {
        if (!value.isJsonPrimitive()) {
            return "wrong type";
        }
        JsonPrimitive u = value.getAsJsonPrimitive();
        JsonPrimitive d = def.getAsJsonPrimitive();
        if (path.equals(DURATION_PATH)) {
            return u.isString() || u.isNumber() ? null : "expected \"offline\" or a number";
        }
        if (d.isBoolean()) {
            return u.isBoolean() ? null : "expected true or false";
        }
        if (d.isString()) {
            return u.isString() ? null : "expected a string";
        }
        if (!u.isNumber()) {
            return "expected a number";
        }
        // whole-number settings can't take 2.5
        if (d.getAsNumber() instanceof Integer && u.getAsDouble() != Math.floor(u.getAsDouble())) {
            return "expected a whole number";
        }
        return null;
    }

    private static void validate(YoltConfig c, List<String> warnings) {
        YoltConfig def = new YoltConfig();

        if (c.worldBorder.diameter < 1 || c.worldBorder.diameter > 59_999_968) {
            c.worldBorder.diameter = def.worldBorder.diameter;
            warnings.add("worldBorder.diameter: must be 1 to 59999968, using default");
        }
        if (!(c.worldBorder.endScale > 0)) {
            c.worldBorder.endScale = def.worldBorder.endScale;
            warnings.add("worldBorder.endScale: must be above 0, using default");
        }
        if (c.deathLockout.cooldownDays < 0) {
            c.deathLockout.cooldownDays = def.deathLockout.cooldownDays;
            warnings.add("deathLockout.cooldownDays: must be 0 or more, using default");
        }
        if (c.chat.radius < 1) {
            c.chat.radius = def.chat.radius;
            warnings.add("chat.radius: must be 1 or more, using default");
        }
        if (c.graves.enderChestOnDeath == null) {
            c.graves.enderChestOnDeath = def.graves.enderChestOnDeath;
            warnings.add("graves.enderChestOnDeath: unknown value, using default");
        }
        if (c.chat.mode == null) {
            c.chat.mode = def.chat.mode;
            warnings.add("chat.mode: unknown value, using default");
        }
        if (c.bodies.type == null) {
            c.bodies.type = def.bodies.type;
            warnings.add("bodies.type: unknown value, using default");
        }
        if (c.bodies.pose == null) {
            c.bodies.pose = def.bodies.pose;
            warnings.add("bodies.pose: unknown value, using default");
        }
        if (c.bodies.duration == null) {
            c.bodies.duration = def.bodies.duration;
            warnings.add("bodies.duration: expected \"offline\" or minutes of 1 or more, using default");
        }
    }
}
