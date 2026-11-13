package bobbynooby.dev.gametest;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.ConfigLoader;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.CommandGameTests.SavedBackups;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.world.LiveSettings;
import bobbynooby.dev.world.WorldBorders;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static bobbynooby.dev.gametest.CommandGameTests.CONFIG_COMMAND;
import static bobbynooby.dev.gametest.CommandGameTests.restoreBackups;
import static bobbynooby.dev.gametest.CommandGameTests.restoreBorders;
import static bobbynooby.dev.gametest.CommandGameTests.restoreConfig;
import static bobbynooby.dev.gametest.CommandGameTests.run;
import static bobbynooby.dev.gametest.CommandGameTests.saveBackups;
import static bobbynooby.dev.gametest.CommandGameTests.saveBorders;
import static bobbynooby.dev.gametest.CommandGameTests.uniqueName;

// /yolt border, center and endscaling, /yolt reload and the startup load, with the border checks they share.
// Commands run as the console, which has no player. Each test puts the settings and the config file back in a finally block.
public class BorderAndReloadGameTests {
    // A batch of its own: it holds the border, which every border command and reload reads
    private static final String STARTUP_BORDER = "you-only-live-twice-gametest:startup_border";
    private static final String SPAWN_NOTE = " The world spawn is outside the new border; move it with /setworldspawn.";
    // The test server keeps bodies off, so every border reply with the feature on says this
    private static final String OFFLINE_NOTE = " Offline players without a body were not checked.";
    // Test structures, and the world spawn among them, sit within ±15 million blocks, so a border this wide around 0, 0
    // holds them all, and BEYOND is past its edge
    private static final int WIDE = 50_000_000;
    private static final int BEYOND = 27_000_000;

    // The test server keeps YOLT's border off, so this turns it on just for the command. confirm, because the test
    // structures and anything in them sit millions of blocks out, far outside a 3000 block border. The world spawn sits
    // among them, so the reply notes it. The command swaps the whole config object, so it runs with the others that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void borderFromConsoleSucceeds(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        try {
            Settings.get().worldBorder.enabled = true;
            helper.assertValueEqual(run(server, "yolt border 3000 confirm"),
                    List.of("World border diameter set to 3000." + SPAWN_NOTE + OFFLINE_NOTE), "command output");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 3000, "diameter setting");
            helper.assertTrue(Files.readString(Settings.path()).contains("\"diameter\": 3000"), "diameter not saved to the config file");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), 3000.0, "overworld border size");
            // With bodies on until their owner is back, every offline survival player has a body to check. Back off in
            // the finally, in this tick.
            Settings.get().bodies.enabled = true;
            helper.assertValueEqual(run(server, "yolt border 3000 confirm"), List.of("World border diameter set to 3000." + SPAWN_NOTE),
                    "command output with bodies on");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // The command swaps the whole config object, so it runs with the others that do
    @GameTest(environment = CONFIG_COMMAND)
    public void borderWithFeatureOffSavesAndSaysSo(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String fileBefore = Files.readString(Settings.path());
        double sizeBefore = server.overworld().getWorldBorder().getSize();
        try {
            Settings.get().worldBorder.enabled = false;
            helper.assertValueEqual(run(server, "yolt border 3000"),
                    List.of("World border diameter set to 3000. The world border feature is off, so YOLT sizes no border."), "command output");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 3000, "diameter setting");
            helper.assertTrue(Files.readString(Settings.path()).contains("\"diameter\": 3000"), "diameter not saved to the config file");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), sizeBefore, "overworld border size");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
        }
        helper.succeed();
    }

    // Same limits as the config file, so a command can't save a value the next load would throw away. A broken refusal
    // would swap the whole config object, so it runs with the tests that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void outOfRangeValuesAreRefused(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        int diameterBefore = config.worldBorder.diameter;
        double endScaleBefore = config.worldBorder.endScale;
        int cooldownBefore = config.deathLockout.cooldownDays;
        String fileBefore = Files.readString(Settings.path());
        try {
            helper.assertValueEqual(run(server, "yolt endscaling 0"), List.of("End world border scaling factor must be above 0."), "endscaling 0 output");
            // The End border is the diameter times endScale (2.0 here), and can't pass vanilla's largest border
            String capped = "worldBorder.diameter times endScale can't pass 59999968.";
            helper.assertTrue(diameterBefore == 10000 && endScaleBefore == 2.0, "the test server's border values");
            helper.assertValueEqual(run(server, "yolt border 40000000"), List.of(capped), "border past the End cap");
            helper.assertValueEqual(run(server, "yolt endscaling 10000"), List.of(capped), "endscaling past the End cap");
            for (String command : List.of("yolt border 0", "yolt border 59999969", "yolt endscaling -1", "yolt respawncooldown -1")) {
                List<String> output = run(server, command);
                helper.assertTrue(!output.isEmpty() && output.stream().noneMatch(line -> line.contains("set to")), command + " was not refused: " + output);
            }
            helper.assertTrue(Settings.get() == config, "the live config was replaced");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, diameterBefore, "diameter setting");
            helper.assertValueEqual(Settings.get().worldBorder.endScale, endScaleBefore, "end scale setting");
            helper.assertValueEqual(Settings.get().deathLockout.cooldownDays, cooldownBefore, "cooldown setting");
            helper.assertValueEqual(Files.readString(Settings.path()), fileBefore, "config file");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
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
            helper.assertValueEqual(run(server, "yolt center 100 -200"), List.of("World border center set to 100, -200." + OFFLINE_NOTE),
                    "command output");
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
                    List.of("World border center set to " + spawn.getX() + ", " + spawn.getZ() + "." + OFFLINE_NOTE),
                    "output with the spawn inside");
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

    // The overworld border is 4000 blocks now but shrinking to 2000, and /yolt center leaves it moving. A centre 1500
    // blocks from the player holds them for now, but not once the border has shrunk. Like the test above, it runs in the
    // config command batch, so the default batch's players can't push this one's name off the list.
    @GameTest(environment = CONFIG_COMMAND)
    public void centerChecksWhereAMovingBorderEnds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        boolean enabledBefore = border.enabled;
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Shrinking", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();

        border.enabled = true;
        try {
            WorldBorder overworld = server.overworld().getWorldBorder();
            double[] before = bordersBefore.get(server.overworld());
            overworld.lerpSizeBetween(4000, 2000, 1_000_000L, server.overworld().getGameTime());
            BlockPos at = player.blockPosition();
            List<String> refused = run(server, "yolt center " + (at.getX() - 1500) + " " + at.getZ());
            helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave ") && refused.get(0).contains(name),
                    "refusal: " + refused);
            assertCentre(helper, server.overworld(), before[0], before[1]);
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.SHRINKING, "overworld border status after the refusal");
        } finally {
            border.enabled = enabledBefore;
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The overworld centre is put 1100 blocks west of the player, so a 2000 block border ends 100 blocks short of them.
    // The command swaps the whole config object, so it runs with the others that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void borderShrinkWithPlayerOutsideNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        int diameterBefore = Settings.get().worldBorder.diameter;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Outside", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        try {
            Settings.get().worldBorder.enabled = true;
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 1100, player.getZ());
            double sizeBefore = overworld.getSize();

            List<String> refused = run(server, "yolt border 2000");
            helper.assertTrue(refused.size() == 1 && refused.get(0).startsWith("This would leave ")
                    && refused.get(0).contains(name) && refused.get(0).contains(" outside the world border."), "refusal: " + refused);
            helper.assertValueEqual(overworld.getSize(), sizeBefore, "overworld border size after the refusal");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, diameterBefore, "diameter setting after the refusal");

            // The world spawn sits among the test structures, so whether the reply notes it depends on where this one is
            List<String> confirmed = run(server, "yolt border 2000 confirm");
            helper.assertTrue(confirmed.size() == 1 && confirmed.get(0).startsWith("World border diameter set to 2000."),
                    "output with confirm: " + confirmed);
            helper.assertValueEqual(overworld.getSize(), 2000.0, "overworld border size with confirm");
            helper.assertValueEqual(Settings.get().worldBorder.diameter, 2000, "diameter setting with confirm");
            helper.assertTrue(DeathLockout.refusal(player.getUUID(), "Test") == null, "a death was recorded for " + name);
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Nobody could reach a grave outside the border to open it. Every border is centred, so a WIDE one holds everything
    // the tests have, and only this grave sits past it: a record alone, since the check reads the records. A refusal
    // changes nothing, but a broken one would save the new diameter and swap the whole config object, so the file and
    // the config are put back, and it runs with the tests that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void borderShrinkOverGraveNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        int diameterBefore = border.diameter;
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
            helper.assertValueEqual(Settings.get().worldBorder.diameter, diameterBefore, "diameter setting after the refusal");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), bordersBefore.get(server.overworld())[2],
                    "overworld border size after the refusal");
        } finally {
            store.remove(grave.id());
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // A pending record's owner took its body over already; the body waits in an unloaded chunk and goes when that loads.
    // So a border that leaves it outside strands nobody, while the same record not taken over needs confirm. Every border
    // is centred, so a WIDE one holds everything the tests have, and only this record sits past it.
    @GameTest(environment = CONFIG_COMMAND)
    public void borderShrinkOverTakenOverBodyNeedsNoConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        BodyStore store = BodyStore.get(server);
        String owner = uniqueName("TakenOver");
        BodySnapshot snapshot = new BodySnapshot(Level.OVERWORLD, new Vec3(BEYOND, 64, 0), 0f, 20f, 0f, 0.0, 0,
                Entity.TOTAL_AIR_SUPPLY, List.of(), Map.of());
        BodyRecord standing = new BodyRecord(UUID.randomUUID(), owner, UUID.randomUUID(), YoltConfig.BodyType.MANNEQUIN,
                System.currentTimeMillis(), false, snapshot);

        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            store.put(standing);
            helper.assertValueEqual(run(server, "yolt border " + WIDE), List.of("This would leave " + owner
                    + "'s body outside the world border. Add confirm to the command to do it anyway."), "refusal over the body");

            store.put(standing.withPendingRemoval(true));
            helper.assertValueEqual(run(server, "yolt border " + WIDE),
                    List.of("World border diameter set to " + WIDE + "." + OFFLINE_NOTE), "output over the taken-over body");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size");
        } finally {
            store.remove(standing.owner());
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
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
        YoltConfig.WorldBorderSection border = config.worldBorder;
        int diameterBefore = border.diameter;
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
            helper.assertValueEqual(Settings.get().worldBorder.diameter, diameterBefore, "diameter setting after the refusal");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), bordersBefore.get(server.overworld())[2],
                    "overworld border size after the refusal");

            helper.assertValueEqual(run(server, "yolt border " + WIDE + " confirm"),
                    List.of("World border diameter set to " + WIDE + "." + SPAWN_NOTE + OFFLINE_NOTE),
                    "output with confirm");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size with confirm");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            server.setRespawnData(spawnBefore);
        }
        helper.succeed();
    }

    // Random spawn puts a first join inside the border instead, so the same border only gets a note
    @GameTest(environment = CONFIG_COMMAND)
    public void borderLeavingSpawnOutsideOnlyNotesWithRandomSpawn(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        YoltConfig.WorldBorderSection border = config.worldBorder;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();

        config.randomSpawn = true;
        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            moveSpawn(server, BEYOND);
            helper.assertValueEqual(run(server, "yolt border " + WIDE),
                    List.of("World border diameter set to " + WIDE + "." + SPAWN_NOTE + OFFLINE_NOTE),
                    "command output");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
            server.setRespawnData(spawnBefore);
        }
        helper.succeed();
    }

    // The world spawn is left where the test server put it, among the tests and inside the border, so no confirm is needed
    @GameTest(environment = CONFIG_COMMAND)
    public void borderKeepingSpawnInsideNeedsNoConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        YoltConfig.WorldBorderSection border = config.worldBorder;
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);

        config.randomSpawn = false;
        border.enabled = true;
        border.endScale = 1.0;
        try {
            centreAll(server);
            helper.assertValueEqual(run(server, "yolt border " + WIDE),
                    List.of("World border diameter set to " + WIDE + "." + OFFLINE_NOTE), "command output");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
        }
        helper.succeed();
    }

    // A player coming through an End portal lands by the platform at 100, 0 whatever the spawn settings, so an End
    // border of 100 blocks around 0, 0 needs confirm. The overworld border is wide enough to hold every test.
    @GameTest(environment = CONFIG_COMMAND)
    public void endScalingStrandingEndPlatformNeedsConfirm(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.WorldBorderSection border = Settings.get().worldBorder;
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
            helper.assertValueEqual(Settings.get().worldBorder.endScale, endScaleBefore, "end scale setting after the refusal");

            helper.assertValueEqual(run(server, "yolt endscaling 0.000002 confirm"),
                    List.of("End world border scaling factor set to " + 0.000002 + "." + OFFLINE_NOTE),
                    "output with confirm");
            helper.assertValueEqual(end.getSize(), WIDE * 0.000002, "end border size with confirm");
            helper.assertValueEqual(Settings.get().worldBorder.endScale, 0.000002, "end scale setting with confirm");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
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

    // A startup or reload leaves a timed vanilla /worldborder change alone, but the command means "now", like /worldborder
    // set. The command swaps the whole config object, so it runs with the others that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void borderCommandEndsAMovingBorder(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String fileBefore = Files.readString(Settings.path());
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        WorldBorder overworld = server.overworld().getWorldBorder();
        try {
            Settings.get().worldBorder.enabled = true;
            double size = overworld.getSize();
            overworld.lerpSizeBetween(size, size - 1000, 1_000_000L, server.overworld().getGameTime());
            // confirm, because the test structures sit far outside a 3000 block border. So does the world spawn, which
            // the reply notes.
            helper.assertValueEqual(run(server, "yolt border 3000 confirm"),
                    List.of("World border diameter set to 3000." + SPAWN_NOTE + OFFLINE_NOTE), "command output");
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.STATIONARY, "overworld border status");
            helper.assertValueEqual(overworld.getSize(), 3000.0, "overworld border size");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
            restoreBorders(bordersBefore);
        }
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

    // A reload whose rewrite fails still applies the file, and says the file is as it was, rather than failing the
    // command. The write goes through a temp file, which can't be made where a folder is.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadThatCantRewriteTheFileStillApplies(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Path inTmp = tmp.resolve("inside");
        // The border stays off, as the test server needs
        String typed = "{\"randomSpawn\": false, \"worldBorder\": {\"enabled\": false}}";
        try {
            Settings.get().randomSpawn = true;
            Files.writeString(path, typed);
            Files.createDirectories(inTmp);
            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.size() == 2 && output.get(0).equals("Config reloaded.")
                    && output.get(1).startsWith("The config file could not be rewritten (")
                    && output.get(1).endsWith("), so it is left as it was."), "command output: " + output);
            helper.assertFalse(Settings.get().randomSpawn, "randomSpawn still on after reload");
            helper.assertValueEqual(Files.readString(path), typed, "config file");
        } finally {
            Files.deleteIfExists(inTmp);
            Files.deleteIfExists(tmp);
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

    // A startup checks the saved border before it applies it, so a border saved while held, or edited into the file while
    // the server was down, can't strand the world spawn. Every border starts at vanilla's largest, so only the moved spawn
    // is past the file's border. With random spawn on, first joins are put inside it, so the same border is applied.
    // Settings.load and YouOnlyLiveTwice.onServerStarted are what the mod runs at start.
    @GameTest(environment = STARTUP_BORDER)
    public void startupHoldsASavedBorderThatStrandsTheSpawn(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        SavedBackups backups = saveBackups(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();
        String border = "\"worldBorder\": {\"enabled\": true, \"diameter\": " + WIDE + ", \"endScale\": 1.0}";
        try {
            centreAll(server);
            server.getAllLevels().forEach(level -> level.getWorldBorder().setSize(ConfigLoader.MAX_BORDER_DIAMETER));
            moveSpawn(server, BEYOND);

            Files.writeString(path, "{\"randomSpawn\": false, " + border + "}");
            startUp(server);
            helper.assertTrue(Settings.borderHeld(), "a border that strands the world spawn was not held");
            for (ServerLevel level : server.getAllLevels()) {
                helper.assertValueEqual(level.getWorldBorder().getSize(), (double) ConfigLoader.MAX_BORDER_DIAMETER,
                        level.dimension().identifier() + " border size");
            }

            Files.writeString(path, "{\"randomSpawn\": true, " + border + "}");
            startUp(server);
            helper.assertFalse(Settings.borderHeld(), "a border that strands nothing was held");
            helper.assertValueEqual(server.overworld().getWorldBorder().getSize(), (double) WIDE, "overworld border size with random spawn");
        } finally {
            Files.writeString(path, fileBefore);
            Settings.load();
            TestServerSetup.applyTestSettings();
            LiveSettings.applyAll(server);
            restoreBorders(bordersBefore);
            server.setRespawnData(spawnBefore);
            restoreBackups(backups);
        }
        helper.succeed();
    }

    // A server stopping ends the hold its own startup check set, so the next server in the same process checks its own
    // border. A hold for bad border values in the loaded config stays, since the next server runs on that same config.
    @GameTest(environment = STARTUP_BORDER)
    public void serverStopEndsOnlyItsOwnBorderHold(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        SavedBackups backups = saveBackups(path);
        try {
            helper.assertFalse(Settings.borderHeld(), "border held before the test");
            Settings.holdBorder();
            stopAndStart(server);
            helper.assertFalse(Settings.borderHeld(), "the startup check's hold carried into the next server");

            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": \"2OOOO\"}}");
            Settings.load();
            helper.assertTrue(Settings.borderHeld(), "a bad border value wasn't held");
            stopAndStart(server);
            helper.assertTrue(Settings.borderHeld(), "the hold for a bad border value ended with the server");
        } finally {
            Files.writeString(path, fileBefore);
            Settings.onServerStarting(server);
            Settings.load();
            TestServerSetup.applyTestSettings();
            LiveSettings.applyAll(server);
            restoreBackups(backups);
        }
        helper.succeed();
    }

    // What a process running a second server does to the settings between the two. YOLT is inactive in between.
    private static void stopAndStart(MinecraftServer server) {
        Settings.onServerStopped(server);
        Settings.onServerStarting(server);
    }

    // The mod's startup with the test server's overrides, but YOLT's border on as the file has it
    private static void startUp(MinecraftServer server) {
        Settings.load();
        TestServerSetup.applyTestSettings();
        Settings.get().worldBorder.enabled = true;
        YouOnlyLiveTwice.onServerStarted(server);
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
    // since the reloaded file turns bodies on. The test server keeps the border feature off, and the kept live section
    // keeps it off, so the advice says how to turn it on: the border commands alone would only save their value.
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
                    && line.startsWith("World border kept as it was, since the config's border would leave ")
                    && line.endsWith(" outside it. The world border feature stays off too, so set the border with /yolt border or"
                            + " endscaling, then run /yolt config worldBorder.enabled true confirm to apply it now, or change the"
                            + " file, then run /yolt reload.")),
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

            // The feature was on before the reload, so the border commands can apply it
            List<String> output = run(server, "yolt reload");
            helper.assertTrue(output.contains("Config reloaded.") && output.stream().anyMatch(line ->
                    line.startsWith("World border kept as it was, since the config's border would leave ") && line.contains(name)
                            && line.endsWith(" outside it. Use /yolt border or endscaling with confirm to apply it now, or change"
                            + " the file, then run /yolt reload.")),
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

    // A timed /worldborder is growing the overworld's border from 4000 to 5000 blocks, centred 1500 blocks from the
    // player, so the config's 2000 would cut them. A reload leaves a moving border to finish, so it neither warns nor
    // resizes it. The live 1000 block borders already leave everything else outside, so nothing else is newly outside.
    @GameTest(environment = CONFIG_COMMAND)
    public void reloadLeavesAMovingBorderToFinish(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        Path path = Settings.path();
        String fileBefore = Files.readString(path);
        Map<ServerLevel, double[]> bordersBefore = saveBorders(server);
        ServerPlayer player = TestPlayers.join(helper, "Moving", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.WorldBorderSection live = Settings.get().worldBorder;
        try {
            live.enabled = true;
            live.diameter = 1000;
            live.endScale = 2.0;
            WorldBorders.apply(server);
            WorldBorder overworld = server.overworld().getWorldBorder();
            overworld.setCenter(player.getX() - 1500, player.getZ());
            overworld.lerpSizeBetween(4000, 5000, 1_000_000L, server.overworld().getGameTime());
            Files.writeString(path, "{\"worldBorder\": {\"enabled\": true, \"diameter\": 2000, \"endScale\": 2.0}}");

            helper.assertValueEqual(run(server, "yolt reload"), List.of("Config reloaded."), "command output");
            helper.assertValueEqual(overworld.getStatus(), BorderStatus.GROWING, "overworld border status");
            helper.assertValueEqual(overworld.getLerpTarget(), 5000.0, "overworld border target");
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

    private static void assertCentre(GameTestHelper helper, ServerLevel level, double x, double z) {
        helper.assertValueEqual(level.getWorldBorder().getCenterX(), x, level.dimension().identifier() + " border centre x");
        helper.assertValueEqual(level.getWorldBorder().getCenterZ(), z, level.dimension().identifier() + " border centre z");
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
}
