package bobbynooby.dev.config;

import bobbynooby.dev.core.Migrations;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the config key by key, so one bad value only resets that value. */
public final class ConfigLoader {
    /** unparseable means the file was not valid JSON; config is then null if load was told not to write defaults. */
    public record Result(@Nullable YoltConfig config, List<String> warnings, boolean unparseable) {}

    // Vanilla's largest world border
    public static final int MAX_BORDER_DIAMETER = 59_999_968;

    /** The layout this build writes. An older file is upgraded by STEPS; a newer one is read but never rewritten. */
    public static final int CURRENT_VERSION = 1;
    // Each step upgrades the file's JSON from its version to the next
    static final List<Migrations.Step<JsonObject>> STEPS = List.of();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DURATION_PATH = "bodies.duration";
    private static final String VERSION_KEY = "configVersion";
    // Every file YOLT writes has the version, so a file without one is read as the first version
    private static final int FIRST_VERSION = 1;

    private ConfigLoader() {}

    public static Result load(Path file) {
        return load(file, true);
    }

    /** With writeDefaultsOnError false, a file that is not valid JSON is left as it is and nothing is loaded. */
    public static Result load(Path file, boolean writeDefaultsOnError) {
        return load(file, writeDefaultsOnError, CURRENT_VERSION, STEPS);
    }

    // The version and steps are parameters so a test can upgrade with steps of its own
    static Result load(Path file, boolean writeDefaultsOnError, int currentVersion, List<Migrations.Step<JsonObject>> steps) {
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(file)) {
            return new Result(writeDefaults(file), warnings, false);
        }

        JsonObject user;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("root is not an object");
            }
            user = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            String problem = "config is not valid JSON (" + e.getMessage() + ")";
            if (!writeDefaultsOnError) {
                warnings.add(problem);
                return new Result(null, warnings, true);
            }
            backup(file, file.getFileName() + ".bak");
            warnings.add(problem + ", old file saved as " + file.getFileName() + ".bak, using defaults");
            return new Result(writeDefaults(file), warnings, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        int version = fileVersion(user);
        if (version < currentVersion) {
            // Copied before anything is written, so the upgraded file never replaces the only copy of the old one
            String backup = file.getFileName() + ".v" + version + ".bak";
            backup(file, backup);
            user = Migrations.run("config", version, currentVersion, steps, user.deepCopy());
            warnings.add("config was v" + version + " and is upgraded to v" + currentVersion + ", old file saved as " + backup);
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
        // What is held is this build's layout, whatever the file said, so a save says so
        config.configVersion = currentVersion;
        if (version > currentVersion) {
            // Rewriting would drop what only the newer YOLT knows
            warnings.add("config is from a newer version, so it was not rewritten");
            return new Result(config, warnings, false);
        }
        write(file, config);
        return new Result(config, warnings, false);
    }

    // A missing or unusable version reads as the first one; merge still warns about one that isn't a number. Zero or
    // below can only be a damaged file.
    private static int fileVersion(JsonObject user) {
        JsonElement value = user.get(VERSION_KEY);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double version = value.getAsDouble();
            if (version == Math.floor(version) && version >= FIRST_VERSION && version <= Integer.MAX_VALUE) {
                return (int) version;
            }
        }
        return FIRST_VERSION;
    }

    /** Writes a temp file first and moves it over the config, so a crash mid-write can't leave half a file. */
    public static void write(Path file, YoltConfig config) {
        Path target = file.toAbsolutePath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new UncheckedIOException(e);
        }
    }

    private static YoltConfig writeDefaults(Path file) {
        YoltConfig config = new YoltConfig();
        write(file, config);
        return config;
    }

    private static void backup(Path file, String name) {
        try {
            Files.copy(file, file.resolveSibling(name), StandardCopyOption.REPLACE_EXISTING);
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
        // Either a string ("offline") or a number is valid here; BodyDuration's adapter rejects the bad values
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
        if (d.getAsNumber() instanceof Integer) {
            double v = u.getAsDouble();
            if (v != Math.floor(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                return "expected a whole number that fits in an int";
            }
        }
        return null;
    }

    private static void validate(YoltConfig c, List<String> warnings) {
        YoltConfig def = new YoltConfig();

        if (c.worldBorder.diameter < 1 || c.worldBorder.diameter > MAX_BORDER_DIAMETER) {
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
