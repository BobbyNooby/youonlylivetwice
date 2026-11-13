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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the config key by key, so one bad value only resets that value. */
public final class ConfigLoader {
    /**
     * unparseable means the file could not be read or was not valid JSON; config is then null if load was told not to
     * write defaults. borderProblem, when not null, says why the worldBorder values are fallbacks rather than the file's
     * own: the whole file, or a worldBorder key that was refused or unknown. rewritten says whether the file now holds
     * what was loaded: a newer config is left as it is, and so is a file a reload couldn't read, didn't find or couldn't
     * write. backup names the copy kept of the file before this load wrote over it, or is null.
     */
    public record Result(@Nullable YoltConfig config, List<String> warnings, boolean unparseable, @Nullable String borderProblem,
                         boolean rewritten, @Nullable String backup) {}

    /**
     * What setting one key did. config is the new live config, and null when nothing changed: problem then says why, or
     * is null too when the key already had that value. backup names the copy kept of a file that couldn't be read before
     * the whole config was written over it, even if that write then failed, and is null otherwise.
     */
    public record Change(@Nullable YoltConfig config, String oldValue, String newValue, @Nullable String problem,
                         @Nullable String backup) {}

    // Vanilla's largest world border
    public static final int MAX_BORDER_DIAMETER = 59_999_968;

    /** The layout this build writes. An older file is upgraded by STEPS; a newer one is read but never rewritten. */
    public static final int CURRENT_VERSION = 1;
    // Each step upgrades the file's JSON from its version to the next
    static final List<Migrations.Step<JsonObject>> STEPS = List.of();

    // Without HTML escaping, so an apostrophe in a text, as in a tab header's "You're", is written as typed and not as
    // an escape code
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DURATION_PATH = "bodies.duration";
    private static final String VERSION_KEY = "configVersion";
    private static final String BORDER_SECTION = "worldBorder";
    private static final String CURRENT_VALUE = "the current value";
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
                // A moved file must not reset a running server, so nothing is written either. The base itself comes
                // back, which is how /yolt reload tells that nothing was read
                warnings.add("config file is missing, nothing changed");
                return new Result(base, warnings, false, null, false, null);
            }
            return new Result(writeDefaults(file), warnings, false, null, true, null);
        }

        JsonObject user;
        try {
            user = readObject(file);
        } catch (JsonParseException | IOException e) {
            // A file that can't be read (not UTF-8, say) is as unusable as one that isn't JSON. The decoder only says
            // "Input length = 1", so that case is named here
            String problem = e instanceof CharacterCodingException
                    ? "config could not be read (not UTF-8 text)"
                    : (e instanceof IOException ? "config could not be read (" : "config is not valid JSON (") + e.getMessage() + ")";
            if (!writeDefaultsOnError) {
                warnings.add(problem);
                return new Result(null, warnings, true, null, false, null);
            }
            String backup = backup(file, file.getFileName() + ".bak");
            warnings.add(problem + ", old file saved as " + backup + ", using defaults");
            return new Result(writeDefaults(file), warnings, true, problem, true, backup);
        }

        int version = fileVersion(user, warnings);
        String backup = null;
        if (version < currentVersion) {
            // Copied before anything is written, so the upgraded file never replaces the only copy of the old one
            backup = backup(file, file.getFileName() + ".v" + version + ".bak");
            user = Migrations.run("config", version, currentVersion, steps, user.deepCopy());
            warnings.add("config was v" + version + " and is upgraded to v" + currentVersion + ", old file saved as " + backup);
        }

        YoltConfig fallback = base != null ? base : new YoltConfig();
        String fallbackName = base != null ? CURRENT_VALUE : "default";
        // The keys the file gave but the config doesn't use (fallbacks and unknown keys), so a startup can tell whether
        // the border values are the file's own
        List<String> refused = new ArrayList<>();
        int warningsBeforeMerge = warnings.size();
        JsonObject merged = merge(GSON.toJsonTree(fallback).getAsJsonObject(), user, "", warnings, fallbackName, refused);

        Read read = read(merged, fallback);
        YoltConfig config = read.config();
        String borderProblem = read.problem();
        if (borderProblem != null) {
            warnings.add(borderProblem + (base != null ? ", using the current values" : ", using defaults"));
        }
        refused.addAll(ConfigChecks.validate(config, fallback, warnings, fallbackName));
        if (borderProblem == null) {
            borderProblem = borderProblem(refused);
        }
        // What is held is this build's layout, whatever the file said, so a save says so
        config.configVersion = currentVersion;
        if (version > currentVersion) {
            // Rewriting would drop what only the newer YOLT knows
            warnings.add("config is from a newer version, so it was not rewritten");
            return new Result(config, warnings, false, borderProblem, false, backup);
        }
        // Each warning since the merge began is something typed in the file that the rewrite leaves out or changes, so at
        // startup the admin's file is kept first, under the same name as one that can't be read. A reload keeps the live
        // values and writes no copy.
        if (writeDefaultsOnError && warnings.size() > warningsBeforeMerge) {
            backup = backup(file, file.getFileName() + ".bak");
            warnings.add("old file saved as " + backup + " before the rewrite");
        }
        try {
            write(file, config);
        } catch (UncheckedIOException e) {
            // What was read still applies, at startup as on a reload, as a set key whose write fails is reported rather
            // than thrown: a read-only folder must not stop the server. The write goes through a temp file, so the config
            // file is as it was.
            warnings.add("The config file could not be rewritten (" + e.getCause().getMessage() + "), so it is left as it was.");
            return new Result(config, warnings, false, borderProblem, false, backup);
        }
        return new Result(config, warnings, false, borderProblem, true, backup);
    }

    /** The merged tree as a config, and why it couldn't be read, or null when it could. */
    record Read(YoltConfig config, @Nullable String problem) {}

    // merge already checked the types, so this should not fail. If it does, the whole config falls back as one bad key
    // does: on a reload to the live values, not every default. A copy, since the checks after this change the config they
    // get, and a reload that hands back the live config itself counts as one that read nothing.
    static Read read(JsonObject merged, YoltConfig fallback) {
        try {
            return new Read(GSON.fromJson(merged, YoltConfig.class), null);
        } catch (JsonParseException e) {
            YoltConfig copy = GSON.fromJson(GSON.toJsonTree(fallback), YoltConfig.class);
            return new Read(copy, "config could not be read (" + e.getMessage() + ")");
        }
    }

    // Null when every worldBorder value is the file's own. A top-level "worldborder" counts too: it is most likely the
    // section misspelt, which leaves every real border key at its fallback
    private static @Nullable String borderProblem(List<String> refused) {
        List<String> border = refused.stream()
                .filter(path -> path.split("\\.", 2)[0].equalsIgnoreCase(BORDER_SECTION))
                .toList();
        return border.isEmpty() ? null : "the config has a bad " + String.join(" and ", border);
    }

    // A missing or unusable version reads as the first one. merge warns about one that isn't a whole number that fits in
    // an int; zero or below can only be a damaged file, so that is warned about here.
    private static int fileVersion(JsonObject user, List<String> warnings) {
        JsonElement value = user.get(VERSION_KEY);
        if (value == null || primitiveProblem(VERSION_KEY, new JsonPrimitive(FIRST_VERSION), value) != null) {
            return FIRST_VERSION;
        }
        int version = value.getAsInt();
        if (version < FIRST_VERSION) {
            warnings.add(VERSION_KEY + ": " + value.getAsString() + " is not a version, read as version " + FIRST_VERSION);
            return FIRST_VERSION;
        }
        return version;
    }

    private static JsonObject readObject(Path file) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("root is not an object");
        }
        return parsed.getAsJsonObject();
    }

    /**
     * Settings.set without swapping the live config: reads the text, then sets the key unless both the live config and
     * the file have that value already. When only the file differs, the key is still written, so the next load doesn't
     * bring the file's value back; oldValue and newValue are then the same.
     */
    static Change setText(Path file, YoltConfig live, ConfigKey key, String text) {
        return setText(file, live, key, text, next -> null);
    }

    // The same, with the caller's own last check on the config the change gives (see setKey)
    static Change setText(Path file, YoltConfig live, ConfigKey key, String text, Function<YoltConfig, @Nullable String> veto) {
        String current = key.show(live);
        JsonPrimitive value;
        try {
            value = key.parse(text);
        } catch (ConfigKey.BadValue e) {
            return new Change(null, current, text, e.getMessage(), null);
        }
        if (key.json(live).equals(value) && value.equals(fileValue(file, key.path()))) {
            return new Change(null, current, current, null, null);
        }
        return setKey(file, live, key, value, veto);
    }

    // The key's value in the file's own JSON, or null when the file doesn't have it or can't be read
    private static @Nullable JsonElement fileValue(Path file, String path) {
        JsonElement value = ownJson(file);
        for (String part : path.split("\\.")) {
            if (value == null || !value.isJsonObject()) {
                return null;
            }
            value = value.getAsJsonObject().get(part);
        }
        return value;
    }

    /**
     * Checks the new value as a reload would, with the live config as the fallback, and refuses it if any warning names
     * the key. Otherwise only that key changes in the file, so the admin's other edits and keys this build doesn't know
     * stay. A file that can't be read is kept as a .bak, named in the change, and the whole new config written. live
     * itself is not changed.
     */
    public static Change setKey(Path file, YoltConfig live, ConfigKey key, JsonPrimitive value) {
        return setKey(file, live, key, value, next -> null);
    }

    /**
     * The same, and veto sees the config a good value gives before anything is written: a reason it returns refuses the
     * change as a problem would, and null lets it through. A command uses it for checks of its own, such as who a new
     * border would leave outside.
     */
    static Change setKey(Path file, YoltConfig live, ConfigKey key, JsonPrimitive value, Function<YoltConfig, @Nullable String> veto) {
        String oldValue = key.show(live);
        String typed = value.getAsString();
        JsonObject liveJson = GSON.toJsonTree(live).getAsJsonObject();
        JsonObject edited = liveJson.deepCopy();
        put(edited, key.path(), value);

        List<String> warnings = new ArrayList<>();
        JsonObject merged = merge(liveJson, edited, "", warnings, CURRENT_VALUE, new ArrayList<>());
        YoltConfig config;
        try {
            config = GSON.fromJson(merged, YoltConfig.class);
        } catch (JsonParseException e) {
            return new Change(null, oldValue, typed, key.path() + ": " + e.getMessage(), null);
        }
        ConfigChecks.validate(config, live, warnings, CURRENT_VALUE);
        for (String warning : warnings) {
            if (names(warning, key.path())) {
                int tail = warning.lastIndexOf(", using ");
                return new Change(null, oldValue, typed, tail < 0 ? warning : warning.substring(0, tail), null);
            }
        }
        String refused = veto.apply(config);
        if (refused != null) {
            return new Change(null, oldValue, typed, refused, null);
        }

        // A file that can't be read is copied before the whole config goes over it, and the copy is named even when that
        // write then fails, so the admin still learns where their file went
        String backup = null;
        try {
            JsonObject own = Files.exists(file) ? ownJson(file) : null;
            if (own != null) {
                put(own, key.path(), value);
                writeText(file, GSON.toJson(own));
            } else {
                if (Files.exists(file)) {
                    backup = backup(file, file.getFileName() + ".bak");
                }
                write(file, config);
            }
        } catch (UncheckedIOException e) {
            String problem = "the config file could not be saved (" + e.getCause().getMessage() + ")";
            return new Change(null, oldValue, typed, problem, backup);
        }
        return new Change(config, oldValue, key.show(config), null, backup);
    }

    // Anywhere in the warning, since the End cap's begins with endScale but is as much about the diameter; and only the
    // whole name, so graves.tellLocation isn't taken for graves.tellLocationDistance. A full stop after it still counts,
    // since a name can end a sentence.
    static boolean names(String warning, String path) {
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(path) + "(?![\\w]|\\.\\w)").matcher(warning).find();
    }

    // The file's own JSON, or null when it can't be read
    private static @Nullable JsonObject ownJson(Path file) {
        try {
            return readObject(file);
        } catch (JsonParseException | IOException e) {
            return null;
        }
    }

    // Sets a dotted path, making any section on the way that is missing or isn't an object
    private static void put(JsonObject root, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        JsonObject section = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = section.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                child = new JsonObject();
                section.add(parts[i], child);
            }
            section = child.getAsJsonObject();
        }
        section.add(parts[parts.length - 1], value);
    }

    /** Writes a temp file first and moves it over the config, so a crash mid-write can't leave half a file. */
    public static void write(Path file, YoltConfig config) {
        writeText(file, GSON.toJson(config));
    }

    private static void writeText(Path file, String text) {
        Path target = file.toAbsolutePath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
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

    /**
     * Copies the file to name. A name that already holds other bytes, perhaps the admin's whole config from an earlier
     * start, is never written over: the copy goes beside it with the time added. A copy with these bytes already there
     * is used as it is. Returns the name of the copy.
     */
    private static String backup(Path file, String name) {
        try {
            if (!Files.exists(file.resolveSibling(name))) {
                Files.copy(file, file.resolveSibling(name));
                return name;
            }
            String same = sameCopy(file, name);
            if (same != null) {
                return same;
            }
            long time = System.currentTimeMillis();
            while (Files.exists(file.resolveSibling(name + "-" + time))) {
                time++;
            }
            Files.copy(file, file.resolveSibling(name + "-" + time));
            return name + "-" + time;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // name, or one of its copies with the time added, when it already holds the file's bytes. One that can't be compared
    // counts as different, since a copy too many is the safe side and a startup must not stop over it
    private static @Nullable String sameCopy(Path file, String name) {
        if (sameBytes(file, file.resolveSibling(name))) {
            return name;
        }
        try (Stream<Path> siblings = Files.list(file.toAbsolutePath().getParent())) {
            for (Path sibling : siblings.toList()) {
                String siblingName = sibling.getFileName().toString();
                if (siblingName.startsWith(name + "-") && sameBytes(file, sibling)) {
                    return siblingName;
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
        return null;
    }

    private static boolean sameBytes(Path file, Path copy) {
        try {
            return Files.mismatch(file, copy) == -1;
        } catch (IOException e) {
            return false;
        }
    }

    private static JsonObject merge(JsonObject defaults, JsonObject user, String prefix, List<String> warnings, String fallbackName,
                                    List<String> refused) {
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
                    result.add(key, merge(def.getAsJsonObject(), value.getAsJsonObject(), path + ".", warnings, fallbackName, refused));
                } else {
                    result.add(key, def);
                    refused.add(path);
                    warnings.add(path + ": expected an object, using " + fallbackName);
                }
            } else if (def.isJsonArray()) {
                if (isStringArray(value)) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    refused.add(path);
                    warnings.add(path + ": expected a list of strings, using " + fallbackName);
                }
            } else {
                String problem = primitiveProblem(path, def, value);
                if (problem == null) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    refused.add(path);
                    warnings.add(path + ": " + problem + ", using " + fallbackName);
                }
            }
        }
        for (String key : user.keySet()) {
            if (!defaults.has(key)) {
                // Most likely a misspelling, whose real key then quietly takes its default
                refused.add(prefix + key);
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
    private static @Nullable String primitiveProblem(String path, JsonElement def, JsonElement value) {
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
