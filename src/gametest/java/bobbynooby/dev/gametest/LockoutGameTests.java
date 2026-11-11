package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.Cooldown;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import bobbynooby.dev.lockout.DeathStore;
import bobbynooby.dev.lockout.UncountedDeaths;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

// Uses fresh random uuids, so tests never collide
public class LockoutGameTests {
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 25565);
    // A batch of its own: the join test keeps bodies on across ticks, so another test's logout would leave a body
    private static final String LOCKED_OUT_JOIN = "you-only-live-twice-gametest:locked_out_join";
    // The GameTest server allows one player (GameTestServer.getMaxPlayers), and vanilla refuses "server full" before
    // our check runs. Batches run one at a time, so in this one no other test's player can be online.
    static final String LOGIN_CHECK = "you-only-live-twice-gametest:login_check";

    @GameTest(environment = LOGIN_CHECK)
    public void lockedOutPlayerIsRefusedAtLogin(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Ghost");
        helper.assertTrue(refusedForLockout(loginRefusal(helper.getLevel().getServer(), new NameAndId(uuid, "Ghost"))),
                "locked-out player was not refused with the lockout message");
        helper.succeed();
    }

    // The test server holds one player, so with one online vanilla says "server full" first. A locked-out
    // player should still be told they are dead.
    @GameTest(environment = LOGIN_CHECK)
    public void lockoutBeatsServerFull(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer holder = TestPlayers.join(helper, "Holder", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            helper.assertTrue(server.getPlayerList().getPlayers().size() >= server.getMaxPlayers(), "the server is not full");
            DeathLockout.recordDeath(uuid, "Ghost");
            helper.assertTrue(refusedForLockout(loginRefusal(server, new NameAndId(uuid, "Ghost"))),
                    "a full server hid the lockout message");
        } finally {
            TestPlayers.leave(holder);
        }
        helper.succeed();
    }

    // The second line's wait is CooldownTest's; this pins what login shows from the death log
    @GameTest(environment = LOGIN_CHECK)
    public void loginRefusalShowsDeathMessage(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Faller", "Faller fell from a high place");
        Component refusal = loginRefusal(helper.getLevel().getServer(), new NameAndId(uuid, "Faller"));
        String text = refusal == null ? "" : refusal.getString();
        helper.assertTrue(text.startsWith("You died: Faller fell from a high place\nTry again in "), "login refusal: " + text);
        helper.succeed();
    }

    @GameTest(environment = LOGIN_CHECK)
    public void revivedPlayerCanLogin(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Ghost");
        helper.assertValueEqual(DeathLockout.revive(uuid, "Test", "console"), DeathLockout.ReviveResult.REVIVED, "revive result");
        helper.assertTrue(canLogin(helper, uuid), "revived player was refused");
        helper.succeed();
    }

    // The same player is refused first, with the switch on, so the login below is let in by the switch alone
    @GameTest(environment = LOGIN_CHECK)
    public void lockoutOffLetsEveryoneIn(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        DeathLockout.recordDeath(uuid, "Ghost");
        helper.assertTrue(refusedForLockout(loginRefusal(helper.getLevel().getServer(), new NameAndId(uuid, "Ghost"))),
                "player not refused with the lockout on");
        boolean before = Settings.get().deathLockout.enabled;
        Settings.get().deathLockout.enabled = false;
        try {
            helper.assertTrue(canLogin(helper, uuid), "player refused with the lockout off");
        } finally {
            Settings.get().deathLockout.enabled = before;
        }
        helper.succeed();
    }

    // The switch is checked inside recordDeath, so no caller can start a lockout while it's off
    @GameTest(environment = LOGIN_CHECK)
    public void lockoutOffRecordsNoDeath(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        boolean before = Settings.get().deathLockout.enabled;
        Settings.get().deathLockout.enabled = false;
        try {
            DeathLockout.recordDeath(uuid, "Ghost");
        } finally {
            Settings.get().deathLockout.enabled = before;
        }
        helper.assertTrue(canLogin(helper, uuid), "death recorded with the lockout off");
        helper.succeed();
    }

    // A closed store makes every query throw, like a failing database would. Its folder goes when the test ends.
    @GameTest
    public void storeFailureLetsPlayerIn(GameTestHelper helper) throws IOException {
        UUID uuid = UUID.randomUUID();
        try (OwnStore broken = new OwnStore()) {
            broken.store().close();
            helper.assertTrue(DeathLockout.refusal(uuid, "Test") == null, "player refused while the store is failing");
            DeathLockout.recordDeath(uuid, "Ghost");
            helper.assertValueEqual(DeathLockout.revive(uuid, "Test", "console"), DeathLockout.ReviveResult.FAILED, "revive result on a failing store");
        }
        helper.succeed();
    }

    // A death recorded while the lockout was on counts again once it is back on, though the player joined and played
    // while it was off
    @GameTest(environment = LOGIN_CHECK)
    public void lockoutOffThenOnStillRefuses(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, "OffOn" + uuid.toString().substring(0, 8));
        NameAndId who = new NameAndId(uuid, profile.name());
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        boolean before = lockout.enabled;
        try {
            lockout.enabled = true;
            DeathLockout.recordDeath(uuid, who.name(), who.name() + " was slain");
            helper.assertTrue(refusedForLockout(loginRefusal(server, who)), "not refused with the lockout on");

            lockout.enabled = false;
            helper.assertTrue(loginRefusal(server, who) == null, "refused with the lockout off");
            TestPlayers.leave(TestPlayers.join(helper, profile, GameType.SURVIVAL, new BlockPos(1, 1, 1)));

            lockout.enabled = true;
            Component refusal = loginRefusal(server, who);
            String text = refusal == null ? "" : refusal.getString();
            helper.assertTrue(text.startsWith("You died: " + who.name() + " was slain\nTry again in "),
                    "refusal once the lockout is back on: " + text);
        } finally {
            lockout.enabled = before;
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                TestPlayers.leave(online);
            }
        }
        helper.succeed();
    }

    // With no death store, as when it could not be opened at startup, the lockout lets everyone in rather than no one.
    // The death is still there once the store is back.
    @GameTest(environment = LOGIN_CHECK)
    public void missingDeathStoreLetsLockedOutPlayerIn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        NameAndId who = new NameAndId(UUID.randomUUID(), "Ghost");
        DeathLockout.recordDeath(who.id(), who.name());
        helper.assertTrue(refusedForLockout(loginRefusal(server, who)), "not refused with the store there");
        DeathStore real = DeathLockout.swapStoreForTesting(null);
        try {
            helper.assertTrue(loginRefusal(server, who) == null, "refused with no death store");
        } finally {
            DeathLockout.swapStoreForTesting(real);
        }
        helper.assertTrue(refusedForLockout(loginRefusal(server, who)), "not refused once the store is back");
        helper.succeed();
    }

    // Another program holding the database's write lock, as a backup tool or an SQLite browser can: the check waits
    // out SQLite's busy timeout, fails, and lets the player in rather than keeping everyone out
    @GameTest(environment = LOGIN_CHECK)
    public void lockedDeathStoreLetsLockedOutPlayerIn(GameTestHelper helper) throws IOException, SQLException {
        MinecraftServer server = helper.getLevel().getServer();
        NameAndId who = new NameAndId(UUID.randomUUID(), "Ghost");
        try (OwnStore own = new OwnStore()) {
            DeathLockout.recordDeath(who.id(), who.name());
            helper.assertTrue(refusedForLockout(loginRefusal(server, who)), "not refused with the database free");
            try (Connection lock = DriverManager.getConnection(own.url()); Statement statement = lock.createStatement()) {
                statement.execute("BEGIN EXCLUSIVE");
                helper.assertTrue(loginRefusal(server, who) == null, "refused while the database is locked");
                statement.execute("ROLLBACK");
            }
            helper.assertTrue(refusedForLockout(loginRefusal(server, who)), "not refused once the lock is gone");
        }
        helper.succeed();
    }

    // Ops get no way round the lockout, not even from their own /yolt kill; the console's /yolt unkill lets them back in
    @GameTest(environment = LOGIN_CHECK)
    public void opCanLockThemselvesOut(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer op = TestPlayers.join(helper, "SelfKill", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = op.nameAndId();
        try {
            TestPlayers.op(op);
            TestPlayers.markLoaded(op);
            helper.assertValueEqual(SkinGameTests.console(op.createCommandSourceStack(), "yolt kill " + who.name()),
                    List.of("Killed " + who.name() + "."), "the op's own /yolt kill");
            helper.assertTrue(op.isDeadOrDying(), "the op did not die");
            // Gone before the kick comes, and still an op
            TestPlayers.leave(op);
            helper.assertTrue(server.getPlayerList().isOp(who), "the op is no longer an op");

            Component refusal = loginRefusal(server, who);
            String text = refusal == null ? "" : refusal.getString();
            helper.assertTrue(text.startsWith("You died: " + who.name() + " was killed\nTry again in "), "the op's login refusal: " + text);
            helper.assertValueEqual(SkinGameTests.console(server, "yolt unkill " + who.name()), List.of("Revived " + who.name() + "."),
                    "the console's /yolt unkill");
            helper.assertTrue(loginRefusal(server, who) == null, "the op is still refused after the console's unkill");
        } finally {
            TestPlayers.deop(op);
            leaveIfOnline(op);
            BodyTestKit.removeGraves(helper, who.id());
        }
        helper.succeed();
    }

    // The End's void hurts a player 4 at a time, every 10 ticks or so, until they die, and then once in a while after.
    // One death, recorded once, with the void as its cause. Graves are off for it, in this tick: the grave side is
    // VoidGraveGameTests'.
    @GameTest(dimension = "minecraft:the_end")
    public void endVoidDeathIsRecordedOnce(GameTestHelper helper) throws IOException, SQLException {
        ServerLevel end = helper.getLevel();
        helper.assertValueEqual(end.dimension(), Level.END, "the test's dimension");
        ServerPlayer player = TestPlayers.join(helper, "EndVoid", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.getGameProfile().name();
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean gravesBefore = graves.enabled;
        try (OwnStore own = new OwnStore()) {
            player.snapTo(player.getX(), end.getMinY() - 70, player.getZ());
            TestPlayers.markLoaded(player);
            int hits = 0;
            graves.enabled = false;
            try {
                // Each hit stands in for the ten ticks between two of the void's hits, which let the next one through
                while (!player.isDeadOrDying() && hits < 10) {
                    hitByVoid(end, player);
                    hits++;
                }
                for (int i = 0; i < 3; i++) {
                    hitByVoid(end, player);
                }
            } finally {
                graves.enabled = gravesBefore;
            }
            helper.assertTrue(player.isDeadOrDying(), "the void did not kill the player in " + hits + " hits");
            helper.assertTrue(hits > 1, "the first hit killed the player, so no hit before the death was tried");
            helper.assertValueEqual(own.rows(player.getUUID()),
                    List.of(name + " fell out of the world | " + Settings.get().deathLockout.cooldownDays), "death rows");
        } finally {
            leaveIfOnline(player);
        }
        helper.succeed();
    }

    // The newest death decides, even one shorter than the death before it. Both in this tick, the second after a
    // respawn, as for a player whose first death didn't kick them. The days go back in this tick too.
    @GameTest
    public void laterOfTwoDeathsCounts(GameTestHelper helper) throws IOException, SQLException {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = TestPlayers.join(helper, "Twice", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        int daysBefore = lockout.cooldownDays;
        try (OwnStore own = new OwnStore()) {
            GraveDeathGameTests.respawnBesideTest(helper, player);
            lockout.cooldownDays = 7;
            killWithoutGrave(helper, player);
            // The client's respawn, which also ends the connection's wait for one; until then the player can't be hurt
            player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            ServerPlayer back = level.getServer().getPlayerList().getPlayer(uuid);
            helper.assertTrue(back != null && back != player, "the player did not respawn");
            TestPlayers.markLoaded(back);
            lockout.cooldownDays = 1;
            YoltConfig.GravesSection graves = Settings.get().graves;
            boolean gravesBefore = graves.enabled;
            graves.enabled = false;
            try {
                back.hurtServer(level, level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            } finally {
                graves.enabled = gravesBefore;
                lockout.cooldownDays = daysBefore;
            }
            helper.assertTrue(back.isDeadOrDying(), "the respawned player did not die");

            helper.assertValueEqual(own.rows(uuid), List.of(name + " was killed | 7", name + " fell out of the world | 1"), "death rows");
            String refusal = refusalText(uuid);
            helper.assertTrue(refusal.startsWith("You died: " + name + " fell out of the world\nTry again in "), "refusal: " + refusal);
            DeathLog newest = DeathLockout.newestDeath(uuid);
            helper.assertTrue(newest != null && Cooldown.remainingMillis(newest, System.currentTimeMillis()) <= Duration.ofDays(1).toMillis(),
                    "the wait is longer than the newest death's day: " + refusal);
        } finally {
            lockout.cooldownDays = daysBefore;
            leaveIfOnline(player);
        }
        helper.succeed();
    }

    // A death recorded after the login check, as when a body dies while its owner's spawn area loads. A test player
    // joins without the login check, like a player who passed it before the death.
    @GameTest(maxTicks = 20, environment = LOCKED_OUT_JOIN)
    public void lockedOutJoinerIsKicked(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, "Late" + uuid.toString().substring(0, 8));
        // On until the kick's logout, so a body that logout wrongly left would show
        YoltConfig.BodiesSection before = BodyTestKit.useDefaultBodies();
        boolean waiting = false;
        try {
            DeathLockout.recordDeath(uuid, profile.name());
            ServerPlayer player = TestPlayers.join(helper, profile, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            // The kick waits for the end of the tick, so the rest of the join still runs
            helper.assertTrue(server.getPlayerList().getPlayer(uuid) == player, "kicked inside the join");
            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(uuid) == null, "locked-out joiner was not kicked");
                    helper.assertTrue(BodyStore.get(server).byOwner(uuid).isEmpty(), "the kick left a body");
                } finally {
                    BodyTestKit.cleanUp(helper, uuid, before);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                BodyTestKit.cleanUp(helper, uuid, before);
            }
        }
    }

    // A revive in the same tick as the death, before the kick runs
    @GameTest(maxTicks = 20)
    public void reviveInSameTickCancelsKick(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Saved", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean waiting = false;
        try {
            killWithoutGrave(helper, player);
            helper.assertValueEqual(DeathLockout.revive(player.getUUID(), "Test", "console"), DeathLockout.ReviveResult.REVIVED, "revive result");
            assertStillOnlineLater(helper, player);
            waiting = true;
        } finally {
            if (!waiting) {
                leaveIfOnline(player);
            }
        }
    }

    // No wait means nothing to keep the player out for. Only this death has 0 days; the setting goes back at once.
    @GameTest(maxTicks = 20)
    public void zeroDayDeathDoesNotKick(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "NoWait", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        int daysBefore = lockout.cooldownDays;
        boolean waiting = false;
        try {
            lockout.cooldownDays = 0;
            try {
                killWithoutGrave(helper, player);
            } finally {
                lockout.cooldownDays = daysBefore;
            }
            assertStillOnlineLater(helper, player);
            waiting = true;
        } finally {
            if (!waiting) {
                leaveIfOnline(player);
            }
        }
    }

    // /kill's own message, so a fallback "<name> died" can't pass for it
    @GameTest(maxTicks = 20)
    public void kickAtDeathShowsDeathMessage(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Kicked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        boolean waiting = false;
        try {
            killWithoutGrave(helper, player);
            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(uuid) == null, "the dead player was not kicked");
                    String reason = disconnectReason(player);
                    helper.assertTrue(reason.startsWith("You died: " + name + " was killed\nTry again in "), "kick reason: " + reason);
                } finally {
                    leaveIfOnline(player);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                leaveIfOnline(player);
            }
        }
    }

    // A death in creative doesn't count: no grave, no death record and no lockout, no XP dropped, and the respawn hands
    // the items, XP and score back the way keepInventory would, each once. A second player with no items at all keeps
    // their XP too. All in one tick.
    @GameTest
    public void creativeDeathKeepsItemsNoLockout(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Creative", GameType.CREATIVE, new BlockPos(1, 1, 1));
        ServerPlayer bare = TestPlayers.join(helper, "CreativeXp", GameType.CREATIVE, new BlockPos(5, 1, 5));
        UUID uuid = player.getUUID();
        try {
            helper.assertFalse(Settings.get().deathLockout.countCreativeAndSpectatorDeaths, "creative deaths count by default");
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            player.giveExperienceLevels(10);
            player.setScore(5);
            bare.giveExperienceLevels(7);
            BlockPos at = player.blockPosition();
            BlockPos bareAt = bare.blockPosition();
            GraveDeathGameTests.respawnBesideTest(helper, player);
            GraveDeathGameTests.respawnBesideTest(helper, bare);
            for (ServerPlayer dying : List.of(player, bare)) {
                TestPlayers.markLoaded(dying);
                dying.kill(dying.level());
                helper.assertTrue(dying.isDeadOrDying(), "a creative player did not die");
            }
            helper.assertTrue(BodyTestKit.gravesOf(helper, uuid).isEmpty(), "a creative death made a grave");
            helper.assertTrue(DeathLockout.newestDeath(uuid) == null, "a creative death was recorded");
            helper.assertTrue(DeathLockout.refusal(uuid, "Test") == null, "a creative death locked the player out");
            helper.assertValueEqual(GraveDeathGameTests.itemsNear(helper, Items.DIAMOND, at, 3.0), 0, "diamonds dropped");
            helper.assertValueEqual(orbsNear(helper, at) + orbsNear(helper, bareAt), 0, "XP orbs dropped");

            ServerPlayer back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(back.getInventory().countItem(Items.DIAMOND), 3, "diamonds after the respawn");
            helper.assertValueEqual(back.getInventory().countItem(Items.IRON_HELMET), 1, "helmets after the respawn");
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the helmet is not worn after the respawn");
            helper.assertValueEqual(back.experienceLevel, 10, "XP level after the respawn");
            helper.assertValueEqual(back.getScore(), 5, "score after the respawn");
            helper.assertFalse(back.entityTags().contains(UncountedDeaths.MARK), "the respawned player still has the death's mark");
            ServerPlayer bareBack = server.getPlayerList().respawn(bare, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(bareBack.experienceLevel, 7, "XP level after the respawn of the player with no items");
        } finally {
            leaveIfOnline(player);
            leaveIfOnline(bare);
            BodyTestKit.removeGraves(helper, uuid);
            BodyTestKit.removeGraves(helper, bare.getUUID());
        }
        helper.succeed();
    }

    // Bug 4 of the edge-case analysis: a spectator below the world dies of the void's damage, which gets through a
    // spectator's invulnerability. That death doesn't count, so they are not locked out, and vanilla gives the items back.
    @GameTest
    public void spectatorVoidDeathNoLockout(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Spectator", GameType.SPECTATOR, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 2));
            GraveDeathGameTests.respawnBesideTest(helper, player);
            BlockPos column = helper.absolutePos(new BlockPos(1, 1, 1));
            player.snapTo(column.getX() + 0.5, helper.getLevel().getMinY() - 70, column.getZ() + 0.5);
            TestPlayers.markLoaded(player);
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            helper.assertTrue(player.isDeadOrDying(), "the spectator did not die in the void");
            helper.assertTrue(DeathLockout.newestDeath(uuid) == null, "a spectator's death was recorded");
            helper.assertTrue(DeathLockout.refusal(uuid, "Test") == null, "a spectator's death locked them out");
            helper.assertTrue(BodyTestKit.gravesOf(helper, uuid).isEmpty(), "a spectator's death made a grave");

            ServerPlayer back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(back.getInventory().countItem(Items.DIAMOND), 2, "diamonds after the respawn");
        } finally {
            leaveIfOnline(player);
            BodyTestKit.removeGraves(helper, uuid);
        }
        helper.succeed();
    }

    // With the switch on, a creative death counts like any other: the items go into the grave and the lockout starts.
    // The switch goes back in the same tick, and the player leaves before the kick comes.
    @GameTest
    public void countCreativeDeathsSwitchOn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Counted", GameType.CREATIVE, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        boolean countBefore = lockout.countCreativeAndSpectatorDeaths;
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 2));
            TestPlayers.markLoaded(player);
            lockout.countCreativeAndSpectatorDeaths = true;
            try {
                player.kill(player.level());
            } finally {
                lockout.countCreativeAndSpectatorDeaths = countBefore;
            }
            helper.assertTrue(player.isDeadOrDying(), "the creative player did not die");
            helper.assertValueEqual(GraveAsserts.countInGraves(server, uuid, Items.DIAMOND), 2, "diamonds in the graves");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 0, "diamonds left on the player");
            helper.assertTrue(refusedForLockout(DeathLockout.refusal(uuid, "Test")), "the counted death did not lock the player out");
        } finally {
            leaveIfOnline(player);
            BodyTestKit.removeGraves(helper, uuid);
        }
        helper.succeed();
    }

    // /yolt kill of a player who logged out in creative: dead in the save with the items and XP still there, no grave and
    // no record, and the reply says why. Their rejoin loads that save, and the respawn hands the items, XP and score back.
    @GameTest
    public void offlineCreativeKillKeepsItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "AwayCreative", GameType.CREATIVE, new BlockPos(1, 1, 1));
        GameProfile profile = player.getGameProfile();
        UUID uuid = player.getUUID();
        String name = profile.name();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 4));
            player.giveExperienceLevels(8);
            player.setScore(3);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            // Saved with the diamonds, the XP and the respawn point
            TestPlayers.leave(player);
            helper.assertValueEqual(SkinGameTests.console(server, "yolt kill " + name),
                    List.of("Killed " + name + ". They were in creative or spectator mode, so the death doesn't count."), "command output");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertValueEqual(GraveAsserts.count(OfflinePlayerData.takeInventory(saved, server.registryAccess(), name), Items.DIAMOND), 4,
                    "diamonds in the save");
            helper.assertTrue(BodyTestKit.gravesOf(helper, uuid).isEmpty(), "the kill made a grave");
            helper.assertTrue(DeathLockout.newestDeath(uuid) == null, "the kill was recorded");

            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.CREATIVE, new BlockPos(1, 1, 1));
            helper.assertTrue(back.isDeadOrDying(), "the player did not come back dead");
            ServerPlayer respawned = server.getPlayerList().respawn(back, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(respawned.getInventory().countItem(Items.DIAMOND), 4, "diamonds after the respawn");
            helper.assertValueEqual(respawned.experienceLevel, 8, "XP level after the respawn");
            helper.assertValueEqual(respawned.getScore(), 3, "score after the respawn");
        } finally {
            leaveIfOnline(player);
            BodyTestKit.removeGraves(helper, uuid);
        }
        helper.succeed();
    }

    // A mark this death didn't make, from /tag say, hands nothing back: a death that counts takes it off, so the respawn
    // gives back no XP or score, while the death drops its XP as usual. All in one tick, and the player leaves before
    // the kick comes.
    @GameTest
    public void countedDeathDropsStrayMark(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Marked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        BlockPos at = player.blockPosition();
        try {
            player.giveExperienceLevels(10);
            player.setScore(5);
            player.addTag(UncountedDeaths.MARK);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            TestPlayers.markLoaded(player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            helper.assertFalse(player.entityTags().contains(UncountedDeaths.MARK), "the death that counts left the mark on");
            helper.assertTrue(orbsNear(helper, at) > 0, "the death that counts dropped no XP");

            ServerPlayer back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(back.experienceLevel, 0, "XP level after the respawn");
            helper.assertValueEqual(back.getScore(), 0, "score after the respawn");
        } finally {
            leaveIfOnline(player);
            BodyTestKit.removeGraves(helper, uuid);
            discardOrbsNear(helper, at);
        }
        helper.succeed();
    }

    // The same away: /yolt kill of a saved player who carries a stray mark takes it off the save, so the respawn after
    // their rejoin gives back no score. All in one tick, and the player leaves before the kick comes.
    @GameTest
    public void offlineCountedKillDropsStrayMark(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "AwayMarked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        GameProfile profile = player.getGameProfile();
        UUID uuid = player.getUUID();
        String name = profile.name();
        BlockPos at = player.blockPosition();
        try {
            player.setScore(3);
            player.addTag(UncountedDeaths.MARK);
            GraveDeathGameTests.respawnBesideTest(helper, player);
            TestPlayers.leave(player);
            helper.assertValueEqual(SkinGameTests.console(server, "yolt kill " + name), List.of("Killed " + name + "."), "command output");
            CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
            helper.assertFalse(saved.read("Tags", Codec.STRING.listOf()).orElse(List.of()).contains(UncountedDeaths.MARK),
                    "the save kept the mark");

            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertTrue(back.isDeadOrDying(), "the player did not come back dead");
            ServerPlayer respawned = server.getPlayerList().respawn(back, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(respawned.getScore(), 0, "score after the respawn");
        } finally {
            leaveIfOnline(player);
            BodyTestKit.removeGraves(helper, uuid);
            discardOrbsNear(helper, at);
        }
        helper.succeed();
    }

    // One hit of the void's, as a player below the world takes it each tick; no cooldown left from the hit before
    private static void hitByVoid(ServerLevel level, ServerPlayer player) {
        player.damageCooldownTime = 0;
        player.hurtServer(level, level.damageSources().fellOutOfWorld(), 4.0F);
    }

    // A death store of the test's own, swapped in until it is closed, so the rows it holds are the test's. Within one
    // tick, since a death another test records meanwhile would land in it.
    static final class OwnStore implements AutoCloseable {
        private final Path dir;
        private final Path file;
        private final DeathStore store;
        private final DeathStore real;

        OwnStore() throws IOException {
            dir = Files.createTempDirectory("yolt-own-deaths");
            file = dir.resolve("deaths.db");
            store = DeathStore.open(file);
            real = DeathLockout.swapStoreForTesting(store);
        }

        DeathStore store() {
            return store;
        }

        String url() {
            return "jdbc:sqlite:" + file.toAbsolutePath();
        }

        // The player's rows, oldest first, as "message | days"
        List<String> rows(UUID uuid) throws SQLException {
            List<String> rows = new ArrayList<>();
            try (Connection connection = DriverManager.getConnection(url());
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT death_message, cooldown_days FROM death_logs WHERE uuid = ? ORDER BY ROWID")) {
                query.setString(1, uuid.toString());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        rows.add(result.getString(1) + " | " + result.getInt(2));
                    }
                }
            }
            return rows;
        }

        // Closing a store twice does nothing, so a test may close it early to make every query fail
        @Override
        public void close() throws IOException {
            DeathLockout.swapStoreForTesting(real);
            store.close();
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // XP orbs near an absolute position
    private static int orbsNear(GameTestHelper helper, BlockPos absolutePos) {
        return helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(absolutePos).inflate(3.0), Entity::isAlive).size();
    }

    // A death that counts drops its XP, which would sit in another test's way
    private static void discardOrbsNear(GameTestHelper helper, BlockPos absolutePos) {
        helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(absolutePos).inflate(3.0), Entity::isAlive)
                .forEach(Entity::discard);
    }

    // Graves are off for the death and back on in the same tick: these players carry nothing, and the tests are
    // about the kick
    private static void killWithoutGrave(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.markLoaded(player);
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean before = graves.enabled;
        graves.enabled = false;
        try {
            player.kill(player.level());
        } finally {
            graves.enabled = before;
        }
        helper.assertTrue(player.isDeadOrDying(), "the player did not die");
    }

    // A server running behind still runs queued tasks within 4 ticks. The death row must be there with no time left,
    // so the player stayed because of it, not because nothing was recorded.
    private static void assertStillOnlineLater(GameTestHelper helper, ServerPlayer player) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.runAfterDelay(5, () -> {
            try {
                helper.assertTrue(server.getPlayerList().getPlayer(player.getUUID()) == player, "the dead player was kicked");
                helper.assertValueEqual(DeathLockout.revive(player.getUUID(), "Test", "console"), DeathLockout.ReviveResult.ALREADY_ENDED,
                        "revive of the death row");
            } finally {
                leaveIfOnline(player);
            }
            helper.succeed();
        });
    }

    private static void leaveIfOnline(ServerPlayer player) {
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
        if (online != null) {
            TestPlayers.leave(online);
        }
    }

    static String disconnectReason(ServerPlayer player) {
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundDisconnectPacket disconnect) {
                return disconnect.reason().getString();
            }
        }
        return "(no disconnect packet)";
    }

    private static boolean canLogin(GameTestHelper helper, UUID uuid) {
        return loginRefusal(helper.getLevel().getServer(), new NameAndId(uuid, "Ghost")) == null;
    }

    static Component loginRefusal(MinecraftServer server, NameAndId who) {
        return server.getPlayerList().canPlayerLogin(ADDRESS, who);
    }

    // Our refusal, not vanilla's (server full, banned, whitelist)
    static boolean refusedForLockout(Component refusal) {
        return refusal != null && refusal.getString().startsWith("You died");
    }

    // What a locked-out player is told, or "" when they may join
    static String refusalText(UUID uuid) {
        Component refusal = DeathLockout.refusal(uuid, "Test");
        return refusal == null ? "" : refusal.getString();
    }
}
