package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.PacketRecorder;
import bobbynooby.dev.gametest.TestPlayers;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.world.RandomSpawn;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.ChatLog;
import net.minecraft.client.multiplayer.chat.LoggedChatMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Who is online never reaches a real client through any packet. The client's own connection is recorded from its first
 * byte, and every frame is searched for the names and UUIDs of the players it mustn't know about, whatever packet,
 * field or encoding they would hide in, while those players do everything that tells vanilla clients about them:
 * <ul>
 *     <li>Hidden is online far off before the client joins, then chats, whispers to it, gets an advancement, changes
 *     game mode and dies</li>
 *     <li>Latecomer joins far off after the client, then leaves through a real disconnect</li>
 *     <li>Distant stays far off and does nothing, until admin view shows it, which proves the search finds a name that
 *     is sent</li>
 *     <li>Near stands next to the client, and has to be found, so the recording is known to work</li>
 * </ul>
 * The client's state is checked too: no entry, entity, team member or chat line for any of the hidden players.
 */
public class PresenceLeakClientTest implements FabricClientGameTest {
    private static final int NEAR_BLOCKS = 4;
    private static final int FAR_BLOCKS = 1_000;
    // How long the far players have to stay unknown once the near one is known
    private static final int HOLD_TICKS = 40;
    // After the last thing the hidden players do, so whatever it sent has time to arrive
    private static final int SETTLE_TICKS = 20;
    // An advancement vanilla announces to everyone
    private static final String ADVANCEMENT = "minecraft:story/mine_stone";

    private record Cast(ServerPlayer near, ServerPlayer hidden, ServerPlayer distant, GameProfile latecomer,
                        PacketRecorder.Session session) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!ClientTests.selected(getClass())) {
            return;
        }
        // The config outlives each test's server; a random spawn would put the client away from the players placed for it
        YoltConfig config = Settings.get();
        boolean randomSpawnWas = config.randomSpawn;
        config.randomSpawn = false;
        // Only touched on the server thread
        List<ServerPlayer> joined = new ArrayList<>();
        Cast[] cast = new Cast[1];
        try {
            // The test server has too few slots for three players and the client
            Properties properties = new Properties();
            properties.setProperty("max-players", "8");
            ClientTests.withServer(ctx, properties, server -> cast[0] = server.computeOnServer(s -> setUp(s, joined)), (server, connection) -> {
                Cast c = cast[0];
                boolean wasOp = server.computeOnServer(s -> s.getPlayerList().isOp(connection.getServerPlayer().nameAndId()));
                boolean whispersWere = server.computeOnServer(s -> Settings.get().chat.allowWhispers);
                try {
                    run(ctx, server, connection, c, joined);
                } finally {
                    server.runOnServer(s -> {
                        List<Runnable> steps = new ArrayList<>();
                        Settings.get().chat.allowWhispers = whispersWere;
                        ServerPlayer self = connection.getServerPlayer();
                        steps.add(() -> AdminView.set(self, false));
                        if (!wasOp) {
                            steps.add(() -> TestPlayers.deop(self));
                        }
                        for (ServerPlayer player : joined) {
                            steps.add(() -> ClientTests.leaveWithoutBody(s, player.getUUID()));
                        }
                        ClientTests.runAll(steps);
                    });
                }
            });
        } finally {
            PacketRecorder.disarm();
            config.randomSpawn = randomSpawnWas;
        }
    }

    // Before the client connects, so the players are online for its join and the recording starts with its first byte
    private static Cast setUp(MinecraftServer s, List<ServerPlayer> joined) {
        YoltConfig.HideSection hide = Settings.get().hide;
        if (!hide.distantPlayers || !hide.tabList || !hide.gameMessages) {
            throw new AssertionError("the test server doesn't hide distant players, the tab list and game messages, as YOLT ships");
        }
        ServerLevel overworld = s.overworld();
        BlockPos spawn = RandomSpawn.spotAt(overworld, 0, 0);
        if (spawn == null) {
            throw new AssertionError("nowhere to stand at 0, 0");
        }
        // The world is new for each test, so its spawn needs no setting back
        s.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spawn, 0.0F, 0.0F));
        ServerPlayer near = join(s, joined, "Near", spawn.east(NEAR_BLOCKS));
        ServerPlayer hidden = join(s, joined, "Hidden", spawn.east(FAR_BLOCKS));
        ServerPlayer distant = join(s, joined, "Distant", spawn.west(FAR_BLOCKS));
        // Latecomer joins only once the client is in, but its needles have to be in place from the start
        GameProfile latecomer = TestPlayers.profile("Latecomer");
        Map<String, byte[]> needles = new LinkedHashMap<>();
        for (ServerPlayer player : List.of(near, hidden, distant)) {
            needles.putAll(PacketRecorder.playerNeedles(player.getScoreboardName(), player.getUUID()));
        }
        needles.putAll(PacketRecorder.playerNeedles(latecomer.name(), latecomer.id()));
        return new Cast(near, hidden, distant, latecomer, PacketRecorder.arm(new PacketRecorder.Session(needles, null)));
    }

    private static void run(ClientGameTestContext ctx, TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                            Cast c, List<ServerPlayer> joined) {
        String selfName = server.computeOnServer(s -> connection.getServerPlayer().getScoreboardName());
        ClientTests.waitDriving(ctx, server, joined, mc -> mc.getConnection().getPlayerInfo(c.near().getUUID()) != null,
                mc -> "the client never got an entry for the near player");
        ClientTests.holdDriving(ctx, server, joined, HOLD_TICKS, mc -> knowsNone(mc, List.of(c.hidden(), c.distant())),
                mc -> "the client got an entry for a far player");
        int mark = ChatStoryClientTest.chatLogEnd(ctx);

        // Everything a vanilla client hears about a far player, one after the other
        server.runOnServer(s -> {
            Settings.get().chat.allowWhispers = true;
            ServerPlayer hidden = c.hidden();
            s.getPlayerList().broadcastChatMessage(PlayerChatMessage.unsigned(hidden.getUUID(), "hello from far away"), hidden,
                    ChatType.bind(ChatType.CHAT, hidden));
            s.getCommands().performPrefixedCommand(hidden.createCommandSourceStack(), "w " + selfName + " psst from far away");
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),
                    "advancement grant " + hidden.getScoreboardName() + " only " + ADVANCEMENT);
            hidden.setGameMode(GameType.CREATIVE);
            hidden.setGameMode(GameType.SURVIVAL);
        });
        // Its join sends everyone a "joined the game" line and a player info entry
        ServerPlayer latecomer = server.computeOnServer(s -> {
            ServerPlayer player = TestPlayers.join(s, c.latecomer(), GameType.SURVIVAL,
                    connection.getServerPlayer().blockPosition().south(FAR_BLOCKS));
            joined.add(player);
            return player;
        });
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            server.runOnServer(s -> joined.forEach(TestPlayers::driveTracking));
            ctx.waitTick();
        }
        server.runOnServer(s -> {
            // A real disconnect, as a client's going makes it: the "left the game" line and the player info removal.
            // Bodies are off for it, so the leave leaves none behind.
            YoltConfig.BodiesSection bodies = Settings.get().bodies;
            boolean bodiesWere = bodies.enabled;
            bodies.enabled = false;
            try {
                latecomer.connection.onDisconnect(new DisconnectionDetails(Component.literal("Disconnected")));
            } finally {
                bodies.enabled = bodiesWere;
            }
            // Last, since the lockout kicks it: the death message, its grave and the leave that follows
            c.hidden().kill(c.hidden().level());
        });
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            server.runOnServer(s -> joined.stream().filter(p -> !p.hasDisconnected()).forEach(TestPlayers::driveTracking));
            ctx.waitTick();
        }
        connection.waitForClientboundPackets();

        List<String> hiddenNames = List.of(c.hidden().getScoreboardName(), c.distant().getScoreboardName(),
                latecomer.getScoreboardName());
        checkNothingReceived(c, latecomer);
        checkClientKnowsNone(ctx, List.of(c.hidden().getUUID(), c.distant().getUUID(), latecomer.getUUID()), hiddenNames, mark);
        checkAdminViewIsFound(ctx, server, connection, c, joined);
    }

    private static void checkNothingReceived(Cast c, ServerPlayer latecomer) {
        PacketRecorder.Session session = c.session();
        if (session.connections() != 1 || !session.problems().isEmpty()) {
            throw new AssertionError("the recording didn't hook the client's one connection whole: " + session.summary());
        }
        String nearName = c.near().getScoreboardName() + " (name)";
        // The control: a name that is sent is found, so a name that isn't found wasn't sent
        if (session.hits(nearName) == 0) {
            throw new AssertionError("the near player's name was never found in what the client got: " + session.summary());
        }
        List<String> leaked = new ArrayList<>();
        for (ServerPlayer player : List.of(c.hidden(), c.distant(), latecomer)) {
            for (String needle : PacketRecorder.playerNeedles(player.getScoreboardName(), player.getUUID()).keySet()) {
                if (session.hits(needle) > 0) {
                    leaked.add(needle + " in " + session.hits(needle) + " frames");
                }
            }
        }
        if (!leaked.isEmpty()) {
            throw new AssertionError("the client was sent what it mustn't know: " + leaked + "; " + session.summary());
        }
    }

    // What the client holds: no entry, entity, team member or chat line for a hidden player
    private static void checkClientKnowsNone(ClientGameTestContext ctx, List<UUID> ids, List<String> names, int mark) {
        ctx.runOnClient(mc -> {
            // Itself and the near player, and nobody under any other id
            if (mc.getConnection().getOnlinePlayerIds().size() != 2 || mc.level.players().size() != 2) {
                throw new AssertionError("the client knows " + mc.getConnection().getOnlinePlayerIds().size() + " players and has "
                        + mc.level.players().size() + " in its world, not just itself and the near one");
            }
            for (UUID id : ids) {
                if (mc.getConnection().getPlayerInfo(id) != null) {
                    throw new AssertionError("the client has an entry for " + id);
                }
                if (mc.level.getEntity(id) != null) {
                    throw new AssertionError("the client has an entity for " + id);
                }
            }
            for (PlayerTeam team : mc.level.getScoreboard().getPlayerTeams()) {
                for (String name : names) {
                    if (team.getPlayers().contains(name)) {
                        throw new AssertionError("the client's team " + team.getName() + " lists " + name);
                    }
                }
            }
            ChatLog log = mc.getReportingContext().chatLog();
            for (int id = Math.max(mark + 1, log.start()); id <= log.end(); id++) {
                if (log.lookup(id) instanceof LoggedChatMessage message) {
                    String text = message.toContentComponent().getString();
                    for (String name : names) {
                        if (text.contains(name)) {
                            throw new AssertionError("the client's chat has \"" + text + "\"");
                        }
                    }
                }
            }
            if (!mc.getConnection().getListedOnlinePlayers().isEmpty()) {
                throw new AssertionError("the client lists players in the tab list");
            }
        });
    }

    // Admin view sends the far player that stayed online, and the same search finds it this time
    private static void checkAdminViewIsFound(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                              TestDedicatedServerConnection connection, Cast c, List<ServerPlayer> joined) {
        String distantName = c.distant().getScoreboardName() + " (name)";
        if (c.session().hits(distantName) != 0) {
            throw new AssertionError("the distant player's name was found before admin view: " + c.session().summary());
        }
        server.runOnServer(s -> TestPlayers.op(connection.getServerPlayer()));
        if (!server.computeOnServer(s -> AdminView.toggle(connection.getServerPlayer()))) {
            throw new AssertionError("admin view was already on, so the toggle turned it off");
        }
        UUID distant = c.distant().getUUID();
        ClientTests.waitDriving(ctx, server, joined.stream().filter(p -> !p.hasDisconnected()).toList(),
                mc -> mc.getConnection().getPlayerInfo(distant) != null, mc -> "admin view never gave the client the distant player");
        connection.waitForClientboundPackets();
        if (c.session().hits(distantName) == 0) {
            throw new AssertionError("admin view sent the distant player, but its name wasn't found in what the client got: "
                    + c.session().summary());
        }
        // The same advancement the hidden player got, which an admin does see: its line is a text sent as NBT, so the
        // search is known to find a name in that form too, and the advancement is known to announce itself
        int before = c.session().hits(distantName);
        int mark = ChatStoryClientTest.chatLogEnd(ctx);
        String name = c.distant().getScoreboardName();
        server.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),
                "advancement grant " + name + " only " + ADVANCEMENT));
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> ChatStoryClientTest.chatLogHas(mc, mark, name),
                mc -> "an admin's client never got the distant player's advancement line");
        if (c.session().hits(distantName) <= before) {
            throw new AssertionError("the advancement line reached the client, but its name wasn't found in the frames: "
                    + c.session().summary());
        }
    }

    private static boolean knowsNone(Minecraft mc, List<ServerPlayer> players) {
        return players.stream().allMatch(player -> mc.getConnection().getPlayerInfo(player.getUUID()) == null);
    }

    private static ServerPlayer join(MinecraftServer s, List<ServerPlayer> joined, String name, BlockPos at) {
        ServerPlayer player = TestPlayers.join(s, name, GameType.SURVIVAL, at);
        joined.add(player);
        return player;
    }
}
