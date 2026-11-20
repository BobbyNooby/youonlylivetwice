package bobbynooby.dev.config;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @TempDir
    Path dir;

    private ConfigLoader.Result loadJson(String json) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return ConfigLoader.load(file, null, true);
    }

    private static ConfigKey key(String path) {
        return ConfigKeys.find(path).orElseThrow(() -> new AssertionError("no key " + path));
    }

    // Every text as YOLT said it before the section existed, so a server that never touches it hears nothing new
    private static Map<String, String> todaysTexts() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("messages.lockoutDied", "You died: {death}");
        d.put("messages.lockoutDiedPlain", "You died.");
        d.put("messages.lockoutWait", "Try again in {wait}.");
        d.put("messages.help", "But nobody answered....");
        d.put("messages.serverFull", "You can't join right now. Try again later.");
        d.put("messages.graveAt", "Your grave is at {x}, {y}, {z} in {dimension}.");
        d.put("messages.graveIn", "Your grave is in {dimension}.");
        d.put("messages.reviveName", "Revive {name}");
        d.put("messages.graveTitle", "{name}'s Grave");
        d.put("messages.bloodMoonWarningTitle", "The moon will rise red tonight…");
        d.put("messages.bloodMoonWarningLine", "A blood moon rises tonight. Find somewhere safe before dark.");
        d.put("messages.bloodMoonRisenLine", "The blood moon has risen. Mobs are stronger and more numerous until dawn.");
        d.put("messages.bloodMoonUpLine", "A blood moon is up. Mobs are stronger and more numerous until dawn.");
        d.put("messages.bloodMoonSetLine", "The blood moon has set.");
        d.put("messages.bloodMoonBarTitle", "Blood Moon");
        return d;
    }

    // Equal as maps, so a text without a line here fails, and so does a line with no text. Each is a text the command
    // can set, not a file-only list.
    @Test
    void everyTextShipsAsYoltSaidItBefore() {
        YoltConfig fresh = new YoltConfig();
        Map<String, String> actual = new LinkedHashMap<>();
        for (ConfigKey key : ConfigKeys.all()) {
            if (key.path().startsWith("messages.")) {
                actual.put(key.path(), key.show(fresh));
                assertEquals(ConfigKey.Kind.STRING, key.kind(), key.path());
                assertFalse(ConfigKeys.fileOnly(key.path()), key.path());
            }
        }
        assertEquals(todaysTexts(), actual);
        assertEquals(List.of(), Messages.unknownPlaceholders(fresh));
    }

    @Test
    void fillsEachPlaceholderOnce() {
        assertEquals("Your grave is at 1, -2, 3 in the Nether.", Messages.fill(new YoltConfig().messages.graveAt,
                "x", "1", "y", "-2", "z", "3", "dimension", "the Nether"));
        // A value holding braces isn't filled in again, and a word it wasn't given stays as typed
        assertEquals("{y} and 2 {z}", Messages.fill("{x} and {y} {z}", "x", "{y}", "y", "2"));
        // Nor is a value read as a regex replacement
        assertEquals("You died: a $1 \\ b", Messages.fill("You died: {death}", "death", "a $1 \\ b"));
        assertEquals("{ name } {}", Messages.fill("{ name } {}", "name", "Bob"));
    }

    // A text that drops a placeholder it must keep, or is too long, falls back on its own; colour codes and new lines
    // are fine. An unknown placeholder only gets a notice, outside the warnings, so it is kept as typed.
    @Test
    void checksRefuseADroppedPlaceholderAndOnlyNoteAnUnknownOne() throws IOException {
        ConfigLoader.Result r = loadJson(new Gson().toJson(Map.of("messages", Map.of(
                "lockoutWait", "Come back later.",
                "graveAt", "Dig at {x} {y}",
                "reviveName", "Revive paper",
                "help", "x".repeat(257),
                "graveTitle", "{player}'s pile",
                "lockoutDied", "\u00a74Gone:\n{death}"))));
        YoltConfig fresh = new YoltConfig();
        assertEquals(fresh.messages.lockoutWait, r.config().messages.lockoutWait);
        assertEquals(fresh.messages.graveAt, r.config().messages.graveAt);
        assertEquals(fresh.messages.reviveName, r.config().messages.reviveName);
        assertEquals(fresh.messages.help, r.config().messages.help);
        assertEquals("{player}'s pile", r.config().messages.graveTitle);
        assertEquals("\u00a74Gone:\n{death}", r.config().messages.lockoutDied);
        List<String> expected = List.of(
                "messages.lockoutWait: must keep {wait}, using default",
                "messages.help: must be at most 256 characters, using default",
                "messages.graveAt: must keep {z}, using default",
                "messages.reviveName: must keep {name}, using default");
        assertEquals(expected, r.warnings().stream().filter(w -> w.startsWith("messages.")).toList());
        assertEquals(List.of("messages.graveTitle: has {player}, which it doesn't fill in, so it shows as typed"),
                Messages.unknownPlaceholders(r.config()));

        ConfigLoader.Result none = loadJson("{\"messages\":{\"graveAt\":\"Somewhere.\"}}");
        assertTrue(none.warnings().contains("messages.graveAt: must keep {x}, {y} and {z}, using default"), none.warnings().toString());
        // 256 fit
        assertEquals("y".repeat(256), loadJson("{\"messages\":{\"help\":\"" + "y".repeat(256) + "\"}}").config().messages.help);
    }

    @Test
    void reloadKeepsTheLiveText() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"messages\":{\"lockoutWait\":\"Later.\",\"help\":\"Silence.\"}}");
        YoltConfig live = new YoltConfig();
        live.messages.lockoutWait = "Wait {wait}.";
        ConfigLoader.Result r = ConfigLoader.load(file, live, false);
        assertEquals("Wait {wait}.", r.config().messages.lockoutWait);
        assertEquals("Silence.", r.config().messages.help);
        assertEquals(List.of("messages.lockoutWait: must keep {wait}, using the current value"), r.warnings());
    }

    // The command refuses what a load would put back, and lets an unknown placeholder through for its reply to name
    @Test
    void setRefusesADroppedPlaceholder() throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{}");
        ConfigLoader.Change refused = ConfigLoader.setKey(file, new YoltConfig(), key("messages.lockoutWait"), new JsonPrimitive("Soon."));
        assertEquals("messages.lockoutWait: must keep {wait}", refused.problem());
        assertNull(refused.config());
        assertEquals("messages.lockoutWait must keep {wait}.", ConfigKeys.asReply(refused.problem()));

        ConfigLoader.Change set = ConfigLoader.setKey(file, new YoltConfig(), key("messages.graveIn"), new JsonPrimitive("In {dim}."));
        assertNull(set.problem());
        assertEquals("In {dim}.", set.config().messages.graveIn);
        assertEquals("messages.graveIn has {dim}, which it doesn't fill in, so it shows as typed.",
                ConfigKeys.asReply(Messages.unknownPlaceholders(set.config()).getFirst()));
    }
}
