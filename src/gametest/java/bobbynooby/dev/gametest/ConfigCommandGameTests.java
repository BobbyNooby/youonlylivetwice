package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.world.LiveSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static bobbynooby.dev.gametest.CommandGameTests.run;

// /yolt config writes the config file and swaps the whole config object, so every test here runs with the others that do.
// Each puts the file's bytes back, then reloads them with the test server's own overrides on top.
public class ConfigCommandGameTests {
    private static final String HELD = "The world border is still held as the world saved it, since the config's border couldn't"
            + " be used at startup. Run /yolt reload to apply the file's border, or /yolt border to set one now.";

    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void showsCurrentValue(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertValueEqual(Settings.get().chat.radius, 48, "the test server's chat radius");
            helper.assertValueEqual(run(server, "yolt config chat.radius"), List.of("chat.radius is 48."), "chat.radius");
            helper.assertValueEqual(run(server, "yolt config chat.mode"), List.of("chat.mode is proximity."), "chat.mode");
            helper.assertValueEqual(run(server, "yolt config bodies.duration"), List.of("bodies.duration is offline."), "bodies.duration");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A boolean, a whole number, a decimal, a name from a list and a body duration, each read back from the live config
    // the command swapped in and from the file
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void setsEachKind(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertValueEqual(run(server, "yolt config hide.tabHeader false"), List.of("hide.tabHeader changed from true to false."),
                    "hide.tabHeader reply");
            helper.assertFalse(Settings.get().hide.tabHeader, "live hide.tabHeader is still true");
            helper.assertValueEqual(fileValue("hide.tabHeader").getAsBoolean(), false, "hide.tabHeader in the file");

            helper.assertValueEqual(run(server, "yolt config chat.radius 20"), List.of("chat.radius changed from 48 to 20."), "chat.radius reply");
            helper.assertValueEqual(Settings.get().chat.radius, 20, "live chat.radius");
            helper.assertValueEqual(fileValue("chat.radius").getAsInt(), 20, "chat.radius in the file");

            helper.assertValueEqual(run(server, "yolt config worldBorder.endScale 1.5"),
                    List.of("worldBorder.endScale changed from 2.0 to 1.5."), "worldBorder.endScale reply");
            helper.assertValueEqual(Settings.get().worldBorder.endScale, 1.5, "live worldBorder.endScale");
            helper.assertValueEqual(fileValue("worldBorder.endScale").getAsDouble(), 1.5, "worldBorder.endScale in the file");

            helper.assertValueEqual(run(server, "yolt config chat.mode global"), List.of("chat.mode changed from proximity to global."),
                    "chat.mode reply");
            helper.assertValueEqual(Settings.get().chat.mode, YoltConfig.ChatMode.GLOBAL, "live chat.mode");
            helper.assertValueEqual(fileValue("chat.mode").getAsString(), "global", "chat.mode in the file");

            helper.assertValueEqual(run(server, "yolt config bodies.duration 30"), List.of("bodies.duration changed from offline to 30."),
                    "bodies.duration reply");
            helper.assertValueEqual(Settings.get().bodies.duration.minutes(), 30, "live bodies.duration");
            helper.assertValueEqual(fileValue("bodies.duration").getAsInt(), 30, "bodies.duration in the file");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void badValueChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            assertRefused(helper, server, fileBefore, "yolt config chat.radius abc", "chat.radius takes a whole number, not 'abc'.");
            helper.assertValueEqual(Settings.get().chat.radius, 48, "live chat.radius");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The same limits a reload has, so the command can't save what the next load would throw away
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void outOfRangeValueChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            assertRefused(helper, server, fileBefore, "yolt config worldBorder.diameter 0", "worldBorder.diameter: must be 1 to 59999968.");
            assertRefused(helper, server, fileBefore, "yolt config chat.radius 0", "chat.radius: must be 1 or more.");
            assertRefused(helper, server, fileBefore, "yolt config worldBorder.endScale 1e400",
                    "worldBorder.endScale takes a number, not '1e400'.");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A typo, the file's version and a list only the file can hold, whether shown or set
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void unknownKeyIsRefused(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        String fileOnly = "serverList.hoverLines can only be changed in the config file. Change it there, then run /yolt reload.";
        try {
            assertRefused(helper, server, fileBefore, "yolt config chat.radus 3", "No config key is named chat.radus.");
            assertRefused(helper, server, fileBefore, "yolt config chat.radus", "No config key is named chat.radus.");
            assertRefused(helper, server, fileBefore, "yolt config configVersion 2", "No config key is named configVersion.");
            assertRefused(helper, server, fileBefore, "yolt config serverList.hoverLines hello", fileOnly);
            assertRefused(helper, server, fileBefore, "yolt config serverList.hoverLines", fileOnly);
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The change is applied the way a reload applies one. Turning the switch off leaves the rule as the admin has it, so
    // it is turned off and on again for the rule to change.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void appliesLikeReload(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        GameRules rules = server.getGameRules();
        boolean ruleBefore = rules.get(GameRules.LOCATOR_BAR);
        try {
            rules.set(GameRules.LOCATOR_BAR, true, server);
            helper.assertValueEqual(run(server, "yolt config hide.locatorBar false"), List.of("hide.locatorBar changed from true to false."),
                    "reply turning it off");
            helper.assertTrue(rules.get(GameRules.LOCATOR_BAR), "turning the switch off changed the rule");
            helper.assertValueEqual(run(server, "yolt config hide.locatorBar true"), List.of("hide.locatorBar changed from false to true."),
                    "reply turning it on");
            helper.assertFalse(rules.get(GameRules.LOCATOR_BAR), "the locator bar rule is still on");
        } finally {
            restore(server, fileBefore);
            rules.set(GameRules.LOCATOR_BAR, ruleBefore, server);
        }
        helper.succeed();
    }

    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void newBodiesKeyReplySaysSo(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertValueEqual(run(server, "yolt config bodies.pose standing"),
                    List.of("bodies.pose changed from prone to standing (applies to new bodies)."), "command output");
            helper.assertValueEqual(Settings.get().bodies.pose, YoltConfig.BodyPose.STANDING, "live bodies.pose");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The value already in place writes nothing, so the file keeps its bytes
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void sameValueSaysSoAndWritesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        YoltConfig before = Settings.get();
        try {
            helper.assertValueEqual(run(server, "yolt config chat.radius 48"), List.of("chat.radius is already 48."), "command output");
            helper.assertTrue(Settings.get() == before, "the live config was replaced");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(Settings.path()), fileBefore), "the config file was written");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The file says otherwise for each key, edited but not reloaded. Each command still writes the value the live config
    // already has, or the next reload would bring the file's value back after a reply that said it was set.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void liveValueIsWrittenWhenTheFileHasAnother(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        String borderOff = " The world border feature is off, so YOLT sizes no border.";
        try {
            YoltConfig live = Settings.get();
            helper.assertTrue(live.chat.radius == 48 && live.worldBorder.diameter == 10000 && live.worldBorder.endScale == 2.0
                    && live.deathLockout.cooldownDays == 7, "the test server's live values");
            JsonObject edited = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            edited.getAsJsonObject("chat").addProperty("radius", 20);
            edited.getAsJsonObject("worldBorder").addProperty("diameter", 20000);
            edited.getAsJsonObject("worldBorder").addProperty("endScale", 3.0);
            edited.getAsJsonObject("deathLockout").addProperty("cooldownDays", 3);
            Files.writeString(path, edited.toString());

            helper.assertValueEqual(run(server, "yolt config chat.radius 48"),
                    List.of("chat.radius is already 48, and the config file now says so too."), "/yolt config output");
            helper.assertValueEqual(fileValue("chat.radius").getAsInt(), 48, "chat.radius in the file");
            helper.assertValueEqual(run(server, "yolt border 10000"), List.of("World border diameter set to 10000." + borderOff),
                    "/yolt border output");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 10000, "diameter in the file");
            helper.assertValueEqual(run(server, "yolt endscaling 2.0"), List.of("End world border scaling factor set to 2.0." + borderOff),
                    "/yolt endscaling output");
            helper.assertValueEqual(fileValue("worldBorder.endScale").getAsDouble(), 2.0, "endScale in the file");
            helper.assertValueEqual(run(server, "yolt respawncooldown 7"),
                    List.of("Respawn cooldown set to 7 days. It applies to deaths from now on; use /yolt unkill for the rest."),
                    "/yolt respawncooldown output");
            helper.assertValueEqual(fileValue("deathLockout.cooldownDays").getAsInt(), 7, "cooldownDays in the file");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // /yolt border writes only its own key, so an edit to the file that wasn't reloaded yet stays in the file and stays
    // out of the live config
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void commandKeepsUnreloadedFileEdits(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        try {
            helper.assertValueEqual(Settings.get().chat.radius, 48, "the test server's chat radius");
            JsonObject edited = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            edited.getAsJsonObject("chat").addProperty("radius", 20);
            Files.writeString(path, edited.toString());

            helper.assertValueEqual(run(server, "yolt border 8000"),
                    List.of("World border diameter set to 8000. The world border feature is off, so YOLT sizes no border."), "command output");
            helper.assertValueEqual(fileValue("chat.radius").getAsInt(), 20, "chat.radius in the file");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 8000, "diameter in the file");
            helper.assertValueEqual(Settings.get().chat.radius, 48, "live chat.radius");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 8000, "live diameter");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void suggestsKeys(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack console = server.createCommandSourceStack();
        try {
            List<String> keys = suggestions(dispatcher, "yolt config hide.", console);
            helper.assertTrue(keys.contains("hide.tabList") && keys.contains("hide.locatorBar") && !keys.contains("chat.radius"),
                    "keys suggested for hide.: " + keys);
            List<String> modes = suggestions(dispatcher, "yolt config chat.mode ", console);
            helper.assertTrue(modes.containsAll(List.of("proximity", "self_only", "global")), "values suggested for chat.mode: " + modes);
            List<String> durations = suggestions(dispatcher, "yolt config bodies.duration ", console);
            helper.assertTrue(durations.contains("offline"), "values suggested for bodies.duration: " + durations);
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A non-op may use /yolt for their own skin, but config is for ops. Their client is sent no config node to complete,
    // with their commands restricted or not, and the server suggests no keys to a client that asks anyway. The same
    // player made an op is the check that these would show config at all.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void nonOpGetsNoConfigSuggestion(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        ServerPlayer player = TestPlayers.join(helper, "NotOp", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            Settings.get().skins.allowPlayerChoice = true;
            for (boolean restrict : new boolean[] {true, false}) {
                Settings.get().restrictCommands = restrict;
                String when = restrict ? " with commands restricted" : " with commands not restricted";
                List<String> subcommands = sentSubcommands(helper, server, player);
                helper.assertTrue(subcommands.contains("skin") && !subcommands.contains("config"), "a non-op's /yolt" + when + ": " + subcommands);
                List<String> keys = WhisperGameTests.suggestions(helper, player, "/yolt config hide.");
                helper.assertFalse(keys.contains("hide.tabList"), "keys the server suggested to a non-op" + when + ": " + keys);
            }

            TestPlayers.op(player);
            List<String> opSubcommands = sentSubcommands(helper, server, player);
            helper.assertTrue(opSubcommands.contains("config"), "an op's /yolt: " + opSubcommands);
            List<String> opKeys = WhisperGameTests.suggestions(helper, player, "/yolt config hide.");
            helper.assertTrue(opKeys.contains("hide.tabList"), "keys the server suggested to an op: " + opKeys);
        } finally {
            TestPlayers.deop(player);
            restore(server, fileBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Border on, and the overworld's centre put 150 blocks west of the player, so a 100 block border ends 100 blocks short
    // of them. A refusal leaves the live config, the file and the world as they were.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void configBorderKeyNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        Map<ServerLevel, double[]> bordersBefore = CommandGameTests.saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "PastEdge", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        try {
            Settings.get().worldBorder.enabled = true;
            YoltConfig before = Settings.get();
            int diameterBefore = before.worldBorder.diameter;
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 150, player.getZ());
            double sizeBefore = overworld.getSize();

            int[] result = {-1};
            List<String> refused = run(server, "yolt config worldBorder.diameter 100", value -> result[0] = value);
            helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave ") && refused.get(0).contains(name)
                    && refused.get(0).endsWith(" outside the world border. Add confirm to the command to do it anyway."), "refusal: " + refused);
            helper.assertValueEqual(result[0], 0, "result of the refused command");
            helper.assertTrue(Settings.get() == before, "the live config was replaced");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, diameterBefore, "live diameter after the refusal");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(path), fileBefore), "the config file changed");
            helper.assertValueEqual(overworld.getSize(), sizeBefore, "overworld border size after the refusal");

            // The world spawn sits among the test structures, so a note about it may follow
            List<String> confirmed = run(server, "yolt config worldBorder.diameter 100 confirm");
            helper.assertTrue(!confirmed.isEmpty() && confirmed.get(0).equals("worldBorder.diameter changed from " + diameterBefore + " to 100."),
                    "output with confirm: " + confirmed);
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 100, "live diameter with confirm");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 100, "diameter in the file");
            helper.assertValueEqual(overworld.getSize(), 100.0, "overworld border size with confirm");
        } finally {
            restore(server, fileBefore);
            CommandGameTests.restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A startup with a typo in the border holds it. The change is saved, but the world keeps its border, and the reply
    // says so and how to let it go. The startup load is put back with another, which is what ends the hold.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void heldBorderKeyChangeSaysSo(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        CommandGameTests.SavedBackups backups = CommandGameTests.saveBackups(path);
        Map<ServerLevel, double[]> bordersBefore = CommandGameTests.saveBorders(server);
        try {
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": \"2OOOO\"}}");
            Settings.load();
            TestServerSetup.applyTestSettings();
            Settings.get().worldBorder.enabled = true;
            helper.assertTrue(Settings.borderHeld(), "border not held after the startup load");
            double sizeBefore = server.overworld().getWorldBorder().getSize();

            helper.assertValueEqual(run(server, "yolt config worldBorder.diameter 8000"),
                    List.of("worldBorder.diameter changed from 10000 to 8000.", HELD), "command output");
            helper.assertTrue(Settings.borderHeld(), "border no longer held");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), sizeBefore, "overworld border size");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 8000, "live diameter");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 8000, "diameter in the file");
        } finally {
            Files.write(path, fileBefore);
            Settings.load();
            TestServerSetup.applyTestSettings();
            LiveSettings.applyAll(server);
            CommandGameTests.restoreBorders(bordersBefore);
            CommandGameTests.restoreBackups(backups);
        }
        helper.succeed();
    }

    // A file that isn't JSON is kept before the whole config goes over it, and the admin is told where, even when the
    // rewrite then fails. The rewrite goes through a temp file, which can't be made where a folder is. With no .bak there,
    // the copy is the .bak itself, the name the replies give.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void unreadableFileIsKeptAndTheReplySaysWhere(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        CommandGameTests.SavedBackups backups = CommandGameTests.saveBackups(path);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Path inTmp = tmp.resolve("inside");
        String kept = "Your config file couldn't be read, so it was kept as you-only-live-twice.json.bak";
        try {
            Files.deleteIfExists(backups.bak());
            Files.writeString(path, "{");
            Files.createDirectories(inTmp);
            List<String> failed = run(server, "yolt config chat.radius 20");
            helper.assertTrue(failed.size() == 2 && failed.get(0).startsWith("the config file could not be saved (")
                    && failed.get(0).endsWith(").") && failed.get(1).equals(kept + "."), "output when the rewrite fails: " + failed);
            helper.assertValueEqual(Settings.get().chat.radius, 48, "live chat.radius after the failed rewrite");
            helper.assertValueEqual(Files.readString(path), "{", "config file after the failed rewrite");

            Files.delete(inTmp);
            Files.delete(tmp);
            helper.assertValueEqual(run(server, "yolt config chat.radius 20"), List.of("chat.radius changed from 48 to 20.",
                    kept + " and rewritten."), "output when the rewrite works");
            helper.assertValueEqual(fileValue("chat.radius").getAsInt(), 20, "chat.radius in the file");
            helper.assertValueEqual(Files.readString(backups.bak()), "{", "the kept copy");
        } finally {
            // Before the restore, whose reload writes through the temp file
            Files.deleteIfExists(inTmp);
            Files.deleteIfExists(tmp);
            restore(server, fileBefore);
            CommandGameTests.restoreBackups(backups);
        }
        helper.succeed();
    }

    // A failed command's single line and result, with the live config and the file left as they were
    private static void assertRefused(GameTestHelper helper, MinecraftServer server, byte[] fileBefore, String command, String reply)
            throws IOException {
        YoltConfig before = Settings.get();
        int[] result = {-1};
        helper.assertValueEqual(run(server, command, value -> result[0] = value), List.of(reply), command);
        helper.assertValueEqual(result[0], 0, "result of " + command);
        helper.assertTrue(Settings.get() == before, command + " replaced the live config");
        helper.assertTrue(Arrays.equals(Files.readAllBytes(Settings.path()), fileBefore), command + " changed the config file");
    }

    // The /yolt subcommands in the tree the server sends this player now
    private static List<String> sentSubcommands(GameTestHelper helper, MinecraftServer server, ServerPlayer player) {
        server.getCommands().sendCommands(player);
        CommandNode<CommandSourceStack> yolt = CommandRestrictionGameTests.lastTree(helper, player).getChild("yolt");
        List<String> names = new ArrayList<>();
        if (yolt != null) {
            for (CommandNode<CommandSourceStack> child : yolt.getChildren()) {
                names.add(child.getName());
            }
        }
        return names;
    }

    private static List<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        List<String> texts = new ArrayList<>();
        for (Suggestion suggestion : dispatcher.getCompletionSuggestions(dispatcher.parse(command, source)).join().getList()) {
            texts.add(suggestion.getText());
        }
        return texts;
    }

    // A dotted key's value as the file holds it now
    static JsonElement fileValue(String key) throws IOException {
        JsonElement value = JsonParser.parseString(Files.readString(Settings.path()));
        for (String part : key.split("\\.")) {
            value = value.getAsJsonObject().get(part);
        }
        return value;
    }

    // The file's bytes back, then reloaded with the test server's overrides on top and applied as a reload would
    static void restore(MinecraftServer server, byte[] fileBefore) throws IOException {
        Files.write(Settings.path(), fileBefore);
        CommandGameTests.restoreConfig(server);
    }
}
