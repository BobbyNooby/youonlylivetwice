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
import java.nio.charset.CharacterCodingException;
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
    /** unparseable means the file could not be read or was not valid JSON; config is then null if load was told not to write defaults. */
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

    /**
     * With a base (a reload), a bad or missing key keeps the base's value and a missing file changes nothing. Without
     * one (startup), they fall back to the defaults. With writeDefaultsOnError false, a file that can't be read is left
     * as it is and nothing is loaded.
     */
    public static Result load(Path file, @Nullable YoltConfig base, boolean writeDefaultsOnError) {
        return load(file, base, writeDefaultsOnError, CURRENT_VERSION, STEPS);
    }

    // The version and steps are parameters so a test can upgrade with steps of its own
    static Result load(Path file, @Nullable YoltConfig base, boolean writeDefaultsOnError, int currentVersion,
                       List<Migrations.Step<JsonObject>> steps) {
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(file)) {
            if (base != null) {
                // A moved file must not reset a running server, so nothing is written either
                warnings.add("config file is missing, nothing changed");
                return new Result(base, warnings, false);
            }
            return new Result(writeDefaults(file), warnings, false);
        }

        JsonObject user;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("root is not an object");
            }
            user = parsed.getAsJsonObject();
        } catch (JsonParseException | IOException e) {
            // A file that can't be read (not UTF-8, say) is as unusable as one that isn't JSON. The decoder only says
            // "Input length = 1", so that case is named here
            String problem = e instanceof CharacterCodingException
                    ? "config could not be read (not UTF-8 text)"
                    : (e instanceof IOException ? "config could not be read (" : "config is not valid JSON (") + e.getMessage() + ")";
            if (!writeDefaultsOnError) {
                warnings.add(problem);
                return new Result(null, warnings, true);
            }
            backup(file, file.getFileName() + ".bak");
            warnings.add(problem + ", old file saved as " + file.getFileName() + ".bak, using defaults");
            return new Result(writeDefaults(file), warnings, true);
        }

        int version = fileVersion(user);
        if (version < currentVersion) {
            // Copied before anything is written, so the upgraded file never replaces the only copy of the old one
            String backup = file.getFileName() + ".v" + version + ".bak";
            backup(file, backup);
            user = Migrations.run("config", version, currentVersion, steps, user.deepCopy());
            warnings.add("config was v" + version + " and is upgraded to v" + currentVersion + ", old file saved as " + backup);
        }

        YoltConfig fallback = base != null ? base : new YoltConfig();
        String fallbackName = base != null ? "the current value" : "default";
        JsonObject merged = merge(GSON.toJsonTree(fallback).getAsJsonObject(), user, "", warnings, fallbackName);

        YoltConfig config;
        try {
            config = GSON.fromJson(merged, YoltConfig.class);
        } catch (JsonParseException e) {
            // merge already checked the types, so this should not happen
            warnings.add("config could not be read (" + e.getMessage() + "), using defaults");
            config = new YoltConfig();
        }
        ConfigChecks.validate(config, fallback, warnings, fallbackName);
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

    private static JsonObject merge(JsonObject defaults, JsonObject user, String prefix, List<String> warnings, String fallbackName) {
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
                    result.add(key, merge(def.getAsJsonObject(), value.getAsJsonObject(), path + ".", warnings, fallbackName));
                } else {
                    result.add(key, def);
                    warnings.add(path + ": expected an object, using " + fallbackName);
                }
            } else if (def.isJsonArray()) {
                if (isStringArray(value)) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    warnings.add(path + ": expected a list of strings, using " + fallbackName);
                }
            } else {
                String problem = primitiveProblem(path, def, value);
                if (problem == null) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    warnings.add(path + ": " + problem + ", using " + fallbackName);
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

    private static boolean isStringArray(JsonElement value) {
        if (!value.isJsonArray()) {
            return false;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                return false;
            }
        }
        return true;
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
        // 1e400 reads as infinity, which Gson can neither load into the config nor write back
        if (!Double.isFinite(u.getAsDouble())) {
            return "expected a finite number";
        }
        // whole-number settings can't take 2.5. Gson's tree holds an int default as a Long; YoltConfig has no long keys
        Number defaultNumber = d.getAsNumber();
        if (defaultNumber instanceof Integer || defaultNumber instanceof Long) {
            double v = u.getAsDouble();
            if (v != Math.floor(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                return "expected a whole number that fits in an int";
            }
            // Gson can't make an int of some numbers that read fine as a double (1e-99999), and that resets the whole config
            try {
                u.getAsInt();
            } catch (NumberFormatException e) {
                return "expected a whole number that fits in an int";
            }
        }
        return null;
    }
}
