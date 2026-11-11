package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.Cooldown;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import bobbynooby.dev.lockout.DeathStore;
import bobbynooby.dev.world.LiveSettings;
import bobbynooby.dev.world.WorldBorders;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.synchronization.ArgumentUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

// Commands run as the console, which has no player. Each test puts the settings and the config file back in a finally block.
public class CommandGameTests {
    // A batch of its own: a reload swaps the whole config object, which a test holding the old one across ticks would miss
    static final String CONFIG_COMMAND = "you-only-live-twice-gametest:config_command";
    private static final String SPAWN_NOTE = " The world spawn is outside the new border; move it with /setworldspawn.";
    // Test structures, and the world spawn among them, sit within ±15 million blocks, so a border this wide around 0, 0
    // holds them all, and BEYOND is past its edge
    private static final int WIDE = 50_000_000;
    private static final int BEYOND = 27_000_000;

    // The test server keeps YOLT's border off, so this turns it on just for the command. confirm, because the test
    // structures and anything in them sit millions of blocks out, far outside a 3000 block border. The world spawn sits
    // among them, so the reply notes it.
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
            helper.assertValueEqual(run(server, "yolt border 3000 confirm"), List.of("World border diameter set to 3000." + SPAWN_NOTE),
                    "command output");
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

    // Writes the config file, so it runs with the other tests that do, apart from the ones holding the config
    @GameTest(environment = CONFIG_COMMAND)
    public void respawnCooldownReplyNamesUnitAndScope(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        int daysBefore = Settings.get().deathLockout.cooldownDays;
        String fileBefore = Files.readString(Settings.path());
        try {
            String scope = " It applies to deaths from now on; use /yolt unkill for the rest.";
            helper.assertValueEqual(run(server, "yolt respawncooldown 7"), List.of("Respawn cooldown set to 7 days." + scope), "output for 7");
            helper.assertValueEqual(run(server, "yolt respawncooldown 1"), List.of("Respawn cooldown set to 1 day." + scope), "output for 1");
            helper.assertValueEqual(Settings.get().deathLockout.cooldownDays, 1, "cooldown setting");
        } finally {
            Settings.get().deathLockout.cooldownDays = daysBefore;
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // Ruling R4: each death keeps the days it was recorded with, so a new cooldown only reaches deaths from then on.
    // Writes the config file, so it runs with the other tests that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void cooldownChangeLeavesEarlierDeathsAlone(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        int daysBefore = lockout.cooldownDays;
        String fileBefore = Files.readString(Settings.path());
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        try {
            lockout.cooldownDays = 7;
            DeathLockout.recordDeath(earlier, uniqueName("Earlier"));
            helper.assertValueEqual(run(server, "yolt respawncooldown 1"), List.of("Respawn cooldown set to 1 day."
                    + " It applies to deaths from now on; use /yolt unkill for the rest."), "command output");
            DeathLockout.recordDeath(later, uniqueName("Later"));

            long now = System.currentTimeMillis();
            DeathLog kept = DeathLockout.newestDeath(earlier);
            helper.assertTrue(kept != null && kept.cooldownDays() == 7, "the earlier death's days: " + kept);
            helper.assertTrue(Cooldown.remainingMillis(kept, now) > Duration.ofDays(6).toMillis(),
                    "the earlier death's wait got shorter: " + LockoutGameTests.refusalText(earlier));
            DeathLog fresh = DeathLockout.newestDeath(later);
            helper.assertTrue(fresh != null && fresh.cooldownDays() == 1, "the later death's days: " + fresh);
        } finally {
            lockout.cooldownDays = daysBefore;
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
                    List.of("World border diameter set to 3000. The world border feature is off, so YOLT sizes no border."), "command output");
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

    // The end is moved off 0, 0 first, so the command putting it back is what the test sees. The border feature is turned
    // on for the command, which leaves the border alone while it's off.
    @GameTest
    public void centerMovesOverworldAndNetherOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        Map<ServerLevel, double[]> centresBefore = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            centresBefore.put(level, new double[] {level.getWorldBorder().getCenterX(), level.getWorldBorder().getCenterZ()});
        }
        border.enabled = true;
        try {
            level(helper, Level.END).getWorldBorder().setCenter(5, 5);
            helper.assertValueEqual(run(server, "yolt center 100 -200"), List.of("World border center set to 100, -200."), "command output");
            assertCentre(helper, server.overworld(), 100, -200);
            assertCentre(helper, level(helper, Level.NETHER), 100, -200);
            assertCentre(helper, level(helper, Level.END), 0, 0);
        } finally {
            border.enabled = enabledBefore;
            for (ServerLevel level : server.getAllLevels()) {
                double[] centre = centresBefore.get(level);
                level.getWorldBorder().setCenter(centre[0], centre[1]);
            }
        }
        helper.succeed();
    }

    // The border is saved with the world, and its codec can't write a centre past the world's edge
    @GameTest
    public void centerOutsideWorldIsRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        try {
            helper.assertValueEqual(run(server, "yolt center 30000000 0"), List.of("Center must be within ±29999984."), "command output");
            bordersBefore.forEach((level, border) -> assertCentre(helper, level, border[0], border[1]));
            helper.assertTrue(WorldBorder.CODEC.encodeStart(NbtOps.INSTANCE, server.overworld().getWorldBorder()).isSuccess(),
                    "the overworld border can't be saved");
        } finally {
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    @GameTest
    public void centerKeepsDecimals(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        border.enabled = true;
        try {
            List<String> output = run(server, "yolt center 100.5 -20.25");
            helper.assertTrue(output.size() == 1 && output.get(0).startsWith("World border center set to 100.5, -20.25."),
                    "command output: " + output);
            assertCentre(helper, server.overworld(), 100.5, -20.25);
        } finally {
            border.enabled = enabledBefore;
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // Off, YOLT leaves the border alone, as /yolt border does, so no centre moves and nothing needs a stranding check
    @GameTest
    public void centerWithBorderOffSaysSo(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);

        border.enabled = false;
        try {
            helper.assertValueEqual(run(server, "yolt center 10 20"), List.of("The world border feature is off, so YOLT moves no border."),
                    "command output");
            bordersBefore.forEach((level, before) -> assertCentre(helper, level, before[0], before[1]));
        } finally {
            border.enabled = enabledBefore;
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // With random spawn off a new player starts at the world spawn, where a border far away would kill them
    @GameTest
    public void centerWarnsWhenSpawnEndsOutside(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean randomSpawnBefore = config.randomSpawn;
        boolean enabledBefore = config.worldBorder.enabled;
        int diameterBefore = config.worldBorder.diameter;
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        BlockPos spawn = server.getWorldData().overworldData().getRespawnData().pos();

        config.randomSpawn = false;
        config.worldBorder.enabled = true;
        config.worldBorder.diameter = 100;
        try {
            WorldBorders.apply(server);
            // confirm, because the test structures and anything in them sit far outside a 100 block border
            List<String> output = run(server, "yolt center " + (spawn.getX() + 10000) + " " + spawn.getZ() + " confirm");
            helper.assertTrue(output.size() == 1
                            && output.get(0).endsWith(" The world spawn is outside the new border; move it with /setworldspawn."),
                    "command output: " + output);

            // Centred on the spawn, it stays inside, so there's no note
            helper.assertValueEqual(run(server, "yolt center " + spawn.getX() + " " + spawn.getZ() + " confirm"),
                    List.of("World border center set to " + spawn.getX() + ", " + spawn.getZ() + "."), "output with the spawn inside");
        } finally {
            config.randomSpawn = randomSpawnBefore;
            config.worldBorder.enabled = enabledBefore;
            config.worldBorder.diameter = diameterBefore;
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // YOLT's diameter is 10,000, but the world's border is 2000, as it can be while held or after a vanilla
    // /worldborder. A centre 3000 blocks from the player leaves them outside, though a 10,000 block border wouldn't.
    // A batch of its own, so no other test's player can push this one's name off the list.
    @GameTest(environment = CONFIG_COMMAND)
    public void centerChecksTheWorldsOwnBorderSize(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Recentred", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();

        border.enabled = true;
        border.diameter = 10000;
        try {
            WorldBorder overworld = server.overworld().getWorldBorder();
            double[] before = bordersBefore.get(server.overworld());
            overworld.setSize(2000);
            BlockPos at = player.blockPosition();
            List<String> refused = run(server, "yolt center " + (at.getX() - 3000) + " " + at.getZ());
            helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave ") && refused.get(0).contains(name),
                    "refusal: " + refused);
            assertCentre(helper, server.overworld(), before[0], before[1]);
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The overworld centre is put 1100 blocks west of the player, so a 2000 block border ends 100 blocks short of them
    @GameTest
    public void borderShrinkWithPlayerOutsideNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Outside", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();

        border.enabled = true;
        try {
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 1100, player.getZ());
            double sizeBefore = overworld.getSize();

            List<String> refused = run(server, "yolt border 2000");
            helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave ")
                    && refused.get(0).contains(name) && refused.get(0).contains(" outside the world border."), "refusal: " + refused);
            helper.assertValueEqual(overworld.getSize(), sizeBefore, "overworld border size after the refusal");
            helper.assertValueEqual(border.diameter, diameterBefore, "diameter setting after the refusal");

            // The world spawn sits among the test structures, so whether the reply notes it depends on where this one is
            List<String> confirmed = run(server, "yolt border 2000 confirm");
            helper.assertTrue(confirmed.size() == 1 && confirmed.get(0).startsWith("World border diameter set to 2000."),
                    "output with confirm: " + confirmed);
            helper.assertValueEqual(overworld.getSize(), 2000.0, "overworld border size with confirm");
            helper.assertTrue(DeathLockout.refusal(player.getUUID(), "Test") == null, "a death was recorded for " + name);
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            restoreBorders(bordersBefore);
            Files.writeString(Settings.path(), fileBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Nobody could reach a grave outside the border to open it. Every border is centred, so a WIDE one holds everything
    // the tests have, and only this grave sits past it: a record alone, since the check reads the records. A refusal
    // changes nothing, but a broken one would save the new diameter, so the setting and the file are put back.
    @GameTest
    public void borderShrinkOverGraveNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        double endScaleBefore = border.endScale;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        GraveStore store = GraveStore.get(server);
        String owner = uniqueName("Buried");
        Grave grave = new Grave(UUID.randomUUID(), UUID.randomUUID(), owner, ResolvableProfile.createUnresolved(owner),
                Level.OVERWORLD, new BlockPos(BEYOND, 64, 0), List.of(new ItemStack(Items.DIAMOND)), System.currentTimeMillis());

        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            store.put(grave);
            helper.assertValueEqual(run(server, "yolt border " + WIDE), List.of("This would leave " + owner
                    + "'s grave outside the world border. Add confirm to the command to do it anyway."), "refusal");
            helper.assertValueEqual(border.diameter, diameterBefore, "diameter setting after the refusal");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), bordersBefore.get(server.overworld())[2],
                    "overworld border size after the refusal");
        } finally {
            store.remove(grave.id());
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            border.endScale = endScaleBefore;
            Files.writeString(Settings.path(), fileBefore);
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // With random spawn off a new player starts at the world spawn itself, so a border that leaves it outside kills them
    // on their first join. The border holds every test; only the moved spawn is outside it.
    @GameTest(environment = CONFIG_COMMAND)
    public void borderShrinkStrandingWorldSpawnNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean randomSpawnBefore = config.randomSpawn;
        YoltConfig.WorldBorderSection border = config.worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        double endScaleBefore = border.endScale;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();

        config.randomSpawn = false;
        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            moveSpawn(server, BEYOND);
            helper.assertValueEqual(run(server, "yolt border " + WIDE),
                    List.of("This would leave the world spawn outside the world border. Add confirm to the command to do it anyway."),
                    "refusal");
            helper.assertValueEqual(border.diameter, diameterBefore, "diameter setting after the refusal");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), bordersBefore.get(server.overworld())[2],
                    "overworld border size after the refusal");

            helper.assertValueEqual(run(server, "yolt border " + WIDE + " confirm"),
                    List.of("World border diameter set to " + WIDE + "." + SPAWN_NOTE), "output with confirm");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size with confirm");
        } finally {
            config.randomSpawn = randomSpawnBefore;
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            border.endScale = endScaleBefore;
            restoreBorders(bordersBefore);
            server.setRespawnData(spawnBefore);
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // Random spawn puts a first join inside the border instead, so the same border only gets a note
    @GameTest(environment = CONFIG_COMMAND)
    public void borderLeavingSpawnOutsideOnlyNotesWithRandomSpawn(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean randomSpawnBefore = config.randomSpawn;
        YoltConfig.WorldBorderSection border = config.worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        double endScaleBefore = border.endScale;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();

        config.randomSpawn = true;
        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            moveSpawn(server, BEYOND);
            helper.assertValueEqual(run(server, "yolt border " + WIDE), List.of("World border diameter set to " + WIDE + "." + SPAWN_NOTE),
                    "command output");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size");
        } finally {
            config.randomSpawn = randomSpawnBefore;
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            border.endScale = endScaleBefore;
            restoreBorders(bordersBefore);
            server.setRespawnData(spawnBefore);
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // The world spawn is left where the test server put it, among the tests and inside the border, so no confirm is needed
    @GameTest(environment = CONFIG_COMMAND)
    public void borderKeepingSpawnInsideNeedsNoConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean randomSpawnBefore = config.randomSpawn;
        YoltConfig.WorldBorderSection border = config.worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        double endScaleBefore = border.endScale;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);

        config.randomSpawn = false;
        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            helper.assertValueEqual(run(server, "yolt border " + WIDE), List.of("World border diameter set to " + WIDE + "."), "command output");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size");
        } finally {
            config.randomSpawn = randomSpawnBefore;
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            border.endScale = endScaleBefore;
            restoreBorders(bordersBefore);
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // A player coming through an End portal lands by the platform at 100, 0 whatever the spawn settings, so an End
    // border of 100 blocks around 0, 0 needs confirm. The overworld border is wide enough to hold every test.
    @GameTest(environment = CONFIG_COMMAND)
    public void endScalingStrandingEndPlatformNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        double endScaleBefore = border.endScale;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        WorldBorder end = level(helper, Level.END).getWorldBorder();

        border.enabled = true;
        border.diameter = WIDE;
        try {
            centreAll(server);
            double endSizeBefore = end.getSize();
            List<String> refused = run(server, "yolt endscaling 0.000002");
            helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave the End's arrival platform")
                    && refused.get(0).endsWith(" outside the world border. Add confirm to the command to do it anyway."), "refusal: " + refused);
            helper.assertValueEqual(end.getSize(), endSizeBefore, "end border size after the refusal");
            helper.assertValueEqual(border.endScale, endScaleBefore, "end scale setting after the refusal");

            helper.assertValueEqual(run(server, "yolt endscaling 0.000002 confirm"),
                    List.of("End world border scaling factor set to " + 0.000002 + "."), "output with confirm");
            helper.assertValueEqual(end.getSize(), WIDE * 0.000002, "end border size with confirm");
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            border.endScale = endScaleBefore;
            restoreBorders(bordersBefore);
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // The same through a reload, which can't be confirmed, so the world's borders stay exactly as they are. Every border
    // starts at vanilla's largest, which holds the moved spawn.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadStrandingWorldSpawnKeepsBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();
        try {
            centreAll(server);
            server.getAllLevels().forEach(level -> level.getWorldBorder().setSize(ConfigLoader.MAX_BORDER_DIAMETER));
            moveSpawn(server, BEYOND);
            Files.writeString(path, "{\"randomSpawn\": false, \"worldBorder\": {\"enabled\": true, \"diameter\": " + WIDE
                    + ", \"endScale\": 1.0}}");

            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("Config reloaded.") && output.stream().anyMatch(line -> line.startsWith(
                    "World border kept as it was, since the config's border would leave the world spawn outside it.")),
                    "command output: " + output);
            for (ServerLevel level : server.getAllLevels()) {
                helper.assertValueEqual(level.getWorldBorder().getSize(), (double) ConfigLoader.MAX_BORDER_DIAMETER,
                        level.dimension().identifier() + " border size");
            }
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            server.setRespawnData(spawnBefore);
        }
        helper.succeed();
    }

    // A startup or reload leaves a timed vanilla /worldborder change alone, but the command means "now", like /worldborder set
    @GameTest
    public void borderCommandEndsAMovingBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        int diameterBefore = border.diameter;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        WorldBorder overworld = server.overworld().getWorldBorder();

        border.enabled = true;
        try {
            double size = overworld.getSize();
            overworld.lerpSizeBetween(size, size - 1000, 1_000_000L, server.overworld().getGameTime());
            // confirm, because the test structures sit far outside a 3000 block border. So does the world spawn, which
            // the reply notes.
            helper.assertValueEqual(run(server, "yolt border 3000 confirm"), List.of("World border diameter set to 3000." + SPAWN_NOTE),
                    "command output");
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.STATIONARY, "overworld border status");
            helper.assertValueEqual(overworld.getSize(), 3000.0, "overworld border size");
        } finally {
            border.enabled = enabledBefore;
            border.diameter = diameterBefore;
            restoreBorders(bordersBefore);
            Files.writeString(Settings.path(), fileBefore);
        }
        helper.succeed();
    }

    // The test server's name cache makes up an id for any name, so the death is recorded under that id. Other tests
    // record "Ghost" too, and unkill finds a name in the death log first, so this one has a name of its own.
    @GameTest
    public void unkillEndsLockoutAndReplies(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Unkill");
        UUID id = server.services().nameToIdCache().get(name).orElseThrow().id();
        DeathLockout.recordDeath(id, name);
        try {
            helper.assertTrue(DeathLockout.refusal(id, "Test") != null, "the death did not lock " + name + " out");
            helper.assertValueEqual(run(server, "yolt unkill " + name), List.of("Revived " + name + "."), "command output");
            helper.assertTrue(DeathLockout.refusal(id, "Test") == null, name + " is still locked out");
        } finally {
            DeathLockout.revive(id, "Test", "console");
        }
        helper.succeed();
    }

    @GameTest
    public void unkillWithoutDeathFails(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertValueEqual(run(server, "yolt unkill Ghost2"), List.of("Ghost2 has no death to undo."), "command output");
        helper.succeed();
    }

    // A locked-out player never joins, so the name cache never learns their id; the death log has it, in any case
    @GameTest
    public void unkillFindsPlayerByLoggedName(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Wanderer");
        UUID id = UUID.randomUUID();
        DeathLockout.recordDeath(id, name);
        try {
            helper.assertValueEqual(run(server, "yolt unkill " + name.toLowerCase(Locale.ROOT)), List.of("Revived " + name + "."),
                    "command output");
            helper.assertTrue(DeathLockout.refusal(id, "Test") == null, name + " is still locked out");
        } finally {
            DeathLockout.revive(id, "Test", "console");
        }
        helper.succeed();
    }

    // The console's revive is kept with the death, which stays as history
    @GameTest
    public void reviveRecordsWhoAndWhen(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Revived");
        UUID id = server.services().nameToIdCache().get(name).orElseThrow().id();
        withOwnDeathStore(false, store -> {
            DeathLockout.recordDeath(id, name, name + " drowned");
            long before = System.currentTimeMillis();
            helper.assertValueEqual(run(server, "yolt unkill " + name), List.of("Revived " + name + "."), "command output");
            long after = System.currentTimeMillis();

            DeathLog log = store.latest(id).orElseThrow();
            helper.assertValueEqual(log.revivedBy(), "console", "who revived");
            helper.assertTrue(log.revivedAt() != null && log.revivedAt() >= before && log.revivedAt() <= after,
                    "revived at " + log.revivedAt() + ", not between " + before + " and " + after);
            helper.assertValueEqual(log.deathMessage(), name + " drowned", "the death kept");
            helper.assertTrue(DeathLockout.refusal(id, "Test") == null, name + " is still locked out");
        });
        helper.succeed();
    }

    // Died long ago with 7 days to wait, so there is nothing left to revive and the row stays as it is
    @GameTest
    public void unkillAfterLockoutEndedSaysSo(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Ended");
        UUID id = server.services().nameToIdCache().get(name).orElseThrow().id();
        withOwnDeathStore(false, store -> {
            store.recordDeath(id, name, 1000L, 7, null);
            helper.assertValueEqual(run(server, "yolt unkill " + name), List.of(name + "'s lockout had already ended."),
                    "command output");
            helper.assertTrue(!store.latest(id).orElseThrow().revived(), "the ended death was marked revived");
        });
        helper.succeed();
    }

    // The kill itself goes through; only the lockout is missing, and the admin is told
    @GameTest
    public void killWithClosedStoreSaysSo(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Unrecorded", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        // Leaving saves the player, so the kill is an offline one
        TestPlayers.leave(player);
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean gravesBefore = graves.enabled;
        // No grave to clean up: the player carries nothing, and this test is about the lockout
        graves.enabled = false;
        try {
            withOwnDeathStore(true, closed -> helper.assertValueEqual(run(server, "yolt kill " + who.name()),
                    List.of("Killed " + who.name() + ", but the death could not be recorded; see the server log."),
                    "command output"));
        } finally {
            graves.enabled = gravesBefore;
        }
        helper.assertTrue(DeathLockout.refusal(who.id(), "Test") == null, "a death was recorded");
        helper.succeed();
    }

    // The check after a kill looks for a new row that locks out; an older death, revived, is not one
    @GameTest
    public void killReplyComesFromTheNewDeath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Recorded", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        // Leaving saves the player, so the kill is an offline one
        TestPlayers.leave(player);
        DeathLockout.recordDeath(who.id(), who.name(), "an older death");
        helper.assertValueEqual(DeathLockout.revive(who.id(), "Test", "console"), DeathLockout.ReviveResult.REVIVED, "revive of the older death");
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean gravesBefore = graves.enabled;
        // No grave to clean up: the player carries nothing, and this test is about the lockout
        graves.enabled = false;
        try {
            helper.assertValueEqual(run(server, "yolt kill " + who.name()), List.of("Killed " + who.name() + "."), "command output");
            helper.assertTrue(DeathLockout.refusal(who.id(), "Test") != null, "the kill did not lock " + who.name() + " out");
        } finally {
            graves.enabled = gravesBefore;
            DeathLockout.revive(who.id(), "Test", "console");
        }
        helper.succeed();
    }

    // Name lookups go through the death log, so a name another test also records could find that test's player
    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    // A death store of the test's own, swapped in for this one call so no other test's death lands in it. A closed
    // one makes every query throw, like a failing database.
    private static void withOwnDeathStore(boolean closed, Consumer<DeathStore> use) throws IOException {
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            if (closed) {
                own.store().close();
            }
            use.accept(own.store());
        }
    }

    // What a client is sent: every path of /yolt, our /w and the non-op trees, with each argument's parser,
    // the lowest permission level that may use the node, and whether it runs. Logged so a CI run shows it.
    @GameTest
    public void commandTreesKeepTheirShape(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        List<String> lines = new ArrayList<>();
        List<String> ours = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
            if (child.getName().equals("yolt") || child.getName().equals("w")) {
                ours.add(child.getName());
            }
        }
        lines.add("root order: " + String.join(", ", ours));
        dump(dispatcher, dispatcher.getRoot().getChild("yolt"), "", lines);
        dump(dispatcher, dispatcher.getRoot().getChild("w"), "", lines);
        lines.add("non-op tree, whispers and skin choice off:");
        dumpChildren(CommandRestriction.nonOpTree(false, false), lines);
        lines.add("non-op tree, whispers and skin choice on:");
        dumpChildren(CommandRestriction.nonOpTree(true, true), lines);

        String tree = String.join("\n", lines);
        Yolt.LOGGER.info("Command trees:\n{}", tree);
        helper.assertValueEqual(tree, """
                root order: yolt, w
                yolt | literal | level 0
                yolt border | literal | level 2
                yolt border <diameter> | brigadier:integer {"min":1,"max":59999968} | level 0 | runs
                yolt border <diameter> confirm | literal | level 0 | runs
                yolt center | literal | level 2
                yolt center <pos> | minecraft:vec2 | level 0 | runs
                yolt center <pos> confirm | literal | level 0 | runs
                yolt endscaling | literal | level 2
                yolt endscaling <scaling> | brigadier:double | level 0 | runs
                yolt endscaling <scaling> confirm | literal | level 0 | runs
                yolt respawncooldown | literal | level 2
                yolt respawncooldown <days> | brigadier:integer {"min":0} | level 0 | runs
                yolt kill | literal | level 2
                yolt kill <player> | brigadier:string {"type":"phrase"} | level 0 | runs
                yolt unkill | literal | level 2
                yolt unkill <player> | brigadier:string {"type":"phrase"} | level 0 | runs
                yolt grave | literal | level 2
                yolt grave list | literal | level 0 | runs
                yolt grave remove | literal | level 0
                yolt grave remove <pos> | minecraft:block_pos | level 0 | runs
                yolt adminview | literal | level 2 | runs
                yolt reload | literal | level 2 | runs
                yolt skin | literal | level 0
                yolt skin set | literal | level 0
                yolt skin set <mojangName> | brigadier:string {"type":"word"} | level 0 | runs
                yolt skin set <player> | brigadier:string {"type":"phrase"} | level 2
                yolt skin set <player> <mojangName> | brigadier:string {"type":"word"} | level 0 | runs
                yolt skin clear | literal | level 0 | runs
                yolt skin clear <player> | brigadier:string {"type":"phrase"} | level 2 | runs
                w | literal | level 0
                w <target> | brigadier:string {"type":"word"} | level 0
                w <target> <message> | minecraft:message | level 0 | runs
                non-op tree, whispers and skin choice off:
                help | literal | level 0 | runs
                non-op tree, whispers and skin choice on:
                help | literal | level 0 | runs
                w | literal | level 0
                w <target> | brigadier:string {"type":"word"} | level 0
                w <target> <message> | minecraft:message | level 0 | runs
                yolt | literal | level 0
                yolt skin | literal | level 0
                yolt skin set | literal | level 0
                yolt skin set <mojangName> | brigadier:string {"type":"word"} | level 0 | runs
                yolt skin clear | literal | level 0 | runs""", "command trees");
        helper.succeed();
    }

    @GameTest
    public void killUnknownNameChangesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertValueEqual(run(server, "yolt kill NoSuchPlayer123"), List.of("Player NoSuchPlayer123 not found."), "command output");
        // The test server's name lookup makes up an offline id for any name, so that id must not be locked out
        helper.assertTrue(DeathLockout.refusal(NameAndId.createOffline("NoSuchPlayer123").id(), "Test") == null, "a death was recorded");
        helper.succeed();
    }

    // Swaps the whole config object, so it runs with the other tests that do
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadReadsFileChanges(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        try {
            Settings.get().randomSpawn = true;
            // The border stays off, as the test server needs
            Files.writeString(path, "{\"randomSpawn\": false, \"worldBorder\": {\"enabled\": false}}");
            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), "command output");
            helper.assertFalse(Settings.get().randomSpawn, "randomSpawn still on after reload");
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
        }
        helper.succeed();
    }

    // One typo in the file must not reset a running server, or undo the test server's border override. Should the
    // check fail, the reload swaps the whole config object, so it runs with the tests that do.
    @GameTest(environment = CONFIG_COMMAND)
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
                restoreConfig(server);
            }
        }
        helper.succeed();
    }

    // A moved file is no reload: the command fails, keeps the live config and writes no new file
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadWithMissingFileChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        Path moved = path.resolveSibling(path.getFileName() + ".moved");
        YoltConfig configBefore = Settings.get();
        Files.move(path, moved, StandardCopyOption.REPLACE_EXISTING);
        try {
            int[] result = {-1};
            List<String> output = run(server, "yolt reload", value -> result[0] = value);
            helper.assertValueEqual(output, List.of("Config not reloaded, nothing changed: config file is missing, nothing changed"),
                    "command output");
            helper.assertValueEqual(result[0], 0, "command result");
            helper.assertFalse(Files.exists(path), "a config file was written");
            helper.assertTrue(Settings.get() == configBefore, "live config was replaced");
        } finally {
            Files.move(moved, path, StandardCopyOption.REPLACE_EXISTING);
            if (Settings.get() != configBefore) {
                restoreConfig(server);
            }
        }
        helper.succeed();
    }

    // A border value that fell back at startup is a default (10,000) that could strand players, so the world keeps its own
    // border. Settings.load and WorldBorders.apply are what the mod runs at start and on SERVER_STARTED.
    @GameTest(environment = CONFIG_COMMAND)
    public void startupWithBadBorderValueKeepsSavedBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        SavedBackups backups = saveBackups(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        try {
            // A size YOLT's defaults give no dimension (10,000, or 20,000 for the end)
            server.getAllLevels().forEach(level -> level.getWorldBorder().setSize(30000));
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": \"2OOOO\"}}");
            Settings.load();
            WorldBorders.apply(server);
            helper.assertTrue(Settings.borderHeld(), "border not held");
            for (ServerLevel level : server.getAllLevels()) {
                helper.assertValueEqual(level.getWorldBorder().getSize(), 30000.0, level.dimension().identifier() + " border size");
            }
        } finally {
            Files.writeString(path, fileBefore);
            Settings.load();
            TestServerSetup.applyTestSettings();
            restoreBorders(bordersBefore);
            restoreBackups(backups);
        }
        helper.succeed();
    }

    // A held border waits through a reload that still has a bad border value, then goes through the reload's stranding
    // check and is applied once the value is good
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadAppliesHeldBorderOnceItIsGood(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        SavedBackups backups = saveBackups(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        String typo = "{\"worldBorder\": {\"enabled\": true, \"diameter\": \"2OOOO\"}}";
        try {
            server.getAllLevels().forEach(level -> level.getWorldBorder().setSize(30000));
            // With no .bak there, the startup keeps the typed file as .bak itself, which is the name the reply gives
            Files.deleteIfExists(backups.bak());
            Files.writeString(path, typo);
            Settings.load();

            // The startup load wrote the default over the typo, so it goes back in
            Files.writeString(path, typo);
            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("World border still not applied, since the config has a bad worldBorder.diameter."
                    + " The reload saved the value in use over it, so set it again in the file, then reload. The file as it was"
                    + " at startup is in you-only-live-twice.json.bak."), "command output: " + output);
            helper.assertTrue(Settings.borderHeld(), "border no longer held after a bad value");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 30000.0, "overworld border size after the bad value");

            // Bigger than the world's border, so it leaves nobody new outside
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 40000, \"endScale\": 1.0}}");
            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), "command output");
            helper.assertFalse(Settings.borderHeld(), "border still held");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 40000.0, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            Settings.load();
            TestServerSetup.applyTestSettings();
            // The test's reloads applied their files to the world
            LiveSettings.applyAll(server);
            restoreBorders(bordersBefore);
            restoreBackups(backups);
        }
        helper.succeed();
    }

    // A config from a newer YOLT is never rewritten, so after a held border's reload the typo is still in the file, and
    // the startup kept no copy of it
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadOfNewerConfigSaysTheTypoIsStillInTheFile(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        SavedBackups backups = saveBackups(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        String newer = "{\"configVersion\": " + (ConfigLoader.CURRENT_VERSION + 1)
                + ", \"worldBorder\": {\"enabled\": true, \"diameter\": \"2OOOO\"}}";
        try {
            server.getAllLevels().forEach(level -> level.getWorldBorder().setSize(30000));
            Files.writeString(path, newer);
            Settings.load();
            helper.assertTrue(Settings.borderHeld(), "border not held");

            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("World border still not applied, since the config has a bad worldBorder.diameter."
                    + " Fix it, then reload again."), "command output: " + output);
            helper.assertValueEqual(Files.readString(path), newer, "config file");
            helper.assertTrue(Settings.borderHeld(), "border no longer held");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 30000.0, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            Settings.load();
            TestServerSetup.applyTestSettings();
            // The test's reload applied its file to the world
            LiveSettings.applyAll(server);
            restoreBorders(bordersBefore);
            restoreBackups(backups);
        }
        helper.succeed();
    }

    // What the startup's WARN asks for: a plain reload of the file it rewrote. The worldBorder section is the same as the
    // held one, yet applying it would put the 10,000 default over the world's 30,000 border with the player 7000 blocks
    // from its centre, so the border stays held. The settings are put back before the player leaves, since the startup
    // load turns bodies on.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadWhileHeldChecksTheUnchangedBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        SavedBackups backups = saveBackups(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "HeldOut", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        try {
            server.getAllLevels().forEach(level -> level.getWorldBorder().setSize(30000));
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 7000, player.getZ());
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": \"2OOOO\"}}");
            Settings.load();
            helper.assertTrue(Settings.borderHeld(), "border not held after the startup load");

            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("Config reloaded.") && output.stream().anyMatch(line ->
                    line.startsWith("World border kept as it was, since the config's border would leave ") && line.contains(name)),
                    "command output: " + output);
            helper.assertTrue(Settings.borderHeld(), "border no longer held");
            helper.assertValueEqual(overworld.getSize(), 30000.0, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            Settings.load();
            TestServerSetup.applyTestSettings();
            // The test's reload applied its file to the world
            LiveSettings.applyAll(server);
            restoreBorders(bordersBefore);
            restoreBackups(backups);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A reload can't be confirmed, so it keeps the live border. The settings are put back before the player leaves,
    // since the reloaded file turns bodies on.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadThatStrandsKeepsBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        YoltConfig.WorldBorderSection borderBefore = Settings.get().worldBorder;
        boolean enabledBefore = borderBefore.enabled;
        int diameterBefore = borderBefore.diameter;
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        double sizeBefore = server.overworld().getWorldBorder().getSize();
        ServerPlayer player = TestPlayers.join(helper, "Stranded", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        try {
            // The test structures sit millions of blocks out, far outside a 1000 block border
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 1000}}");
            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("Config reloaded.") && output.stream().anyMatch(line -> line.contains(name)
                    && line.contains("A restart will apply the config's border") && line.endsWith("or change the file.")),
                    "command output: " + output);
            helper.assertValueEqual(Settings.get().worldBorder.diameter, diameterBefore, "live diameter");
            helper.assertValueEqual(Settings.get().worldBorder.enabled, enabledBefore, "live border switch");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), sizeBefore, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The world's borders already are what the file holds, so the reload changes none, and the player, already far
    // outside the 1000 block border, is no reason to warn
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadWithUnchangedBorderSaysNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Unchanged", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.WorldBorderSection live = Settings.get().worldBorder;
        try {
            live.enabled = true;
            live.diameter = 1000;
            live.endScale = 2.0;
            WorldBorders.apply(server);
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 1000, \"endScale\": 2.0}}");
            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), "command output");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 1000.0, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The player is already outside the live 1000 block border, so growing it to 2000 strands nobody new
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadToBiggerBorderSkipsThoseAlreadyOutside(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "AlreadyOut", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.WorldBorderSection live = Settings.get().worldBorder;
        try {
            live.enabled = true;
            live.diameter = 1000;
            WorldBorders.apply(server);
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 2000}}");
            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), "command output");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 2000, "live diameter");
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // YOLT's border is 2000, but a timed /worldborder has since grown the overworld's to 4000, and the player is 1500
    // blocks from its centre. A reload of the unchanged file must not snap the border back onto them.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadAfterLiveBorderDriftedChecksStranding(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Drifted", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        YoltConfig.WorldBorderSection live = Settings.get().worldBorder;
        try {
            live.enabled = true;
            live.diameter = 2000;
            live.endScale = 2.0;
            WorldBorders.apply(server);
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 1500, player.getZ());
            overworld.setSize(4000);
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 2000, \"endScale\": 2.0}}");

            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("Config reloaded.") && output.stream().anyMatch(line ->
                    line.startsWith("World border kept as it was, since the config's border would leave ") && line.contains(name)),
                    "command output: " + output);
            helper.assertValueEqual(overworld.getSize(), 4000.0, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The overworld's border shrank to 1000 under a timed /worldborder while YOLT's stayed 2000. Growing it back
    // leaves nobody outside, so the reload applies the config's border.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadAfterLiveBorderDriftedAppliesWhenNobodyIsStranded(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        YoltConfig.WorldBorderSection live = Settings.get().worldBorder;
        try {
            live.enabled = true;
            live.diameter = 2000;
            live.endScale = 2.0;
            WorldBorders.apply(server);
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setSize(1000);
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 2000, \"endScale\": 2.0}}");

            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), "command output");
            helper.assertValueEqual(overworld.getSize(), 2000.0, "overworld border size");
        } finally {
            Files.writeString(path, fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // A startup load that refuses a value keeps the file it rewrites as a .bak, or beside it with the time added when the
    // .bak holds something else. bytes is null when there was no .bak.
    private record SavedBackups(Path bak, byte[] bytes, List<Path> timed) {}

    private static SavedBackups saveBackups(Path config) throws IOException {
        Path bak = config.resolveSibling(config.getFileName() + ".bak");
        return new SavedBackups(bak, Files.exists(bak) ? Files.readAllBytes(bak) : null, timedCopies(bak));
    }

    // Puts back the .bak a test's startup loads may have written, or removes it if there was none, and removes the timed
    // copies they made
    private static void restoreBackups(SavedBackups saved) throws IOException {
        if (saved.bytes() == null) {
            Files.deleteIfExists(saved.bak());
        } else {
            Files.write(saved.bak(), saved.bytes());
        }
        for (Path copy : timedCopies(saved.bak())) {
            if (!saved.timed().contains(copy)) {
                Files.delete(copy);
            }
        }
    }

    private static List<Path> timedCopies(Path bak) throws IOException {
        String prefix = bak.getFileName() + "-";
        try (Stream<Path> files = Files.list(bak.toAbsolutePath().getParent())) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    private static void assertCentre(GameTestHelper helper, ServerLevel level, double x, double z) {
        helper.assertValueEqual(level.getWorldBorder().getCenterX(), x, level.dimension().identifier() + " border centre x");
        helper.assertValueEqual(level.getWorldBorder().getCenterZ(), z, level.dimension().identifier() + " border centre z");
    }

    // Every level's border centre and size, for restoreBorders
    private static Map<ServerLevel, double[]> saveBorders(MinecraftServer server) {
        Map<ServerLevel, double[]> saved = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            WorldBorder border = level.getWorldBorder();
            saved.put(level, new double[] {border.getCenterX(), border.getCenterZ(), border.getSize()});
        }
        return saved;
    }

    // Reads the config file back and applies it to the world as a reload does, with the test server's overrides first
    // so its border stays off. Without the apply, the world would keep what the test's reload set.
    static void restoreConfig(MinecraftServer server) {
        Settings.reload();
        TestServerSetup.applyTestSettings();
        LiveSettings.applyAll(server);
    }

    private static void restoreBorders(Map<ServerLevel, double[]> saved) {
        saved.forEach((level, border) -> {
            level.getWorldBorder().setCenter(border[0], border[1]);
            level.getWorldBorder().setSize(border[2]);
        });
    }

    // Every border around 0, 0, so a WIDE one holds everything a test hasn't put past it
    private static void centreAll(MinecraftServer server) {
        server.getAllLevels().forEach(level -> level.getWorldBorder().setCenter(0, 0));
    }

    // The test server puts the world spawn among the tests; the caller puts back the spawn it saved
    private static void moveSpawn(MinecraftServer server, int x) {
        server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(x, 64, 0), 0, 0));
    }

    private static ServerLevel level(GameTestHelper helper, ResourceKey<Level> dimension) {
        ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level != null, "test server has no level " + dimension);
        return level;
    }

    private static void dumpChildren(RootCommandNode<CommandSourceStack> root, List<String> lines) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>(root);
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            dump(dispatcher, child, "", lines);
        }
    }

    private static void dump(CommandDispatcher<CommandSourceStack> dispatcher, CommandNode<CommandSourceStack> node,
                             String parent, List<String> lines) {
        String path = parent + (node instanceof ArgumentCommandNode<?, ?> ? "<" + node.getName() + ">" : node.getName());
        StringBuilder line = new StringBuilder(path).append(" | ");
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            // The parser name and properties the client is sent, as vanilla's commands report writes them
            JsonObject json = ArgumentUtils.serializeNodeToJson(dispatcher, node);
            line.append(json.get("parser").getAsString());
            if (json.has("properties")) {
                line.append(' ').append(json.get("properties"));
            }
            if (argument.getCustomSuggestions() != null) {
                line.append(" | own suggestions");
            }
        } else {
            line.append("literal");
        }
        line.append(" | ").append(lowestLevel(node));
        if (node.getCommand() != null) {
            line.append(" | runs");
        }
        lines.add(line.toString());
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            dump(dispatcher, child, path + " ", lines);
        }
    }

    private static String lowestLevel(CommandNode<CommandSourceStack> node) {
        List<PermissionSet> levels = List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.MODERATOR,
                LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.ADMIN, LevelBasedPermissionSet.OWNER);
        for (int level = 0; level < levels.size(); level++) {
            if (node.canUse(Commands.createCompilationContext(levels.get(level)))) {
                return "level " + level;
            }
        }
        return "no level";
    }

    // Runs the command as the console and returns every line it sent back
    private static List<String> run(MinecraftServer server, String command) {
        return run(server, command, value -> {});
    }

    // The same, and hands the command's return value to result
    private static List<String> run(MinecraftServer server, String command, IntConsumer result) {
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
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSource(capture).withCallback((success, value) -> result.accept(value)), command);
        return output;
    }
}
