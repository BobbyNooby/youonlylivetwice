package bobbynooby.dev.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigKeysTest {
    // Stand-ins for kinds of key YoltConfig has none of today
    static class Fixture {
        public static int NOT_A_KEY = 1;
        @Applies(Applies.When.RESTART) public int port = 25565;
        public String motto = "hi";
        public NestedSection nested = new NestedSection();

        public static class NestedSection {
            public boolean on = true;
        }
    }

    static class UnknownKind {
        public long big = 1;
    }

    // Every leaf of the file a fresh config writes, found from the JSON rather than the fields
    private static void leaves(JsonObject json, String prefix, Set<String> paths) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                leaves(entry.getValue().getAsJsonObject(), prefix + entry.getKey() + ".", paths);
            } else {
                paths.add(prefix + entry.getKey());
            }
        }
    }

    private static ConfigKey key(String path) {
        return ConfigKeys.find(path).orElseThrow(() -> new AssertionError("no key " + path));
    }

    private static String badValue(String path, String text) {
        return assertThrows(ConfigKey.BadValue.class, () -> key(path).parse(text), path + " took " + text).getMessage();
    }

    @Test
    void everyLeafHasAKey() {
        List<String> paths = ConfigKeys.all().stream().map(ConfigKey::path).toList();
        for (String path : List.of("worldBorder.diameter", "hide.locatorBar", "bodies.duration", "restrictCommands")) {
            assertTrue(paths.contains(path), "no key " + path);
        }
        assertFalse(paths.contains("configVersion"));

        Set<String> leaves = new HashSet<>();
        leaves(new Gson().toJsonTree(new YoltConfig()).getAsJsonObject(), "", leaves);
        // The version is no setting, and a list can't be typed as one value, so those two stay in the file
        assertTrue(leaves.remove("configVersion"));
        assertTrue(leaves.remove("serverList.hoverLines"));
        assertEquals(leaves, new HashSet<>(paths));
        assertEquals(leaves.size(), paths.size(), "a path is listed twice: " + paths);
        assertTrue(ConfigKeys.find("serverList.hoverLines").isEmpty());
    }

    // /yolt config can then say to change the list in the file rather than call it unknown
    @Test
    void hoverLinesIsFileOnly() {
        assertTrue(ConfigKeys.fileOnly("serverList.hoverLines"));
        assertTrue(ConfigKeys.find("serverList.hoverLines").isEmpty());
        // The version is no setting at all, and a real key or a typo is not file-only
        assertFalse(ConfigKeys.fileOnly("configVersion"));
        assertFalse(ConfigKeys.fileOnly("chat.radius"));
        assertFalse(ConfigKeys.fileOnly("serverList.hoverLine"));
    }

    @Test
    void parsesEachKind() throws ConfigKey.BadValue {
        assertEquals(new JsonPrimitive(true), key("hide.tabList").parse("true"));
        assertEquals(new JsonPrimitive(false), key("hide.tabList").parse("false"));
        assertEquals("hide.tabList takes true or false, not 'yes'.", badValue("hide.tabList", "yes"));

        assertEquals(new JsonPrimitive(32), key("chat.radius").parse("32"));
        assertEquals("chat.radius takes a whole number, not 'abc'.", badValue("chat.radius", "abc"));
        assertEquals("chat.radius takes a whole number, not '2.5'.", badValue("chat.radius", "2.5"));

        assertEquals(new JsonPrimitive(2.5), key("worldBorder.endScale").parse("2.5"));
        assertEquals("worldBorder.endScale takes a number, not '1e400'.", badValue("worldBorder.endScale", "1e400"));
        assertEquals("worldBorder.endScale takes a number, not 'NaN'.", badValue("worldBorder.endScale", "NaN"));

        assertEquals(new JsonPrimitive("self_only"), key("chat.mode").parse("self_only"));
        assertEquals("chat.mode takes proximity, self_only or global, not 'loud'.", badValue("chat.mode", "loud"));
        // The file's names exactly, not the Java ones
        badValue("chat.mode", "SELF_ONLY");
        assertEquals(List.of("proximity", "self_only", "global"), key("chat.mode").choices());

        assertEquals(new JsonPrimitive("offline"), key("bodies.duration").parse("offline"));
        assertEquals(new JsonPrimitive(30), key("bodies.duration").parse("30"));
        assertEquals("bodies.duration takes offline or whole minutes of 1 or more, not '0'.", badValue("bodies.duration", "0"));

        assertEquals(ConfigKey.Kind.BODY_DURATION, key("bodies.duration").kind());
        assertEquals(ConfigKey.Kind.DOUBLE, key("worldBorder.endScale").kind());
        assertEquals(ConfigKey.Kind.ENUM, key("graves.tellLocation").kind());
        assertEquals(List.of("never", "whenMoved", "always"), key("graves.tellLocation").choices());
    }

    @Test
    void showsValuesAsTheFileHasThem() {
        YoltConfig config = new YoltConfig();
        config.chat.mode = YoltConfig.ChatMode.SELF_ONLY;
        config.bodies.duration = new BodyDuration(30);
        assertEquals("self_only", key("chat.mode").show(config));
        assertEquals("30", key("bodies.duration").show(config));
        assertEquals("offline", key("bodies.duration").show(new YoltConfig()));
        assertEquals("2.0", key("worldBorder.endScale").show(config));
        assertEquals("48", key("chat.radius").show(config));
        assertEquals("true", key("hide.locatorBar").show(config));
    }

    @Test
    void restartFieldIsReported() {
        Map<String, ConfigKey> keys = new HashMap<>();
        ConfigKeys.of(Fixture.class).forEach(key -> keys.put(key.path(), key));
        assertEquals(Set.of("port", "motto", "nested.on"), keys.keySet());
        assertEquals(Applies.When.RESTART, keys.get("port").when());
        assertEquals(" (needs a restart)", keys.get("port").when().note());
        assertEquals(Applies.When.NOW, keys.get("nested.on").when());
    }

    @Test
    void stringFieldTakesTextAsIs() throws ConfigKey.BadValue {
        ConfigKey motto = ConfigKeys.of(Fixture.class).stream().filter(key -> key.path().equals("motto")).findFirst().orElseThrow();
        assertEquals(ConfigKey.Kind.STRING, motto.kind());
        assertEquals(new JsonPrimitive("hello world"), motto.parse("hello world"));
    }

    // A new key of a type nothing reads fails here rather than at the first /yolt config
    @Test
    void unknownKindOfKeyThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ConfigKeys.of(UnknownKind.class));
        assertTrue(e.getMessage().contains("big"), e.getMessage());
    }

    // Each key whose change waits for something, and the note a reply gives it
    @Test
    void keysSayWhenAChangeTakesEffect() {
        Map<String, Applies.When> waiting = new HashMap<>();
        for (ConfigKey key : ConfigKeys.all()) {
            if (key.when() != Applies.When.NOW) {
                waiting.put(key.path(), key.when());
            }
        }
        assertEquals(Map.of(
                "bodies.enabled", Applies.When.NEW_BODIES,
                "bodies.type", Applies.When.NEW_BODIES,
                "bodies.pose", Applies.When.NEW_BODIES,
                "bodies.movable", Applies.When.NEW_BODIES,
                "bodies.totems", Applies.When.NEW_BODIES,
                "deathLockout.cooldownDays", Applies.When.NEW_DEATHS,
                "deathLockout.countCreativeAndSpectatorDeaths", Applies.When.NEW_DEATHS,
                "graves.enabled", Applies.When.NEW_DEATHS,
                "graves.enderChestOnDeath", Applies.When.NEW_DEATHS,
                "skins.mode", Applies.When.NEXT_JOIN), waiting);
        assertEquals("", Applies.When.NOW.note());
        assertEquals(" (applies when a player next joins)", Applies.When.NEXT_JOIN.note());
        assertEquals(" (applies to new bodies)", Applies.When.NEW_BODIES.note());
        assertEquals(" (applies to deaths from now on)", Applies.When.NEW_DEATHS.note());
    }
}
