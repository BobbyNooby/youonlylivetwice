package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathStore;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

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

    // A closed store makes every query throw, like a failing database would
    @GameTest
    public void storeFailureLetsPlayerIn(GameTestHelper helper) throws java.io.IOException {
        UUID uuid = UUID.randomUUID();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("yolt-broken-store");
        DeathStore broken = DeathStore.open(dir.resolve("broken.db"));
        broken.close();
        DeathStore real = DeathLockout.swapStoreForTesting(broken);
        try {
            helper.assertTrue(DeathLockout.refusal(uuid, "Test") == null, "player refused while the store is failing");
            DeathLockout.recordDeath(uuid, "Ghost");
            helper.assertValueEqual(DeathLockout.revive(uuid, "Test", "console"), DeathLockout.ReviveResult.FAILED, "revive result on a failing store");
        } finally {
            DeathLockout.swapStoreForTesting(real);
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
        YoltConfig.BodiesSection before = Settings.get().bodies;
        // On until the kick's logout, so a body that logout wrongly left would show
        Settings.get().bodies = new YoltConfig.BodiesSection();
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
                    cleanUpJoiner(helper, uuid, before);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUpJoiner(helper, uuid, before);
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

    private static String disconnectReason(ServerPlayer player) {
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

    // The switches go back first, so a player still online leaves without making a body
    private static void cleanUpJoiner(GameTestHelper helper, UUID uuid, YoltConfig.BodiesSection before) {
        Settings.get().bodies = before;
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            TestPlayers.leave(online);
        }
        helper.getLevel().getEntities(EntityTypes.MANNEQUIN, body -> body.entityTags().contains(Bodies.TAG)
                && uuid.equals(body.getProfile().partialProfile().id())).forEach(Entity::discard);
        BodyStore.get(server).remove(uuid);
    }
}
