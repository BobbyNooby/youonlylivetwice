package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Commands run as the console, which has no player. Each test puts the settings and the config file back in a finally block.
public class CommandGameTests {
    // The test server keeps YOLT's border off, so this turns it on just for the command
    @GameTest
    public void borderFromConsoleSucceeds(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, Double> sizesBefore = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            sizesBefore.put(level, level.getWorldBorder().getSize());
        }

        border.enabled = true;
        try {
            helper.assertValueEqual(run(server, "yolt border 3000"), List.of("World border diameter set to: 3000"), "command output");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 3000, "diameter setting");
            helper.assertTrue(Files.readString(Settings.path()).contains("\"diameter\": 3000"), "diameter not saved to the config file");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 3000.0, "overworld border size");
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            sizesBefore.forEach((level, size) -> level.getWorldBorder().setSize(size));
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    @GameTest
    public void borderWithFeatureOffSavesAndSaysSo(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        String fileBefore = Files.readString(Settings.path());
        double sizeBefore = server.overworld().getWorldBorder().getSize();

        border.enabled = false;
        try {
            helper.assertValueEqual(run(server, "yolt border 3000"),
                    List.of("World border diameter set to: 3000 (world border feature is off)"), "command output");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 3000, "diameter setting");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), sizeBefore, "overworld border size");
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // Same limits as the config file, so a command can't save a value the next load would throw away
    @GameTest
    public void outOfRangeValuesAreRefused(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        int diameterBefore = config.worldBorder.diameter;
        double endScaleBefore = config.worldBorder.endScale;
        int cooldownBefore = config.deathLockout.cooldownDays;
        String fileBefore = Files.readString(Settings.path());
        try {
            helper.assertValueEqual(run(server, "yolt endscaling 0"), List.of("End world border scaling factor must be above 0."), "endscaling 0 output");
            for (String command : List.of("yolt border 0", "yolt border 59999969", "yolt endscaling -1", "yolt respawncooldown -1")) {
                List<String> output = run(server, command);
                helper.assertTrue(!output.isEmpty() && output.stream().noneMatch(line -> line.contains("set to")), command + " was not refused: " + output);
            }
            helper.assertValueEqual(config.worldBorder.diameter, diameterBefore, "diameter setting");
            helper.assertValueEqual(config.worldBorder.endScale, endScaleBefore, "end scale setting");
            helper.assertValueEqual(config.deathLockout.cooldownDays, cooldownBefore, "cooldown setting");
            helper.assertValueEqual(Files.readString(Settings.path()), fileBefore, "config file");
        } finally {
            config.worldBorder.diameter = diameterBefore;
            config.worldBorder.endScale = endScaleBefore;
            config.deathLockout.cooldownDays = cooldownBefore;
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // The end is moved off 0, 0 first, so the command putting it back is what the test sees
    @GameTest
    public void centerMovesOverworldAndNetherOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<ServerLevel, double[]> centresBefore = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            centresBefore.put(level, new double[] {level.getWorldBorder().getCenterX(), level.getWorldBorder().getCenterZ()});
        }
        try {
            level(helper, Level.END).getWorldBorder().setCenter(5, 5);
            helper.assertValueEqual(run(server, "yolt center 100 -200"), List.of("World border center set to: 100, -200"), "command output");
            assertCentre(helper, server.overworld(), 100, -200);
            assertCentre(helper, level(helper, Level.NETHER), 100, -200);
            assertCentre(helper, level(helper, Level.END), 0, 0);
        } finally {
            for (ServerLevel level : server.getAllLevels()) {
                double[] centre = centresBefore.get(level);
                level.getWorldBorder().setCenter(centre[0], centre[1]);
            }
        }
        helper.succeed();
    }

    // The test server's name cache makes up an id for any name, so the death is recorded under that id
    @GameTest
    public void unkillEndsLockoutAndReplies(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID id = server.services().nameToIdCache().get("Ghost").orElseThrow().id();
        DeathLockout.recordDeath(id, "Ghost");
        try {
            helper.assertTrue(DeathLockout.refusal(id) != null, "the death did not lock Ghost out");
            helper.assertValueEqual(run(server, "yolt unkill Ghost"), List.of("Revived Ghost"), "command output");
            helper.assertTrue(DeathLockout.refusal(id) == null, "Ghost is still locked out");
        } finally {
            DeathLockout.revive(id);
        }
        helper.succeed();
    }

    @GameTest
    public void unkillWithoutDeathFails(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertValueEqual(run(server, "yolt unkill Ghost2"), List.of("Ghost2 has no death to undo."), "command output");
        helper.succeed();
    }

    @GameTest
    public void killUnknownNameChangesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertValueEqual(run(server, "yolt kill NoSuchPlayer123"), List.of("Player NoSuchPlayer123 not found."), "command output");
        // The test server's name lookup makes up an offline id for any name, so that id must not be locked out
        helper.assertTrue(DeathLockout.refusal(NameAndId.createOffline("NoSuchPlayer123").id()) == null, "a death was recorded");
        helper.succeed();
    }

    @GameTest
    public void reloadReadsFileChanges(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        try {
            Settings.get().randomSpawn = true;
            // The border stays off, as the test server needs
            Files.writeString(path, "{\"randomSpawn\": false, \"worldBorder\": {\"enabled\": false}}");
            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded"), "command output");
            helper.assertFalse(Settings.get().randomSpawn, "randomSpawn still on after reload");
        } finally {
            Files.writeString(path, fileBefore);
            Settings.reload();
            TestServerSetup.applyTestSettings();
        }
        helper.succeed();
    }

    // One typo in the file must not reset a running server, or undo the test server's border override
    @GameTest
    public void reloadOfBrokenFileChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        YoltConfig configBefore = Settings.get();
        try {
            Files.writeString(path, "{broken");
            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.size() == 1 && output.get(0).startsWith("Config not reloaded"), "command output: " + output);
            helper.assertTrue(Settings.get() == configBefore, "live config was replaced");
            helper.assertValueEqual(Files.readString(path), "{broken", "config file");
        } finally {
            Files.writeString(path, fileBefore);
            if (Settings.get() != configBefore) {
                Settings.reload();
                TestServerSetup.applyTestSettings();
            }
        }
        helper.succeed();
    }

    private static void assertCentre(GameTestHelper helper, ServerLevel level, double x, double z) {
        helper.assertValueEqual(level.getWorldBorder().getCenterX(), x, level.dimension().identifier() + " border centre x");
        helper.assertValueEqual(level.getWorldBorder().getCenterZ(), z, level.dimension().identifier() + " border centre z");
    }

    private static ServerLevel level(GameTestHelper helper, ResourceKey<Level> dimension) {
        ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level != null, "test server has no level " + dimension);
        return level;
    }

    // Runs the command as the console and returns every line it sent back
    private static List<String> run(MinecraftServer server, String command) {
        List<String> output = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command);
        return output;
    }
}
