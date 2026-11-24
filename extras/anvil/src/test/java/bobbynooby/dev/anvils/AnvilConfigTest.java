package bobbynooby.dev.anvils;

import bobbynooby.dev.anvils.shared.Key;
import bobbynooby.dev.anvils.shared.SettingsFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnvilConfigTest {
    private static final AnvilSettings CURRENT = new AnvilSettings(false, 9, 8, 7, 0.5);

    @TempDir
    Path folder;

    private static SettingsFile.Parsed<AnvilSettings> parse(String json, AnvilSettings fallback) {
        return AnvilConfig.parse(JsonParser.parseString(json).getAsJsonObject(), fallback);
    }

    // A file system without links skips the test
    private static Path link(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "no symbolic links here: " + e);
            throw e;
        }
    }

    private static boolean mentions(List<String> lines, String key) {
        return lines.stream().anyMatch(line -> line.startsWith(key));
    }

    @Test
    void aWholeFileIsReadAsWritten() {
        SettingsFile.Parsed<AnvilSettings> parsed = parse("""
                {"enabled": false, "enchantCost": 39, "repairCost": 2, "renameCost": 1, "breakChance": 0}""",
                AnvilSettings.DEFAULTS);
        assertEquals(new AnvilSettings(false, 39, 2, 1, 0.0), parsed.settings());
        assertTrue(parsed.refused().isEmpty() && parsed.missing().isEmpty() && parsed.unknown().isEmpty(), parsed.toString());
    }

    @Test
    void anEmptyObjectKeepsEveryFallbackAndMissesEveryKey() {
        SettingsFile.Parsed<AnvilSettings> parsed = parse("{}", CURRENT);
        assertEquals(CURRENT, parsed.settings());
        assertEquals(List.of("enabled", "enchantCost", "repairCost", "renameCost", "breakChance"), parsed.missing());
        assertTrue(parsed.refused().isEmpty());
    }

    // 0 can't be paid and 40 shows "Too Expensive!" on the client
    @Test
    void pricesOutsideOneToThirtyNineAreRefused() {
        for (String bad : List.of("0", "40", "-1", "1000", "2147483648", "5.5", "\"5\"", "true", "[5]", "{}")) {
            SettingsFile.Parsed<AnvilSettings> parsed = parse("{\"repairCost\": " + bad + "}", CURRENT);
            assertEquals(CURRENT.repairCost(), parsed.settings().repairCost(), bad);
            assertTrue(mentions(parsed.refused(), "repairCost"), bad + ": " + parsed.refused());
        }
        for (int good : List.of(1, 39)) {
            assertEquals(good, parse("{\"enchantCost\": " + good + "}", CURRENT).settings().enchantCost());
        }
        assertEquals(5, parse("{\"renameCost\": 5.0}", CURRENT).settings().renameCost(), "a whole 5.0 is 5");
    }

    @Test
    void breakChanceOutsideZeroToOneIsRefused() {
        for (String bad : List.of("-0.01", "1.01", "2", "\"0.5\"", "false", "NaN")) {
            SettingsFile.Parsed<AnvilSettings> parsed = parse("{\"breakChance\": " + bad + "}", CURRENT);
            assertEquals(CURRENT.breakChance(), parsed.settings().breakChance(), bad);
            assertTrue(mentions(parsed.refused(), "breakChance"), bad + ": " + parsed.refused());
        }
        assertEquals(1.0, parse("{\"breakChance\": 1}", CURRENT).settings().breakChance());
        assertEquals(0.0, parse("{\"breakChance\": 0.0}", CURRENT).settings().breakChance());
    }

    @Test
    void enabledMustBeABoolean() {
        for (String bad : List.of("1", "\"true\"", "\"yes\"", "{}")) {
            SettingsFile.Parsed<AnvilSettings> parsed = parse("{\"enabled\": " + bad + "}", CURRENT);
            assertFalse(parsed.settings().enabled(), bad);
            assertTrue(mentions(parsed.refused(), "enabled"), bad);
        }
    }

    // One bad value doesn't take the good ones with it
    @Test
    void oneRefusedKeyLeavesTheOthersRead() {
        SettingsFile.Parsed<AnvilSettings> parsed = parse("""
                {"enabled": true, "enchantCost": 40, "repairCost": 3, "renameCost": 2, "breakChance": 0.25}""", CURRENT);
        assertEquals(new AnvilSettings(true, CURRENT.enchantCost(), 3, 2, 0.25), parsed.settings());
        assertEquals(1, parsed.refused().size(), parsed.refused().toString());
    }

    @Test
    void aNullValueCountsAsMissing() {
        SettingsFile.Parsed<AnvilSettings> parsed = parse("{\"repairCost\": null}", CURRENT);
        assertTrue(parsed.missing().contains("repairCost"));
        assertTrue(parsed.refused().isEmpty());
    }

    @Test
    void unknownKeysAreNotedAndChangeNothing() {
        SettingsFile.Parsed<AnvilSettings> parsed = parse("{\"enchantcost\": 3, \"tooExpensive\": false}", CURRENT);
        assertEquals(CURRENT, parsed.settings());
        assertTrue(mentions(parsed.unknown(), "enchantcost") && mentions(parsed.unknown(), "tooExpensive"), parsed.unknown().toString());
    }

    @Test
    void writtenJsonReadsBackTheSame() {
        AnvilSettings settings = new AnvilSettings(false, 12, 3, 2, 0.05);
        JsonObject json = JsonParser.parseString(AnvilConfig.json(settings)).getAsJsonObject();
        SettingsFile.Parsed<AnvilSettings> parsed = AnvilConfig.parse(json, AnvilSettings.DEFAULTS);
        assertEquals(settings, parsed.settings());
        assertTrue(parsed.missing().isEmpty());
        assertEquals(List.of("enabled", "enchantCost", "repairCost", "renameCost", "breakChance"), List.copyOf(json.keySet()));
    }

    @Test
    void aMissingFileIsWrittenWithTheFallback() throws IOException {
        Path file = folder.resolve("config").resolve(AnvilConfig.FILE);
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, CURRENT);
        assertFalse(result.failed());
        assertEquals(CURRENT, result.settings());
        assertTrue(Files.exists(file));
        assertEquals(AnvilConfig.json(CURRENT), Files.readString(file));
        try (var left = Files.list(file.getParent())) {
            assertEquals(1, left.count(), "a temporary file was left beside the config");
        }
    }

    @Test
    void missingKeysAreAddedToTheFile() throws IOException {
        Path file = folder.resolve(AnvilConfig.FILE);
        Files.writeString(file, "{\"repairCost\": 3}");
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, AnvilSettings.DEFAULTS);
        AnvilSettings expected = new AnvilSettings(true, 6, 3, 1, 0.12);
        assertEquals(expected, result.settings());
        assertEquals(AnvilConfig.json(expected), Files.readString(file));
        assertTrue(result.notes().stream().anyMatch(note -> note.startsWith("added enabled, enchantCost, renameCost, breakChance")),
                result.notes().toString());
    }

    // The mistake stays where its owner typed it
    @Test
    void aFileWithARefusedValueIsLeftAsTyped() throws IOException {
        Path file = folder.resolve(AnvilConfig.FILE);
        String typed = "{\"enchantCost\": 50}";
        Files.writeString(file, typed);
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, AnvilSettings.DEFAULTS);
        assertEquals(AnvilSettings.DEFAULTS, result.settings());
        assertEquals(typed, Files.readString(file));
        assertTrue(mentions(result.notes(), "enchantCost"), result.notes().toString());
        assertFalse(result.failed());
    }

    @Test
    void aFileThatIsntJsonChangesNothing() throws IOException {
        Path file = folder.resolve(AnvilConfig.FILE);
        for (String bad : List.of("{ \"enabled\": ", "[1, 2]", "", "5", "\"text\"")) {
            Files.writeString(file, bad);
            SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, CURRENT);
            assertTrue(result.failed(), "'" + bad + "' was read");
            assertEquals(CURRENT, result.settings(), bad);
            assertEquals(bad, Files.readString(file), "'" + bad + "' was written over");
        }
    }

    @Test
    void aFileThatIsntTextChangesNothing() throws IOException {
        Path file = folder.resolve(AnvilConfig.FILE);
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28, '{', '}'});
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, CURRENT);
        assertTrue(result.failed());
        assertEquals(CURRENT, result.settings());
    }

    @Test
    void aFolderWhereTheFileShouldBeChangesNothing() throws IOException {
        Path file = folder.resolve(AnvilConfig.FILE);
        Files.createDirectories(file);
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, CURRENT);
        assertTrue(result.failed(), result.notes().toString());
        assertEquals(CURRENT, result.settings());
    }

    @Test
    void aFileWithAByteOrderMarkStillReads() throws IOException {
        Path file = folder.resolve(AnvilConfig.FILE);
        Files.writeString(file, "﻿" + AnvilConfig.json(CURRENT), StandardCharsets.UTF_8);
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, AnvilSettings.DEFAULTS);
        assertFalse(result.failed(), result.notes().toString());
        assertEquals(CURRENT, result.settings());
    }

    // An admin's config may be a link to a file kept elsewhere: a rewrite writes that file and leaves the link
    @Test
    void aLinkedFileIsWrittenThroughTheLink() throws IOException {
        Path managed = Files.createDirectories(folder.resolve("managed")).resolve(AnvilConfig.FILE);
        Files.writeString(managed, "{\"repairCost\": 3}");
        Path file = link(Files.createDirectories(folder.resolve("config")).resolve(AnvilConfig.FILE), managed);
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, AnvilSettings.DEFAULTS);
        AnvilSettings expected = new AnvilSettings(true, 6, 3, 1, 0.12);
        assertEquals(expected, result.settings());
        assertTrue(result.notes().stream().anyMatch(note -> note.startsWith("added ")), result.notes().toString());
        assertTrue(Files.isSymbolicLink(file), "the link was written over");
        assertEquals(managed, Files.readSymbolicLink(file));
        assertEquals(AnvilConfig.json(expected), Files.readString(managed));
        try (var left = Files.list(managed.getParent())) {
            assertEquals(1, left.count(), "a temporary file was left beside the linked file");
        }
        try (var left = Files.list(file.getParent())) {
            assertEquals(1, left.count(), "a file was left beside the link");
        }
    }

    // The rewrite is a new file, which gets what any new file in its folder gets, not an owner-only temporary file's
    @Test
    void aRewrittenFileHasTheModeOfANewFile() throws IOException {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class),
                "no POSIX permissions here");
        Path file = folder.resolve(AnvilConfig.FILE);
        Files.writeString(file, "{\"repairCost\": 3}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Path probe = Files.writeString(folder.resolve("new.txt"), "");
        Set<PosixFilePermission> newFile = Files.getPosixFilePermissions(probe);
        assumeFalse(newFile.equals(PosixFilePermissions.fromString("rw-------")), "a new file here is owner-only anyway");
        SettingsFile.Result<AnvilSettings> result = AnvilConfig.load(file, AnvilSettings.DEFAULTS);
        assertTrue(result.notes().stream().anyMatch(note -> note.startsWith("added ")), result.notes().toString());
        assertEquals(PosixFilePermissions.toString(newFile), PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    // A link to a file that isn't there can't be written through: the note says so, and the link stays as it was
    @Test
    void aLinkToNowhereEndsInTheNote() throws IOException {
        Path nowhere = folder.resolve("gone").resolve(AnvilConfig.FILE);
        Path file = link(folder.resolve(AnvilConfig.FILE), nowhere);
        SettingsFile.Result<AnvilSettings> result = assertDoesNotThrow(() -> AnvilConfig.load(file, CURRENT));
        assertEquals(CURRENT, result.settings());
        assertFalse(result.failed());
        assertEquals(1, result.notes().size(), result.notes().toString());
        assertTrue(result.notes().getFirst().startsWith("couldn't write " + AnvilConfig.FILE + ": "), result.notes().getFirst());
        assertTrue(Files.isSymbolicLink(file), "the link was written over");
        assertFalse(Files.exists(nowhere));
    }

    @Test
    void theDefaultsAreValid() {
        AnvilSettings d = AnvilSettings.DEFAULTS;
        assertTrue(d.enabled() && AnvilSettings.isPrice(d.enchantCost()) && AnvilSettings.isPrice(d.repairCost())
                && AnvilSettings.isPrice(d.renameCost()) && AnvilSettings.isChance(d.breakChance()));
        assertEquals("rename 1, repair 5, enchant 6", d.prices());
    }

    // Two keys of one name would share one entry in the file, so a mod built that way stops before any file is read
    @Test
    void twoKeysOfTheSameNameAreRefused() {
        List<Key<AnvilSettings>> keys = List.of(
                Key.flag(AnvilConfig.ENABLED, AnvilSettings::enabled),
                Key.whole(AnvilConfig.REPAIR_COST, 1, AnvilSettings.MAX_PRICE, AnvilSettings::repairCost),
                Key.whole(AnvilConfig.REPAIR_COST, 1, AnvilSettings.MAX_PRICE, AnvilSettings::renameCost));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SettingsFile<>(AnvilConfig.FILE, keys, values -> AnvilSettings.DEFAULTS));
        assertEquals("yolt-anvils.json has two keys called repairCost", thrown.getMessage());
    }
}
