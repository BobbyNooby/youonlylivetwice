package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.lockout.Cooldown;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import bobbynooby.dev.lockout.DeathStore;
import bobbynooby.dev.skins.SkinStore;
import bobbynooby.dev.world.LiveSettings;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

// Commands run as the console, which has no player. Each test puts the settings and the config file back in a finally block.
public class CommandGameTests {
    // A batch of its own: a reload swaps the whole config object, which a test holding the old one across ticks would miss
    static final String CONFIG_COMMAND = "you-only-live-twice-gametest:config_command";

    // Writes the config file, so it runs with the other tests that do, apart from the ones holding the config. The log
    // line names the unit as the reply does.
    @GameTest(environment = CONFIG_COMMAND)
    public void respawnCooldownReplyNamesUnitAndScope(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String fileBefore = Files.readString(Settings.path());
        try {
            String scope = " It applies to deaths from now on; use /yolt unkill for the rest.";
            List<String> output = new ArrayList<>();
            List<String> log = OfflineGameTests.yoltLog(() -> output.addAll(run(server, "yolt respawncooldown 7")));
            helper.assertValueEqual(output, List.of("Respawn cooldown set to 7 days." + scope), "output for 7");
            helper.assertTrue(log.stream().anyMatch(line -> line.endsWith(" set the respawn cooldown to 7 days")), "log for 7: " + log);
            output.clear();
            log = OfflineGameTests.yoltLog(() -> output.addAll(run(server, "yolt respawncooldown 1")));
            helper.assertValueEqual(output, List.of("Respawn cooldown set to 1 day." + scope), "output for 1");
            helper.assertTrue(log.stream().anyMatch(line -> line.endsWith(" set the respawn cooldown to 1 day")), "log for 1: " + log);
            helper.assertValueEqual(Settings.get().deathLockout.cooldownDays, 1, "cooldown setting");
            helper.assertTrue(Files.readString(Settings.path()).contains("\"cooldownDays\": 1"), "cooldown not saved to the config file");
        } finally {
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
        }
        helper.succeed();
    }

    // Ruling R4: each death keeps the days it was recorded with, so a new cooldown only reaches deaths from then on.
    // Writes the config file, so it runs with the other tests that do.
    @GameTest(environment = CONFIG_COMMAND)
    public void cooldownChangeLeavesEarlierDeathsAlone(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String fileBefore = Files.readString(Settings.path());
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        try {
            Settings.get().deathLockout.cooldownDays = 7;
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
            Files.writeString(Settings.path(), fileBefore);
            restoreConfig(server);
        }
        helper.succeed();
    }

    // The death is recorded under the id the test server's name cache makes up for any name, and unkill finds it in the
    // death log. Other tests record "Ghost" too, so this one has a name of its own.
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
        ServerPlayer player = TestPlayers.join(helper, "Undying", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        TestPlayers.leave(player);
        helper.assertValueEqual(run(server, "yolt unkill " + name), List.of(name + " has no death to undo."), "command output");
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

    // With the lockout off nothing is recorded, so the kill reads nothing before it either, and a failing store stays
    // quiet. Online, so finding the player reads no death log. The switches go back in this tick.
    @GameTest
    public void killWithLockoutOffReadsNoDeaths(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Unlocked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        YoltConfig config = Settings.get();
        boolean gravesBefore = config.graves.enabled;
        boolean lockoutBefore = config.deathLockout.enabled;
        List<String> output = new ArrayList<>();
        List<String> log;
        try {
            TestPlayers.markLoaded(player);
            // No grave to clean up: the player carries nothing, and this test is about the lockout
            config.graves.enabled = false;
            config.deathLockout.enabled = false;
            try {
                log = OfflineGameTests.yoltLog(() -> {
                    try {
                        withOwnDeathStore(true, closed -> output.addAll(run(server, "yolt kill " + name)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } finally {
                config.graves.enabled = gravesBefore;
                config.deathLockout.enabled = lockoutBefore;
            }
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            helper.assertValueEqual(output, List.of("Killed " + name + "."), "command output");
            helper.assertTrue(log.stream().noneMatch(line -> line.contains("before the kill")), "the kill read the death log: " + log);
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A body whose death could not kill its owner: that death logs it, so the command only replies and nothing is logged
    // twice. A non-empty folder where the old save gets moved makes the save swap fail. Bodies are on for this tick only.
    @GameTest
    public void killThroughABodyThatCouldNotKillRepliesOnce(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "BodyKill", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        YoltConfig.BodiesSection before = BodyTestKit.useDefaultBodies();
        try {
            BodyTestKit.leaveBody(helper, player);
            Path blocker = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(who.id() + ".dat_old");
            Path blockerContent = blocker.resolve("blocker");
            Files.createDirectories(blocker);
            Files.writeString(blockerContent, "");
            List<String> output = new ArrayList<>();
            List<String> log;
            try {
                log = OfflineGameTests.yoltLog(() -> output.addAll(run(server, "yolt kill " + who.name())));
            } finally {
                Files.delete(blockerContent);
                Files.delete(blocker);
            }
            helper.assertValueEqual(output, List.of("Could not kill " + who.name() + "; see the server log."), "command output");
            helper.assertValueEqual(log.stream().filter(line -> line.contains("could not kill") || line.contains("could not be killed")).toList(),
                    List.of("The body of " + who.name() + " died, but they could not be killed"), "the failure's log lines");
            helper.assertTrue(DeathLockout.refusal(who.id(), "Test") == null, "the failed kill locked " + who.name() + " out");
        } finally {
            BodyTestKit.cleanUp(helper, who.id(), before);
        }
        helper.succeed();
    }

    // A death alike in every value to the one before it, as two in the same millisecond with the same message would be,
    // is still a new one: a kill is confirmed by the death's row, not by what the row holds
    @GameTest
    public void killConfirmsANewDeathAlikeTheOneBefore(GameTestHelper helper) throws IOException {
        UUID id = UUID.randomUUID();
        String name = uniqueName("Twin");
        withOwnDeathStore(false, store -> {
            long now = System.currentTimeMillis();
            store.recordDeath(id, name, now, 7, name + " was killed");
            OptionalLong before = DeathLockout.newestDeathId(id, name);
            helper.assertFalse(DeathLockout.deathRecordedAfter(id, name, before), "confirmed with no new death");
            store.recordDeath(id, name, now, 7, name + " was killed");
            helper.assertTrue(DeathLockout.deathRecordedAfter(id, name, before), "the second of two alike deaths was not confirmed");
        });
        helper.succeed();
    }

    // A read that failed before the kill can't tell the kill's death from an older one, so the kill is not confirmed,
    // though the store works again afterwards and holds an older death that still locks the player out
    @GameTest
    public void killNotConfirmedWhenTheReadBeforeItFailed(GameTestHelper helper) throws IOException {
        UUID id = UUID.randomUUID();
        String name = uniqueName("Unread");
        try (LockoutGameTests.OwnStore working = new LockoutGameTests.OwnStore()) {
            working.store().recordDeath(id, name, System.currentTimeMillis(), 7, name + " drowned");
            OptionalLong before;
            try (LockoutGameTests.OwnStore failing = new LockoutGameTests.OwnStore()) {
                failing.store().close();
                before = DeathLockout.newestDeathId(id, name);
            }
            helper.assertTrue(before.isEmpty(), "the read before the kill worked on a closed store");
            helper.assertTrue(DeathLockout.refusal(id, "Test") != null, "the older death does not lock " + name + " out");
            helper.assertFalse(DeathLockout.deathRecordedAfter(id, name, before), "the kill was confirmed by an older death");
        }
        helper.succeed();
    }

    // The same through /yolt kill. Another program holds the database through the read before the kill, which gives up
    // after its 250 ms, and lets go 1.5 s in, well inside the 3 s the death's write waits. So the kill's own death is
    // the newest row, yet the failed read can't tell it from an older one. With the lock test's batch, so the server
    // thread's pause falls among quick checks.
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void killNotConfirmedWhenALockFailedTheReadBeforeIt(GameTestHelper helper)
            throws IOException, SQLException, InterruptedException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "LockedKill", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean gravesBefore = graves.enabled;
        List<String> output = new ArrayList<>();
        List<String> log;
        try (LockoutGameTests.OwnStore own = new LockoutGameTests.OwnStore()) {
            TestPlayers.markLoaded(player);
            try (Connection lock = DriverManager.getConnection(own.url()); Statement statement = lock.createStatement()) {
                statement.execute("BEGIN EXCLUSIVE");
                Thread release = new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                        statement.execute("ROLLBACK");
                    } catch (InterruptedException | SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, "yolt-gametest-lock-release");
                // No grave to clean up: the player carries nothing, and this test is about the lockout
                graves.enabled = false;
                release.start();
                try {
                    log = OfflineGameTests.yoltLog(() -> output.addAll(run(server, "yolt kill " + who.name())));
                } finally {
                    graves.enabled = gravesBefore;
                    release.join();
                }
            }
            helper.assertValueEqual(output, List.of("Killed " + who.name() + ", but the death could not be recorded; see the server log."),
                    "command output");
            helper.assertTrue(log.contains("Could not read the death log of " + who.name() + " before the kill"),
                    "the read before the kill did not meet the lock: " + log);
            helper.assertTrue(log.stream().noneMatch(line -> line.startsWith("Could not record death of ")), "the death's write failed: " + log);
            helper.assertTrue(DeathLockout.refusal(who.id(), "Test") != null, "the kill's death is not in the store");
        } finally {
            ServerPlayer online = server.getPlayerList().getPlayer(who.id());
            if (online != null) {
                TestPlayers.leave(online);
            }
        }
        helper.succeed();
    }

    // Name lookups go through the death log, so a name another test also records could find that test's player
    static String uniqueName(String prefix) {
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

    // What a client is sent: every path of /yolt, our /w, /msg and /tell and the non-op trees, with each argument's parser,
    // the lowest permission level that may use the node, and whether it runs. Logged so a CI run shows it. The levels are
    // read with no player, so a whisper's player-only <target> shows "no level" and vanilla's <targets> shows level 0.
    @GameTest
    public void commandTreesKeepTheirShape(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        List<String> lines = new ArrayList<>();
        List<String> ours = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
            if (List.of("yolt", "w", "msg", "tell").contains(child.getName())) {
                ours.add(child.getName());
            }
        }
        lines.add("root order: " + String.join(", ", ours));
        dump(dispatcher, dispatcher.getRoot().getChild("yolt"), "", lines);
        for (String whisper : List.of("w", "msg", "tell")) {
            dump(dispatcher, dispatcher.getRoot().getChild(whisper), "", lines);
        }
        lines.add("non-op tree, whispers and skin choice off:");
        dumpChildren(CommandRestriction.nonOpTree(false, false), lines);
        lines.add("non-op tree, whispers and skin choice on:");
        dumpChildren(CommandRestriction.nonOpTree(true, true), lines);

        String tree = String.join("\n", lines);
        Yolt.LOGGER.info("Command trees:\n{}", tree);
        helper.assertValueEqual(tree, """
                root order: yolt, w, msg, tell
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
                yolt config | literal | level 2
                yolt config <key> | brigadier:string {"type":"word"} | own suggestions | level 0 | runs
                yolt config <key> <value> | brigadier:string {"type":"greedy"} | own suggestions | level 0 | runs
                yolt status | literal | level 2
                yolt status <player> | brigadier:string {"type":"phrase"} | level 0 | runs
                yolt curse | literal | level 2
                yolt curse <player> | brigadier:string {"type":"phrase"} | level 0
                yolt curse <player> set | literal | level 0
                yolt curse <player> set <hearts> | brigadier:integer {"min":0} | level 0 | runs
                yolt curse <player> clear | literal | level 0 | runs
                yolt bloodmoon | literal | level 2
                yolt bloodmoon start | literal | level 0 | runs
                yolt bloodmoon stop | literal | level 0 | runs
                yolt bloodmoon status | literal | level 0 | runs
                yolt skin | literal | level 0
                yolt skin set | literal | level 0
                yolt skin set <mojangName> | brigadier:string {"type":"word"} | level 0 | runs
                yolt skin set <player> | brigadier:string {"type":"phrase"} | level 2
                yolt skin set <player> <mojangName> | brigadier:string {"type":"word"} | level 0 | runs
                yolt skin clear | literal | level 0 | runs
                yolt skin clear <player> | brigadier:string {"type":"phrase"} | level 2 | runs
                w | literal | level 0
                w <target> | brigadier:string {"type":"word"} | no level
                w <target> <message> | minecraft:message | level 0 | runs
                w <targets> | minecraft:entity {"amount":"multiple","type":"players"} | own suggestions | level 0
                w <targets> <message> | minecraft:message | level 0 | runs
                msg | literal | level 0
                msg <target> | brigadier:string {"type":"word"} | no level
                msg <target> <message> | minecraft:message | level 0 | runs
                msg <targets> | minecraft:entity {"amount":"multiple","type":"players"} | own suggestions | level 0
                msg <targets> <message> | minecraft:message | level 0 | runs
                tell | literal | level 0
                tell <target> | brigadier:string {"type":"word"} | no level
                tell <target> <message> | minecraft:message | level 0 | runs
                tell <targets> | minecraft:entity {"amount":"multiple","type":"players"} | own suggestions | level 0
                tell <targets> <message> | minecraft:message | level 0 | runs
                non-op tree, whispers and skin choice off:
                help | literal | level 0 | runs
                non-op tree, whispers and skin choice on:
                help | literal | level 0 | runs
                w | literal | level 0
                w <target> | brigadier:string {"type":"word"} | level 0
                w <target> <message> | minecraft:message | level 0 | runs
                msg | literal | level 0
                msg <target> | brigadier:string {"type":"word"} | level 0
                msg <target> <message> | minecraft:message | level 0 | runs
                tell | literal | level 0
                tell <target> | brigadier:string {"type":"word"} | level 0
                tell <target> <message> | minecraft:message | level 0 | runs
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
        helper.assertValueEqual(run(server, "yolt kill NoSuchPlayer123"),
                List.of("No player named NoSuchPlayer123 has joined this server."), "command output");
        // Nor under the id an offline login would get
        helper.assertTrue(DeathLockout.refusal(NameAndId.createOffline("NoSuchPlayer123").id(), "Test") == null, "a death was recorded");
        helper.succeed();
    }

    // A command finds a player only where this server knows them: a name that joined here, typed in any case, and a name
    // only the death log has. A name it doesn't know is not found, so no id is made up for it to act on.
    @GameTest
    public void namedPlayerIsFoundOnlyWhereThisServerKnowsThem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SkinStore store = SkinStore.get(server);
        ServerPlayer player = TestPlayers.join(helper, "Cased", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String cased = player.nameAndId().name();
        TestPlayers.leave(player);
        helper.assertValueEqual(run(server, "yolt unkill " + cased.toLowerCase(Locale.ROOT)),
                List.of(cased + " has no death to undo."), "unkill of a name typed in another case");

        String logged = uniqueName("Logged");
        UUID loggedId = UUID.randomUUID();
        DeathLockout.recordDeath(loggedId, logged);
        try {
            store.choose(loggedId, uniqueName("Acct"));
            try {
                helper.assertValueEqual(run(server, "yolt skin clear " + logged),
                        List.of(logged + "'s skin is back to the default. It shows once they rejoin."),
                        "clear for a name only the death log has");
                helper.assertTrue(store.choice(loggedId) == null, "the pick under the logged id is still there");
            } finally {
                store.clearChoice(loggedId);
            }
        } finally {
            DeathLockout.revive(loggedId, logged, "console");
        }

        String unknown = uniqueName("Nobody");
        List<String> notFound = List.of("No player named " + unknown + " has joined this server.");
        for (String command : List.of("yolt kill ", "yolt unkill ", "yolt skin clear ")) {
            helper.assertValueEqual(run(server, command + unknown), notFound, "output of " + command + "for an unknown name");
        }
        helper.assertValueEqual(run(server, "yolt skin set " + unknown + " " + uniqueName("Acct")), notFound,
                "output of yolt skin set for an unknown name");
        UUID madeUp = NameAndId.createOffline(unknown).id();
        helper.assertTrue(DeathLockout.refusal(madeUp, "Test") == null, "a death was recorded under a made-up id");
        helper.assertTrue(store.choice(madeUp) == null, "a pick was made under a made-up id");
        helper.succeed();
    }

    // On an offline-mode server "Steve" and "steve" are two players with two ids. A name typed in the same case as one
    // of them finds that one, even while vanilla's name cache holds the other, as after the other logged in last.
    @GameTest
    public void sameCaseNameWinsOverTheOtherCase(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SkinStore store = SkinStore.get(server);
        String upper = uniqueName("Cases");
        String lower = upper.toLowerCase(Locale.ROOT);
        UUID upperId = UUID.randomUUID();
        store.noteJoin(upperId, upper);
        store.noteJoin(UUID.randomUUID(), lower);
        server.services().nameToIdCache().add(new NameAndId(upperId, upper));
        helper.assertValueEqual(run(server, "yolt skin clear " + lower), List.of(lower + " hasn't picked a skin."),
                "the player found for the name in lower case");
        helper.assertValueEqual(run(server, "yolt skin clear " + upper), List.of(upper + " hasn't picked a skin."),
                "the player found for the name as the cache holds it");
        helper.succeed();
    }

    // A startup load that refuses a value keeps the file it rewrites as a .bak, or beside it with the time added when the
    // .bak holds something else. bytes is null when there was no .bak.
    record SavedBackups(Path bak, byte[] bytes, List<Path> timed) {}

    static SavedBackups saveBackups(Path config) throws IOException {
        Path bak = config.resolveSibling(config.getFileName() + ".bak");
        return new SavedBackups(bak, Files.exists(bak) ? Files.readAllBytes(bak) : null, timedCopies(bak));
    }

    // Puts back the .bak a test's startup loads may have written, or removes it if there was none, and removes the timed
    // copies they made
    static void restoreBackups(SavedBackups saved) throws IOException {
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

    // Every level's border centre and size, for restoreBorders
    static Map<ServerLevel, double[]> saveBorders(MinecraftServer server) {
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

    static void restoreBorders(Map<ServerLevel, double[]> saved) {
        saved.forEach((level, border) -> {
            level.getWorldBorder().setCenter(border[0], border[1]);
            level.getWorldBorder().setSize(border[2]);
        });
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
    static List<String> run(MinecraftServer server, String command) {
        return run(server, command, value -> {});
    }

    // The same, and hands the command's return value to result
    static List<String> run(MinecraftServer server, String command, IntConsumer result) {
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
