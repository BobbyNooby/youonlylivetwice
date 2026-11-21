package bobbynooby.dev.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A tiny mod's config file: one flat JSON object, read key by key, so one bad value only keeps that value as it was: the
 * defaults at a server start, the values in use at a reload.
 * <p>
 * A missing file is written with those values. Keys missing from a file are added to it, unless the file also holds a
 * value that was refused: that file is left as it was typed, so its owner sees the mistake where they made it.
 *
 * @param <S> the settings record the file holds
 */
public final class SettingsFile<S> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String name;
    private final List<Key<S>> keys;
    private final List<String> names;
    private final Function<Values, S> build;

    /**
     * What a read gave. settings are what to use from now on. notes has a line for each key that was refused, missing
     * or unknown, and for a file written. failed is true when the file couldn't be read at all, and settings are then
     * the fallback.
     */
    public record Result<S>(S settings, List<String> notes, boolean failed) {}

    /** What the keys of one JSON object gave, before any file is written. */
    public record Parsed<S>(S settings, List<String> refused, List<String> missing, List<String> unknown) {}

    /**
     * name is the file's name in the config folder. keys are read and written in the order given, and build makes the
     * settings record from the value each key read.
     */
    public SettingsFile(String name, List<Key<S>> keys, Function<Values, S> build) {
        this.name = name;
        this.keys = List.copyOf(keys);
        this.names = this.keys.stream().map(Key::name).toList();
        this.build = build;
    }

    public String name() {
        return name;
    }

    /** Reads the file, writing it first with the fallback values if it isn't there. */
    public Result<S> load(Path file, S fallback) {
        if (!Files.exists(file)) {
            List<String> notes = new ArrayList<>();
            notes.add(write(file, fallback, "wrote " + file.getFileName() + " with the values in use"));
            return new Result<>(fallback, notes, false);
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result<>(fallback, List.of("couldn't read " + file.getFileName() + ": " + e), true);
        }
        // Some editors start a UTF-8 file with a byte order mark, which isn't JSON
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return new Result<>(fallback, List.of(file.getFileName() + " isn't valid JSON: " + e.getMessage()), true);
        }
        if (!root.isJsonObject()) {
            return new Result<>(fallback, List.of(file.getFileName() + " must hold one JSON object, {...}"), true);
        }
        Parsed<S> parsed = parse(root.getAsJsonObject(), fallback);
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
        return new Result<>(parsed.settings(), notes, false);
    }

    /** Each key's value, or the fallback's for a key that is missing or refused. */
    public Parsed<S> parse(JsonObject json, S fallback) {
        List<String> refused = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String key : json.keySet()) {
            if (!names.contains(key)) {
                unknown.add(key + " isn't a setting and does nothing");
            }
        }
        Map<String, Object> values = new HashMap<>();
        for (Key<S> key : keys) {
            values.put(key.name(), key.read(json, fallback, refused, missing));
        }
        return new Parsed<>(build.apply(new Values(values)), refused, missing, unknown);
    }

    /** The JSON for these settings, every key in the order the file lists them. */
    public String json(S settings) {
        JsonObject json = new JsonObject();
        for (Key<S> key : keys) {
            json.add(key.name(), key.json(settings));
        }
        return GSON.toJson(json) + "\n";
    }

    // Through a file beside it, so a crash mid-write never leaves half a config
    private String write(Path file, S settings, String done) {
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
