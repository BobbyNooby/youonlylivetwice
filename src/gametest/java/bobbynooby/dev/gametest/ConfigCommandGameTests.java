package bobbynooby.dev.gametest;

import bobbynooby.dev.config.ConfigKey;
import bobbynooby.dev.config.ConfigKeys;
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
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            // A key at the top of the file has no dot, and keeps its name as it is
            assertRefused(helper, server, fileBefore, "yolt config randomSpawn maybe", "randomSpawn takes true or false, not 'maybe'.");
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
            assertRefused(helper, server, fileBefore, "yolt config worldBorder.diameter 0", "worldBorder.diameter must be 1 to 59999968.");
            assertRefused(helper, server, fileBefore, "yolt config chat.radius 0", "chat.radius must be 1 or more.");
            // The End cap's problem has the diameter as its subject, so it reads on its own
            assertRefused(helper, server, fileBefore, "yolt config worldBorder.endScale 10000",
                    "worldBorder.diameter times endScale can't pass 59999968.");
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
            assertRefused(helper, server, fileBefore, "yolt config hide.tabHeaderLines hello",
                    "hide.tabHeaderLines can only be changed in the config file. Change it there, then run /yolt reload.");
            assertRefused(helper, server, fileBefore, "yolt config serverList.motdLines hello",
                    "serverList.motdLines can only be changed in the config file. Change it there, then run /yolt reload.");
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

    // Only a border key applies the border. The world's border differs from the config's, as after a reload that refused
    // to apply its border, so applying the config's for a chat key would move it unchecked.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void otherKeyLeavesTheBorderAlone(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = CommandGameTests.saveBorders(server);
        try {
            Settings.get().worldBorder.enabled = true;
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setSize(30000);
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 10000, "the test server's diameter");
            helper.assertValueEqual(run(server, "yolt config chat.radius 20"), List.of("chat.radius changed from 48 to 20."), "command output");
            helper.assertValueEqual(overworld.getSize(), 30000.0, "overworld border size after a chat key");
        } finally {
            restore(server, fileBefore);
            CommandGameTests.restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // The overworld's centre is put 150 blocks west of the player, so a 100 block border ends 100 blocks short of them.
    // First the live switch is off and the change turns it on, so only a check of the new config's own switch refuses
    // it. Then, with the border on, a diameter of 120 still leaves them outside. A refusal leaves the live config, the
    // file and the world as they were.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void configBorderKeyNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        Map<ServerLevel, double[]> bordersBefore = CommandGameTests.saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "PastEdge", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        try {
            Settings.get().worldBorder.enabled = false;
            Settings.get().worldBorder.diameter = 100;
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 150, player.getZ());
            double sizeBefore = overworld.getSize();

            assertStrandingRefused(helper, server, "yolt config worldBorder.enabled true", name, sizeBefore);
            // The world spawn sits among the test structures, so a note about it may follow
            List<String> switchedOn = run(server, "yolt config worldBorder.enabled true confirm");
            helper.assertTrue(!switchedOn.isEmpty() && switchedOn.get(0).equals("worldBorder.enabled changed from false to true."),
                    "output turning the border on with confirm: " + switchedOn);
            helper.assertTrue(Settings.get().worldBorder.enabled, "the border is still off with confirm");
            helper.assertValueEqual(overworld.getSize(), 100.0, "overworld border size once the border is on");

            assertStrandingRefused(helper, server, "yolt config worldBorder.diameter 120", name, 100.0);
            List<String> resized = run(server, "yolt config worldBorder.diameter 120 confirm");
            helper.assertTrue(!resized.isEmpty() && resized.get(0).equals("worldBorder.diameter changed from 100 to 120."),
                    "output with confirm: " + resized);
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 120, "live diameter with confirm");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 120, "diameter in the file");
            helper.assertValueEqual(overworld.getSize(), 120.0, "overworld border size with confirm");
        } finally {
            restore(server, fileBefore);
            CommandGameTests.restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A timed vanilla /worldborder change is still running, and a border key means "now", as /yolt border does: the border
    // stops at the new size, so the reply is true. confirm, because the test structures sit outside a 5000 block border,
    // and so may the world spawn, whose note may follow, before the note about offline players.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void configBorderKeyEndsAMovingBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = CommandGameTests.saveBorders(server);
        WorldBorder overworld = server.overworld().getWorldBorder();
        try {
            Settings.get().worldBorder.enabled = true;
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 10000, "the test server's diameter");
            overworld.lerpSizeBetween(10000, 8000, 1_000_000L, server.overworld().getGameTime());
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.SHRINKING, "overworld border status before the command");

            List<String> output = run(server, "yolt config worldBorder.diameter 5000 confirm");
            helper.assertTrue(!output.isEmpty() && output.get(0).equals("worldBorder.diameter changed from 10000 to 5000."),
                    "command output: " + output);
            // The test server keeps bodies off, so offline players without one went unchecked
            helper.assertValueEqual(output.get(output.size() - 1), "Offline players without a body were not checked.",
                    "the reply's last line");
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.STATIONARY, "overworld border status");
            helper.assertValueEqual(overworld.getSize(), 5000.0, "overworld border size");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 5000, "live diameter");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 5000, "diameter in the file");
        } finally {
            restore(server, fileBefore);
            CommandGameTests.restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // A border change refused for leaving the player outside, with the live config, the file and the overworld border as they were
    private static void assertStrandingRefused(GameTestHelper helper, MinecraftServer server, String command, String name, double size)
            throws IOException {
        YoltConfig before = Settings.get();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        int[] result = {-1};
        List<String> refused = run(server, command, value -> result[0] = value);
        helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave ") && refused.get(0).contains(name)
                && refused.get(0).endsWith(" outside the world border. Add confirm to the command to do it anyway."),
                command + " was not refused for the player: " + refused);
        helper.assertValueEqual(result[0], 0, "result of " + command);
        helper.assertTrue(Settings.get() == before, command + " replaced the live config");
        helper.assertTrue(Arrays.equals(Files.readAllBytes(Settings.path()), fileBefore), command + " changed the config file");
        helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), size, "overworld border size after " + command);
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
            helper.assertTrue(failed.size() == 2 && failed.get(0).startsWith("The config file could not be saved (")
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

    // /yolt respawncooldown writes the file through the same change as /yolt config, so it too says where a file that
    // couldn't be read went. With no .bak there, the copy is the .bak itself, the name the reply gives.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void respawnCooldownWithUnreadableConfigTellsTheBackup(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        CommandGameTests.SavedBackups backups = CommandGameTests.saveBackups(path);
        try {
            Files.deleteIfExists(backups.bak());
            Files.writeString(path, "{");
            List<Integer> results = new ArrayList<>();
            List<String> output = CommandGameTests.run(server, "yolt respawncooldown 3", results::add);
            helper.assertValueEqual(output, List.of(
                    "Respawn cooldown set to 3 days. It applies to deaths from now on; use /yolt unkill for the rest.",
                    "Your config file couldn't be read, so it was kept as you-only-live-twice.json.bak and rewritten."),
                    "command output");
            helper.assertValueEqual(results, List.of(1), "command result");
            helper.assertValueEqual(fileValue("deathLockout.cooldownDays").getAsInt(), 3, "cooldownDays in the file");
            helper.assertValueEqual(Files.readString(backups.bak()), "{", "the kept copy");
        } finally {
            restore(server, fileBefore);
            CommandGameTests.restoreBackups(backups);
        }
        helper.succeed();
    }

    // A new whole-number key, as each new key settable in game is checked: shown, set, refused out of range, and read
    // again from the file by a reload
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void curseAfkMinutesSetsAndReloads(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            setsRefusesAndReloads(helper, server, "hardcore.curse.afkMinutes", "5", "2", "", "0",
                    "hardcore.curse.afkMinutes must be 1 or more.", "9");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A new switch: set off by the command, refused when not true or false, and on again from the file by a reload
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void tabHeaderFrameSetsAndReloads(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            setsRefusesAndReloads(helper, server, "hide.tabHeaderFrame", "true", "false", "", "maybe",
                    "hide.tabHeaderFrame takes true or false, not 'maybe'.", "true");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The bar's colour, a name from a list, and the bar's other settings, which all say they wait for the next blood moon
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void bloodMoonBarKeysSayTheyWaitForTheNextOne(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        String next = " (applies from the next blood moon)";
        try {
            setsRefusesAndReloads(helper, server, "hardcore.bloodMoon.barColour", "red", "blue", next, "maroon",
                    "hardcore.bloodMoon.barColour takes pink, blue, red, green, yellow, purple or white, not 'maroon'.", "purple");
            helper.assertValueEqual(run(server, "yolt config hardcore.bloodMoon.bossBar false"),
                    List.of("hardcore.bloodMoon.bossBar changed from true to false" + next + "."), "bossBar reply");
            helper.assertValueEqual(run(server, "yolt config hardcore.bloodMoon.darkenSky false"),
                    List.of("hardcore.bloodMoon.darkenSky changed from true to false" + next + "."), "darkenSky reply");
            helper.assertValueEqual(run(server, "yolt config hardcore.bloodMoon.fog true"),
                    List.of("hardcore.bloodMoon.fog changed from false to true" + next + "."), "fog reply");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // Every text of the messages section: set, kept to 256 characters, and read again by a reload, with the note of a
    // text that waits, such as the revive paper's name. A required placeholder can't be dropped, and one the text doesn't
    // fill in is let through with a line that says so, by the command and by a reload.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void messagesSetRefuseAndReload(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        YoltConfig fresh = new YoltConfig();
        try {
            int texts = 0;
            for (ConfigKey key : ConfigKeys.all()) {
                if (!key.path().startsWith("messages.")) {
                    continue;
                }
                String shipped = key.show(fresh);
                // Placeholders and all, so no text loses one it must keep
                setsRefusesAndReloads(helper, server, key.path(), shipped, "Now " + shipped, key.when().note(), "x".repeat(257),
                        key.path() + " must be at most 256 characters.", "Then " + shipped);
                texts++;
            }
            helper.assertValueEqual(texts, 15, "texts in the messages section");
            for (String[] dropped : new String[][] {{"lockoutWait", "Come back later.", "{wait}"},
                    {"reviveName", "Bring them back", "{name}"}, {"graveAt", "Dig at {x}", "{y} and {z}"}}) {
                String path = "messages." + dropped[0];
                assertRefused(helper, server, Files.readAllBytes(Settings.path()), "yolt config " + path + " " + dropped[1],
                        path + " must keep " + dropped[2] + ".");
            }

            helper.assertValueEqual(run(server, "yolt config messages.graveIn Your grave lies in {dim}."), List.of(
                    "messages.graveIn changed from Then Your grave is in {dimension}. to Your grave lies in {dim}..",
                    "messages.graveIn has {dim}, which it doesn't fill in, so it shows as typed."), "set with an unknown placeholder");
            helper.assertValueEqual(Settings.get().messages.graveIn, "Your grave lies in {dim}.", "live messages.graveIn");
            Files.writeString(Settings.path(), "{\"messages\": {\"graveIn\": \"In {where}.\"}, \"worldBorder\": {\"enabled\": false}}");
            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded.",
                    "messages.graveIn: has {where}, which it doesn't fill in, so it shows as typed"), "reload with an unknown placeholder");
            helper.assertValueEqual(Settings.get().messages.graveIn, "In {where}.", "live messages.graveIn after the reload");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A non-op reaches /yolt while players may pick their skin, and every command with restrictCommands off, yet config
    // and reload can't even be read past "yolt" for them, so neither runs: the live config and the file stay as they
    // were, though the file now says something a reload would read. The same player made an op is the check that the
    // command as typed works.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void nonOpCantRunConfigOrReload(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        YoltConfig live = Settings.get();
        boolean restrictBefore = live.restrictCommands;
        boolean choiceBefore = live.skins.allowPlayerChoice;
        ServerPlayer player = TestPlayers.join(helper, "NoConfig", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        try {
            live.restrictCommands = false;
            live.skins.allowPlayerChoice = true;
            JsonObject edited = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            edited.addProperty("randomSpawn", !live.randomSpawn);
            Files.writeString(path, edited.toString());
            byte[] editedBytes = Files.readAllBytes(path);

            for (String command : List.of("yolt config chat.radius 20", "yolt reload")) {
                helper.assertTrue(dispatcher.parse(command, player.createCommandSourceStack()).getReader().canRead(),
                        "a non-op's '" + command + "' was read past yolt");
                int[] result = {-1};
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withCallback((success, value) -> result[0] = value),
                        command);
                helper.assertTrue(result[0] != 1, "a non-op's '" + command + "' ran");
                helper.assertTrue(Settings.get() == live, "a non-op's '" + command + "' replaced the live config");
            }
            helper.assertValueEqual(Settings.get().chat.radius, 48, "live chat.radius");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(path), editedBytes), "the config file was written");

            TestPlayers.op(player);
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "yolt config chat.radius 20");
            helper.assertValueEqual(Settings.get().chat.radius, 20, "chat.radius set by the player made an op");
        } finally {
            TestPlayers.deop(player);
            live.restrictCommands = restrictBefore;
            live.skins.allowPlayerChoice = choiceBefore;
            restore(server, fileBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Every key the command takes, set to another value and back. Each is either changed, with the reply naming both
    // values and the key's note, or refused by a reply that names the key, as one of a pair can be; none fails with an
    // error. Once all are back the file says what it said before. The live config starts as the file has it, without the
    // test server's overrides, or setting a key back would write the override into the file. The border keys move the
    // world's border, which their own tests check, and the revive paper's model is left to its own tests.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void everyKeyCanBeSetAndPutBack(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        JsonElement jsonBefore = JsonParser.parseString(new String(fileBefore, StandardCharsets.UTF_8));
        GameRules rules = server.getGameRules();
        boolean respawnBefore = rules.get(GameRules.IMMEDIATE_RESPAWN);
        boolean locatorBefore = rules.get(GameRules.LOCATOR_BAR);
        boolean soundsBefore = rules.get(GameRules.GLOBAL_SOUND_EVENTS);
        int changed = 0;
        List<String> refused = new ArrayList<>();
        try {
            Settings.reload();
            for (ConfigKey key : ConfigKeys.all()) {
                if (key.path().startsWith("worldBorder.") || key.path().equals("resourcePack.reviveModel")) {
                    continue;
                }
                String old = key.show(Settings.get());
                String other = otherValue(key, old);
                int[] result = {-1};
                List<String> set = run(server, "yolt config " + key.path() + " " + other, value -> result[0] = value);
                if (result[0] == 1) {
                    helper.assertTrue(set.get(0).equals(key.path() + " changed from " + old + " to " + other + key.when().note() + "."),
                            key.path() + " set to " + other + ": " + set);
                    result[0] = -1;
                    List<String> back = run(server, "yolt config " + key.path() + " " + old, value -> result[0] = value);
                    helper.assertTrue(result[0] == 1 && back.get(0).equals(key.path() + " changed from " + other + " to " + old
                            + key.when().note() + "."), key.path() + " set back to " + old + ": " + back);
                    changed++;
                } else {
                    helper.assertTrue(result[0] == 0 && set.size() == 1 && set.get(0).contains(key.path()),
                            key.path() + " set to " + other + " was neither changed nor refused by name: " + set);
                    refused.add(key.path());
                }
                helper.assertValueEqual(key.show(Settings.get()), old, "live " + key.path() + " at the end");
            }
            helper.assertValueEqual(JsonParser.parseString(Files.readString(path)), jsonBefore, "the config file once every key was back");
            // Most keys take another value of their kind; only the odd range or pair refuses it
            helper.assertTrue(changed > 100 && refused.size() < 10, changed + " keys changed, refused: " + refused);
        } finally {
            restore(server, fileBefore);
            rules.set(GameRules.IMMEDIATE_RESPAWN, respawnBefore, server);
            rules.set(GameRules.LOCATOR_BAR, locatorBefore, server);
            rules.set(GameRules.GLOBAL_SOUND_EVENTS, soundsBefore, server);
        }
        helper.succeed();
    }

    // A value of the key's kind that differs from this one: the other switch, the next name, one more, half as much (or
    // one, for nothing), a time for "offline", and a text with a word in front, which keeps its placeholders
    private static String otherValue(ConfigKey key, String value) {
        return switch (key.kind()) {
            case BOOLEAN -> Boolean.toString(!Boolean.parseBoolean(value));
            case ENUM -> key.choices().get((key.choices().indexOf(value) + 1) % key.choices().size());
            case INT -> Integer.toString(Integer.parseInt(value) + 1);
            case DOUBLE -> Double.parseDouble(value) == 0 ? "1.0" : Double.toString(Double.parseDouble(value) / 2);
            case BODY_DURATION -> value.equals("offline") ? "30" : "offline";
            case STRING -> value.isEmpty() ? "Now" : "Now " + value;
        };
    }

    // One of a pair crossing the other is refused, and nothing is written. Either way round the reply is the pair's
    // first line, which names both keys.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void pairKeyCrossingIsRefused(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertTrue(Settings.get().hardcore.limp.belowHearts == 2.0 && Settings.get().hardcore.limp.recoverAtHearts == 3.0,
                    "the test server's limp thresholds");
            assertRefused(helper, server, fileBefore, "yolt config hardcore.limp.belowHearts 3",
                    "hardcore.limp.belowHearts must be less than hardcore.limp.recoverAtHearts.");
            assertRefused(helper, server, fileBefore, "yolt config hardcore.limp.recoverAtHearts 2",
                    "hardcore.limp.belowHearts must be less than hardcore.limp.recoverAtHearts.");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The write goes through a temp file, which can't be made where a folder is. The reply is the save's failure, and the
    // live config and the file stay as they were.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void writeFailureIsReportedAndChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        byte[] fileBefore = Files.readAllBytes(path);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Path inTmp = tmp.resolve("inside");
        YoltConfig before = Settings.get();
        try {
            Files.createDirectories(inTmp);
            int[] result = {-1};
            List<String> output = run(server, "yolt config chat.radius 20", value -> result[0] = value);
            helper.assertTrue(output.size() == 1 && output.get(0).startsWith("The config file could not be saved (")
                    && output.get(0).endsWith(")."), "command output: " + output);
            helper.assertValueEqual(result[0], 0, "command result");
            helper.assertTrue(Settings.get() == before, "the live config was replaced");
            helper.assertValueEqual(Settings.get().chat.radius, 48, "live chat.radius");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(path), fileBefore), "the config file was written");
        } finally {
            // Before the restore, whose reload writes through the temp file
            Files.deleteIfExists(inTmp);
            Files.deleteIfExists(tmp);
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // Turning the border feature off moves no border and needs no confirm, though the world's 100 block border already
    // leaves the tests outside. With it off, a new diameter is only saved: no stranding check, and the border stays.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void borderOffViaConfigLeavesTheBorderAlone(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = CommandGameTests.saveBorders(server);
        WorldBorder overworld = server.overworld().getWorldBorder();
        try {
            Settings.get().worldBorder.enabled = true;
            overworld.setSize(100);
            helper.assertValueEqual(run(server, "yolt config worldBorder.enabled false"),
                    List.of("worldBorder.enabled changed from true to false."), "turning the border off");
            helper.assertValueEqual(overworld.getSize(), 100.0, "overworld border size once the feature is off");
            helper.assertValueEqual(run(server, "yolt config worldBorder.diameter 50"),
                    List.of("worldBorder.diameter changed from 10000 to 50."), "a diameter with the feature off");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 50, "live diameter");
            helper.assertValueEqual(fileValue("worldBorder.diameter").getAsInt(), 50, "diameter in the file");
            helper.assertValueEqual(overworld.getSize(), 100.0, "overworld border size after the diameter");
        } finally {
            restore(server, fileBefore);
            CommandGameTests.restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // With restrictCommands off a non-op has /yolt only to pick their skin. Turning player choice off resends every tree
    // at once, so their game drops /yolt, and the server no longer reads it for them either.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void skinChoiceOffResendsTreeWithoutYolt(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        YoltConfig live = Settings.get();
        boolean restrictBefore = live.restrictCommands;
        boolean choiceBefore = live.skins.allowPlayerChoice;
        ServerPlayer player = TestPlayers.join(helper, "SkinChooser", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            live.restrictCommands = false;
            live.skins.allowPlayerChoice = true;
            server.getCommands().sendCommands(player);
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, player).getChild("yolt") != null,
                    "the non-op had no /yolt while they may pick a skin");

            helper.assertValueEqual(run(server, "yolt config skins.allowPlayerChoice false"),
                    List.of("skins.allowPlayerChoice changed from true to false."), "command output");
            helper.assertTrue(CommandRestrictionGameTests.lastTree(helper, player).getChild("yolt") == null,
                    "the tree sent after the change still has /yolt");
            helper.assertTrue(server.getCommands().getDispatcher().parse("yolt skin clear", player.createCommandSourceStack())
                    .getReader().canRead(), "the server still reads the non-op's /yolt skin clear");
        } finally {
            live.restrictCommands = restrictBefore;
            live.skins.allowPlayerChoice = choiceBefore;
            restore(server, fileBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // "confirm" is only taken off a border key's value. Anywhere else it is part of the value, which a number or a name
    // from a list then can't be.
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void confirmOnlyStripsFromBorderKeys(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            assertRefused(helper, server, fileBefore, "yolt config chat.radius 20 confirm", "chat.radius takes a whole number, not '20 confirm'.");
            assertRefused(helper, server, fileBefore, "yolt config chat.mode global confirm",
                    "chat.mode takes proximity, self_only or global, not 'global confirm'.");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A key that only takes effect later says when, so a change that seems to do nothing doesn't surprise the admin
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void replyCarriesEachWhenNote(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertValueEqual(run(server, "yolt config skins.mode off"),
                    List.of("skins.mode changed from auto to off (applies when a player next joins)."), "skins.mode");
            helper.assertValueEqual(run(server, "yolt config hide.coordinates true"), List.of("hide.coordinates changed from false to true"
                    + " (applies when a player next joins, respawns or changes dimension)."), "hide.coordinates");
            helper.assertValueEqual(run(server, "yolt config deathLockout.cooldownDays 3"),
                    List.of("deathLockout.cooldownDays changed from 7 to 3 (applies to deaths from now on)."), "deathLockout.cooldownDays");
            helper.assertValueEqual(run(server, "yolt config hardcore.tougherMobs.health 2.0"),
                    List.of("hardcore.tougherMobs.health changed from 1.5 to 2.0 (applies to mobs as they load)."), "hardcore.tougherMobs.health");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A command block runs commands at level 2, which is all /yolt config asks for
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void commandBlockCanSetAKey(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        BlockPos pos = new BlockPos(1, 1, 1);
        try {
            helper.setBlock(pos, Blocks.COMMAND_BLOCK);
            BaseCommandBlock block = helper.<CommandBlockEntity>getBlockEntity(pos, CommandBlockEntity.class).getCommandBlock();
            block.setCommand("yolt config chat.radius 20");
            helper.assertTrue(block.performCommand(helper.getLevel()), "the command block didn't run");
            helper.assertValueEqual(block.getSuccessCount(), 1, "the command block's result");
            helper.assertValueEqual(Settings.get().chat.radius, 20, "live chat.radius");
            helper.assertValueEqual(fileValue("chat.radius").getAsInt(), 20, "chat.radius in the file");
        } finally {
            helper.setBlock(pos, Blocks.AIR);
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // A number, a text and a key that doesn't exist have no values to suggest, and asking doesn't fail
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void valueSuggestionsForUnknownOrNumberKeyAreEmpty(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack console = server.createCommandSourceStack();
        for (String command : List.of("yolt config chat.radus ", "yolt config chat.radius ", "yolt config messages.graveIn ")) {
            helper.assertValueEqual(suggestions(dispatcher, command, console), List.of(), "values suggested for '" + command + "'");
        }
        // The check that the same request does suggest values for a key that has them
        helper.assertTrue(!suggestions(dispatcher, "yolt config chat.mode ", console).isEmpty(), "no values suggested for chat.mode");
        helper.succeed();
    }

    // A body that stays a set time can go back to staying until its owner is back, written as the word the file uses,
    // and a time of nothing is refused
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void offlineDurationRoundTrips(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        try {
            helper.assertValueEqual(run(server, "yolt config bodies.duration 30"), List.of("bodies.duration changed from offline to 30."),
                    "set to 30");
            helper.assertValueEqual(run(server, "yolt config bodies.duration offline"), List.of("bodies.duration changed from 30 to offline."),
                    "set to offline");
            helper.assertTrue(Settings.get().bodies.duration.isOffline(), "live bodies.duration is not offline");
            helper.assertValueEqual(fileValue("bodies.duration").getAsString(), "offline", "bodies.duration in the file");
            assertRefused(helper, server, Files.readAllBytes(Settings.path()), "yolt config bodies.duration 0",
                    "bodies.duration takes offline or whole minutes of 1 or more, not '0'.");
        } finally {
            restore(server, fileBefore);
        }
        helper.succeed();
    }

    // The value already in place, in the live config and the file, changes nothing, so it doesn't apply the game rule
    // again after a hand-made /gamerule either; turning the switch on does, and so does a reload (BorderAndReloadGameTests)
    @GameTest(environment = CommandGameTests.CONFIG_COMMAND)
    public void alreadySetKeyDoesNotReapplyRules(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        GameRules rules = server.getGameRules();
        boolean ruleBefore = rules.get(GameRules.IMMEDIATE_RESPAWN);
        try {
            helper.assertTrue(Settings.get().immediateRespawn && fileValue("immediateRespawn").getAsBoolean(),
                    "immediateRespawn is not on in the live config and the file");
            rules.set(GameRules.IMMEDIATE_RESPAWN, false, server);
            helper.assertValueEqual(run(server, "yolt config immediateRespawn true"), List.of("immediateRespawn is already true."),
                    "setting the value it has");
            helper.assertFalse(rules.get(GameRules.IMMEDIATE_RESPAWN), "the value it had already turned the rule on");

            helper.assertValueEqual(run(server, "yolt config immediateRespawn false"),
                    List.of("immediateRespawn changed from true to false."), "turning it off");
            helper.assertValueEqual(run(server, "yolt config immediateRespawn true"),
                    List.of("immediateRespawn changed from false to true."), "turning it on");
            helper.assertTrue(rules.get(GameRules.IMMEDIATE_RESPAWN), "turning the switch on left the rule off");
        } finally {
            restore(server, fileBefore);
            rules.set(GameRules.IMMEDIATE_RESPAWN, ruleBefore, server);
        }
        helper.succeed();
    }

    /**
     * Shows the key at its shipped value, sets it with the command, whose reply ends with the key's note, has a bad value
     * refused, then writes the file with only that key, with the border kept off as the test server needs, and reloads
     * it. Each step reads the live config back, the set one the file too.
     */
    static void setsRefusesAndReloads(GameTestHelper helper, MinecraftServer server, String path, String shipped, String set,
                                      String note, String bad, String refusal, String reloaded) throws IOException {
        ConfigKey key = ConfigKeys.find(path).orElseThrow(() -> new AssertionError("no key " + path));
        helper.assertValueEqual(run(server, "yolt config " + path), List.of(path + " is " + shipped + "."), path + " shown");
        helper.assertValueEqual(run(server, "yolt config " + path + " " + set),
                List.of(path + " changed from " + shipped + " to " + set + note + "."), path + " set");
        helper.assertValueEqual(key.show(Settings.get()), set, "live " + path + " after the set");
        helper.assertValueEqual(fileValue(path).getAsString(), set, path + " in the file after the set");
        assertRefused(helper, server, Files.readAllBytes(Settings.path()), "yolt config " + path + " " + bad, refusal);

        JsonObject file = new JsonObject();
        JsonObject border = new JsonObject();
        border.addProperty("enabled", false);
        file.add("worldBorder", border);
        String[] parts = path.split("\\.");
        JsonObject section = file;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonObject inner = new JsonObject();
            section.add(parts[i], inner);
            section = inner;
        }
        try {
            section.add(parts[parts.length - 1], key.parse(reloaded));
        } catch (ConfigKey.BadValue e) {
            throw new AssertionError(e.getMessage(), e);
        }
        Files.writeString(Settings.path(), file.toString());
        helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), path + " reload");
        helper.assertValueEqual(key.show(Settings.get()), reloaded, "live " + path + " after the reload");
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
