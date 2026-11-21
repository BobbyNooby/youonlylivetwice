package bobbynooby.dev.anvils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The config file of the mod on its own, config/yolt-anvils.json. It is read key by key, so one bad value only keeps
 * that value as it was: the defaults at a server start, the values in use at a reload.
 * <p>
 * A missing file is written with those values. Keys missing from a file are added to it, unless the file also holds a
 * value that was refused: that file is left as it was typed, so its owner sees the mistake where they made it.
 */
public final class AnvilConfig {
    public static final String FILE = "yolt-anvils.json";
    static final String ENABLED = "enabled";
    static final String ENCHANT_COST = "enchantCost";
    static final String REPAIR_COST = "repairCost";
    static final String RENAME_COST = "renameCost";
    static final String BREAK_CHANCE = "breakChance";
    private static final List<String> KEYS = List.of(ENABLED, ENCHANT_COST, REPAIR_COST, RENAME_COST, BREAK_CHANCE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AnvilConfig() {}

    /**
     * What a read gave. settings are what to use from now on. notes has a line for each key that was refused, missing
     * or unknown, and for a file written. failed is true when the file couldn't be read at all, and settings are then
     * the fallback.
     */
    public record Result(AnvilSettings settings, List<String> notes, boolean failed) {}

    /** What the keys of one JSON object gave, before any file is written. */
    record Parsed(AnvilSettings settings, List<String> refused, List<String> missing, List<String> unknown) {}

    /** Reads the file, writing it first with the fallback values if it isn't there. */
    public static Result load(Path file, AnvilSettings fallback) {
        if (!Files.exists(file)) {
            List<String> notes = new ArrayList<>();
            notes.add(write(file, fallback, "wrote " + file.getFileName() + " with the values in use"));
            return new Result(fallback, notes, false);
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result(fallback, List.of("couldn't read " + file.getFileName() + ": " + e), true);
        }
        // Some editors start a UTF-8 file with a byte order mark, which isn't JSON
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return new Result(fallback, List.of(file.getFileName() + " isn't valid JSON: " + e.getMessage()), true);
        }
        if (!root.isJsonObject()) {
            return new Result(fallback, List.of(file.getFileName() + " must hold one JSON object, {...}"), true);
        }
        Parsed parsed = parse(root.getAsJsonObject(), fallback);
        List<String> notes = new ArrayList<>(parsed.refused());
        notes.addAll(parsed.unknown());
        if (!parsed.missing().isEmpty()) {
            if (parsed.refused().isEmpty()) {
                notes.add(write(file, parsed.settings(), "added " + String.join(", ", parsed.missing()) + " to "
                        + file.getFileName()));
            } else {
                notes.add("missing " + String.join(", ", parsed.missing()) + ", left out of the file until the refused "
                        + "values are fixed");
            }
        }
        return new Result(parsed.settings(), notes, false);
    }

    /** Each key's value, or the fallback's for a key that is missing or refused. */
    static Parsed parse(JsonObject json, AnvilSettings fallback) {
        List<String> refused = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String key : json.keySet()) {
            if (!KEYS.contains(key)) {
                unknown.add(key + " isn't a setting and does nothing");
            }
        }
        boolean enabled = flag(json, ENABLED, fallback.enabled(), refused, missing);
        int enchantCost = price(json, ENCHANT_COST, fallback.enchantCost(), refused, missing);
        int repairCost = price(json, REPAIR_COST, fallback.repairCost(), refused, missing);
        int renameCost = price(json, RENAME_COST, fallback.renameCost(), refused, missing);
        double breakChance = chance(json, BREAK_CHANCE, fallback.breakChance(), refused, missing);
        return new Parsed(new AnvilSettings(enabled, enchantCost, repairCost, renameCost, breakChance), refused, missing,
                unknown);
    }

    /** The JSON for these settings, every key in the order the file lists them. */
    static String json(AnvilSettings settings) {
        JsonObject json = new JsonObject();
        json.addProperty(ENABLED, settings.enabled());
        json.addProperty(ENCHANT_COST, settings.enchantCost());
        json.addProperty(REPAIR_COST, settings.repairCost());
        json.addProperty(RENAME_COST, settings.renameCost());
        json.addProperty(BREAK_CHANCE, settings.breakChance());
        return GSON.toJson(json) + "\n";
    }

    private static boolean flag(JsonObject json, String key, boolean fallback, List<String> refused, List<String> missing) {
        JsonPrimitive value = primitive(json, key, missing);
        if (value == null) {
            return fallback;
        }
        if (!value.isBoolean()) {
            refused.add(key + " must be true or false, kept " + fallback);
            return fallback;
        }
        return value.getAsBoolean();
    }

    private static int price(JsonObject json, String key, int fallback, List<String> refused, List<String> missing) {
        JsonPrimitive value = primitive(json, key, missing);
        if (value == null) {
            return fallback;
        }
        double number = value.isNumber() ? value.getAsDouble() : Double.NaN;
        // A whole number in range; 5.0 is 5, and NaN fails the range
        if (number != Math.rint(number) || !(number >= 1 && number <= AnvilSettings.MAX_PRICE)) {
            refused.add(key + " must be a whole number from 1 to " + AnvilSettings.MAX_PRICE + ", kept " + fallback);
            return fallback;
        }
        return (int) number;
    }

    private static double chance(JsonObject json, String key, double fallback, List<String> refused, List<String> missing) {
        JsonPrimitive value = primitive(json, key, missing);
        if (value == null) {
            return fallback;
        }
        double number = value.isNumber() ? value.getAsDouble() : Double.NaN;
        if (!AnvilSettings.isChance(number)) {
            refused.add(key + " must be a number from 0 to 1, kept " + fallback);
            return fallback;
        }
        return number;
    }

    // A key that is absent or null counts as missing; an object or array where a value belongs is a primitive of no type
    private static @Nullable JsonPrimitive primitive(JsonObject json, String key, List<String> missing) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            missing.add(key);
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsJsonPrimitive() : new JsonPrimitive("");
    }

    // Through a file beside it, so a crash mid-write never leaves half a config
    private static String write(Path file, AnvilSettings settings, String done) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, json(settings), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return done;
        } catch (IOException e) {
            return "couldn't write " + file.getFileName() + ": " + e;
        }
    }
}
