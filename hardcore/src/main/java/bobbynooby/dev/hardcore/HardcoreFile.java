package bobbynooby.dev.hardcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * config/yolt-hardcore.json, the mod's settings on its own: configVersion, the hardcore block of YOLT's config, key for
 * key, and the six blood moon texts under the names YOLT's messages give them, so a block copied from one file to the
 * other reads the same.
 * <p>
 * It is read key by key with the rules and words of YOLT's own loader, so one bad value only loses that value: a
 * missing key takes the fallback, a wrong type or an unknown key is named, and so is a value HardcoreChecks refuses.
 * The fallback is the default at a start and the value in use on a reload. At a start the file is written again with
 * every key, after a copy of it is kept as .bak when a note names something typed; a reload only reads. YOLT's
 * HardcoreFileParityTest holds the two loaders to the same notes and values.
 */
public final class HardcoreFile {
    public static final String NAME = "yolt-hardcore.json";
    // The layout this build writes
    static final int VERSION = 1;
    // YOLT's limit for any text of its messages, a line of chat's worth
    static final int MAX_TEXT_LENGTH = 256;
    // Without HTML escaping, so an apostrophe in a text is written as typed and not as an escape code, as YOLT writes it
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    // Maps of names to numbers, read whole: the file's object replaces the default rather than adding to it
    private static final Set<String> NUMBER_MAPS = Set.of("hardcore.weight.groups", "hardcore.weight.items",
            "hardcore.weight.itemsEach", "hardcore.weight.armourFactors");
    // The same, one level deeper: each name maps to an object of numbers
    private static final Set<String> NUMBER_MAP_MAPS = Set.of("hardcore.nutrients.foods", "hardcore.tougherMobs.overrides");
    private static final String DEFAULT = "default";
    private static final String CURRENT_VALUE = "the current value";

    /** What the mod runs on: the hardcore settings and the blood moon's texts. */
    public record Loaded(HardcoreConfig.HardcoreSection hardcore, BloodMoonTexts texts) {
        /** Every default, new each time, so nothing that changes one changes another. */
        public static Loaded defaults() {
            return new Loaded(new HardcoreConfig.HardcoreSection(), BloodMoonTexts.DEFAULTS);
        }
    }

    /**
     * What a read gave. loaded is what to use from now on. notes has a line for each value that was refused or unknown
     * and for what happened to the file. failed is true when the file couldn't be read at all, or on a reload wasn't
     * there: loaded is then the defaults at a start and the settings in use on a reload. backup names the copy kept of
     * the file before a start wrote over it, or is null.
     */
    public record Result(Loaded loaded, List<String> notes, boolean failed, @Nullable String backup) {}

    // The file as Gson reads and writes it, in the order it is written
    private static final class Shape {
        int configVersion = VERSION;
        HardcoreConfig.HardcoreSection hardcore = new HardcoreConfig.HardcoreSection();
        Texts messages = new Texts(BloodMoonTexts.DEFAULTS);

        // For Gson
        Shape() {}

        Shape(Loaded loaded) {
            hardcore = loaded.hardcore();
            messages = new Texts(loaded.texts());
        }
    }

    // The six blood moon texts under their names in YOLT's messages
    private static final class Texts {
        @Nullable String bloodMoonWarningTitle;
        @Nullable String bloodMoonWarningLine;
        @Nullable String bloodMoonRisenLine;
        @Nullable String bloodMoonUpLine;
        @Nullable String bloodMoonSetLine;
        @Nullable String bloodMoonBarTitle;

        // For Gson
        Texts() {
            this(BloodMoonTexts.DEFAULTS);
        }

        Texts(BloodMoonTexts texts) {
            bloodMoonWarningTitle = texts.warningTitle();
            bloodMoonWarningLine = texts.warningLine();
            bloodMoonRisenLine = texts.risenLine();
            bloodMoonUpLine = texts.upLine();
            bloodMoonSetLine = texts.setLine();
            bloodMoonBarTitle = texts.barTitle();
        }

        // Each text YOLT's Messages.check would refuse goes back to the fallback's, in the order the file has them
        BloodMoonTexts checked(BloodMoonTexts fallback, List<String> notes, String fallbackName) {
            return new BloodMoonTexts(
                    text("bloodMoonWarningTitle", bloodMoonWarningTitle, fallback.warningTitle(), notes, fallbackName),
                    text("bloodMoonWarningLine", bloodMoonWarningLine, fallback.warningLine(), notes, fallbackName),
                    text("bloodMoonRisenLine", bloodMoonRisenLine, fallback.risenLine(), notes, fallbackName),
                    text("bloodMoonUpLine", bloodMoonUpLine, fallback.upLine(), notes, fallbackName),
                    text("bloodMoonSetLine", bloodMoonSetLine, fallback.setLine(), notes, fallbackName),
                    text("bloodMoonBarTitle", bloodMoonBarTitle, fallback.barTitle(), notes, fallbackName));
        }

        // None of the six has a placeholder, so Messages.problem only asks for text of at most its length
        private static String text(String key, @Nullable String value, String fallback, List<String> notes,
                                   String fallbackName) {
            String problem = value == null ? "must be text"
                    : value.length() > MAX_TEXT_LENGTH ? "must be at most " + MAX_TEXT_LENGTH + " characters" : null;
            if (problem == null) {
                return value;
            }
            notes.add("messages." + key + ": " + problem + ", using " + fallbackName);
            return fallback;
        }
    }

    private HardcoreFile() {}

    /**
     * Reads the file at a server start, with the defaults for whatever it lacks or gets wrong, and writes it again with
     * every key. A missing file is written with the defaults. One that can't be read or isn't JSON is kept as .bak and
     * the defaults written in its place. A folder that can't be written never stops the start: the notes say so, and
     * what was read still applies.
     */
    public static Result start(Path file) {
        List<String> notes = new ArrayList<>();
        if (!Files.exists(file)) {
            return defaults(file, notes, false, null);
        }
        JsonObject user;
        try {
            user = readObject(file);
        } catch (JsonParseException | IOException e) {
            String problem = problem(e);
            String backup;
            try {
                backup = backup(file, file.getFileName() + ".bak");
            } catch (UncheckedIOException copying) {
                // The file is the admin's only copy, so it stays as it is, and the server runs on the defaults
                notes.add(problem + ", and it could not be copied (" + copying.getCause().getMessage()
                        + "), so it is left as it was, using defaults");
                return new Result(Loaded.defaults(), notes, true, null);
            }
            notes.add(problem + ", old file saved as " + backup + ", using defaults");
            return defaults(file, notes, true, backup);
        }
        Loaded loaded = read(user, Loaded.defaults(), DEFAULT, notes);
        // Each note names something typed in the file that the rewrite leaves out or changes, so the admin's file is
        // kept first, under the same name as one that can't be read
        String backup = null;
        if (!notes.isEmpty()) {
            try {
                backup = backup(file, file.getFileName() + ".bak");
                notes.add("old file saved as " + backup + " before the rewrite");
            } catch (UncheckedIOException e) {
                notes.add("The config file could not be copied before the rewrite (" + e.getCause().getMessage()
                        + "), so it is left as it was.");
                return new Result(loaded, notes, false, null);
            }
        }
        try {
            write(file, loaded);
        } catch (UncheckedIOException e) {
            // The write goes through a temp file, so the file is as it was
            notes.add("The config file could not be rewritten (" + e.getCause().getMessage() + "), so it is left as it was.");
        }
        return new Result(loaded, notes, false, backup);
    }

    /**
     * Reads the file again, with the settings in use for whatever it lacks or gets wrong, and writes nothing. A file that
     * is missing, can't be read or isn't JSON changes nothing, so one typo can't reset a running server.
     */
    public static Result reload(Path file, Loaded inUse) {
        List<String> notes = new ArrayList<>();
        if (!Files.exists(file)) {
            notes.add("config file is missing, nothing changed");
            return new Result(inUse, notes, true, null);
        }
        JsonObject user;
        try {
            user = readObject(file);
        } catch (JsonParseException | IOException e) {
            notes.add(problem(e));
            return new Result(inUse, notes, true, null);
        }
        return new Result(read(user, inUse, CURRENT_VALUE, notes), notes, false, null);
    }

    /** The text the file is written with for these settings: every key, in the file's order. */
    static String json(Loaded loaded) {
        return GSON.toJson(new Shape(loaded));
    }

    // Merged over the fallback key by key, then checked. The result is new, so nothing in it is the fallback's own
    // section, which a reload's caller is still running on.
    private static Loaded read(JsonObject user, Loaded fallback, String fallbackName, List<String> notes) {
        JsonObject defaults = GSON.toJsonTree(new Shape(fallback)).getAsJsonObject();
        JsonObject merged = merge(defaults, user, "", notes, fallbackName);
        Shape shape;
        try {
            shape = GSON.fromJson(merged, Shape.class);
        } catch (JsonParseException e) {
            // merge checked every type, so this should not happen. If it does, the whole file falls back as one bad key
            // does.
            notes.add("config could not be read (" + e.getMessage() + "), using "
                    + (fallbackName.equals(DEFAULT) ? "defaults" : "the current values"));
            shape = GSON.fromJson(defaults, Shape.class);
        }
        HardcoreChecks.check(shape.hardcore, fallback.hardcore(),
                (path, problem) -> notes.add(path + ": " + problem + ", using " + fallbackName));
        return new Loaded(shape.hardcore, shape.messages.checked(fallback.texts(), notes, fallbackName));
    }

    // A file that can't be read (not UTF-8, say) is as unusable as one that isn't JSON. The decoder only says "Input
    // length = 1", so that case is named here.
    private static String problem(Exception e) {
        if (e instanceof CharacterCodingException) {
            return "config could not be read (not UTF-8 text)";
        }
        return (e instanceof IOException ? "config could not be read (" : "config is not valid JSON (") + e.getMessage() + ")";
    }

    private static JsonObject readObject(Path file) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("root is not an object");
        }
        return parsed.getAsJsonObject();
    }

    // The defaults, written to the file when it can be
    private static Result defaults(Path file, List<String> notes, boolean failed, @Nullable String backup) {
        Loaded loaded = Loaded.defaults();
        try {
            write(file, loaded);
        } catch (UncheckedIOException e) {
            notes.add("The config file could not be written (" + e.getCause().getMessage()
                    + "), so YOLT Hardcore runs on the defaults.");
        }
        return new Result(loaded, notes, failed, backup);
    }

    // Writes a temp file beside it, forces it to disk and moves it over the file, so neither a crash mid-write nor a
    // power cut just after it can leave half a file or an empty one
    private static void write(Path file, Loaded loaded) {
        String text = json(loaded);
        Path target = file.toAbsolutePath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copies the file to name. A name that already holds other bytes, perhaps the admin's whole file from an earlier
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
    // counts as different, since a copy too many is the safe side and a start must not stop over it
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

    // YOLT's ConfigLoader.merge, for this file's tree: the defaults' keys in their order, then the file's unknown ones
    private static JsonObject merge(JsonObject defaults, JsonObject user, String prefix, List<String> notes,
                                    String fallbackName) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            String key = entry.getKey();
            String path = prefix + key;
            JsonElement def = entry.getValue();
            JsonElement value = user.get(key);

            if (value == null) {
                result.add(key, def);
            } else if (NUMBER_MAPS.contains(path) || NUMBER_MAP_MAPS.contains(path)) {
                // Read whole, so the file's names are never unknown keys and a name it leaves out is gone
                boolean nested = NUMBER_MAP_MAPS.contains(path);
                if (nested ? isMapOfNumberMaps(value) : isNumberMap(value)) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    notes.add(path + ": expected an object of " + (nested ? "objects of numbers" : "numbers")
                            + ", using " + fallbackName);
                }
            } else if (def.isJsonObject()) {
                if (value.isJsonObject()) {
                    result.add(key, merge(def.getAsJsonObject(), value.getAsJsonObject(), path + ".", notes, fallbackName));
                } else {
                    result.add(key, def);
                    notes.add(path + ": expected an object, using " + fallbackName);
                }
            } else if (def.isJsonArray()) {
                if (isStringArray(value)) {
                    result.add(key, value);
                } else {
                    result.add(key, def);
                    notes.add(path + ": expected a list of strings, using " + fallbackName);
                }
            } else {
                String problem = primitiveProblem(def, value);
                if (problem == null) {
                    // Gson reads an enum by the name the file is written with and, failing that, by its Java name, so
                    // "RED" would pass for red, a name the docs never give. Any other name goes on as no value, which
                    // HardcoreChecks then refuses as it does an unknown one.
                    result.add(key, documentedName(path, value) ? value : JsonNull.INSTANCE);
                } else {
                    result.add(key, def);
                    notes.add(path + ": " + problem + ", using " + fallbackName);
                }
            }
        }
        for (String key : user.keySet()) {
            if (!defaults.has(key)) {
                // Most likely a misspelling, whose real key then quietly takes its fallback
                notes.add(prefix + key + ": unknown key, ignored");
            }
        }
        return result;
    }

    // False only for an enum key's text that isn't one of its names in the file
    private static boolean documentedName(String path, JsonElement value) {
        Class<?> type = Shape.class;
        for (String part : path.split("\\.")) {
            try {
                type = type.getDeclaredField(part).getType();
            } catch (NoSuchFieldException e) {
                return true;
            }
        }
        if (!type.isEnum()) {
            return true;
        }
        String text = value.getAsString();
        for (Object constant : type.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            try {
                SerializedName serialized = type.getField(name).getAnnotation(SerializedName.class);
                if ((serialized == null ? name : serialized.value()).equals(text)) {
                    return true;
                }
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
        return false;
    }

    // Every value a finite number, so Gson can read it into a map of doubles and write it back
    private static boolean isNumberMap(JsonElement value) {
        if (!value.isJsonObject()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            JsonElement item = entry.getValue();
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber() || !Double.isFinite(item.getAsDouble())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMapOfNumberMaps(JsonElement value) {
        if (!value.isJsonObject()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (!isNumberMap(entry.getValue())) {
                return false;
            }
        }
        return true;
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

    /** Returns why the file's value can't replace the default, or null if it can. */
    private static @Nullable String primitiveProblem(JsonElement def, JsonElement value) {
        if (!value.isJsonPrimitive()) {
            return "wrong type";
        }
        JsonPrimitive u = value.getAsJsonPrimitive();
        JsonPrimitive d = def.getAsJsonPrimitive();
        if (d.isBoolean()) {
            return u.isBoolean() ? null : "expected true or false";
        }
        if (d.isString()) {
            return u.isString() ? null : "expected a string";
        }
        if (!u.isNumber()) {
            return "expected a number";
        }
        // 1e400 reads as infinity, which Gson can neither load into the settings nor write back
        if (!Double.isFinite(u.getAsDouble())) {
            return "expected a finite number";
        }
        // Whole-number settings can't take 2.5. Gson's tree holds an int default as an Integer or a Long; there are no
        // long keys.
        Number defaultNumber = d.getAsNumber();
        if (defaultNumber instanceof Integer || defaultNumber instanceof Long) {
            double v = u.getAsDouble();
            if (v != Math.floor(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                return "expected a whole number that fits in an int";
            }
            // Gson can't make an int of some numbers that read fine as a double (1e-99999)
            try {
                u.getAsInt();
            } catch (NumberFormatException e) {
                return "expected a whole number that fits in an int";
            }
        }
        return null;
    }
}
