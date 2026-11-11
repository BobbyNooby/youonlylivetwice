package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInfo;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.LegacyQueryHandler;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.rcon.thread.QueryThreadGs4;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServerListGameTests {
    // The switch is flipped and restored inside one tick, so no other test sees it
    @GameTest
    public void statusHidesPlayerCount(GameTestHelper helper) {
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of(new NameAndId(UUID.randomUUID(), "Listed")));
        boolean before = Settings.get().hide.serverPlayerCount;
        List<String> lines = Settings.get().serverList.hoverLines;
        try {
            Settings.get().serverList.hoverLines = List.of();
            Settings.get().hide.serverPlayerCount = true;
            helper.assertValueEqual(players.max(), 1, "max players with the count hidden");
            helper.assertValueEqual(players.online(), 0, "online players with the count hidden");
            helper.assertTrue(players.sample().isEmpty(), "player sample is not empty with the count hidden");

            Settings.get().hide.serverPlayerCount = false;
            helper.assertValueEqual(players.max(), 20, "max players with the count shown");
            helper.assertValueEqual(players.online(), 5, "online players with the count shown");
            helper.assertValueEqual(players.sample().size(), 1, "player sample size with the count shown");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            Settings.get().serverList.hoverLines = lines;
        }
        helper.succeed();
    }

    // The hover lines replace the sample whatever the count switch says
    @GameTest
    public void statusSampleShowsHoverLines(GameTestHelper helper) {
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of(new NameAndId(UUID.randomUUID(), "Listed")));
        boolean before = Settings.get().hide.serverPlayerCount;
        List<String> lines = Settings.get().serverList.hoverLines;
        try {
            Settings.get().serverList.hoverLines = List.of("First line, with spaces", "\u00a7cSecond line");
            for (boolean hidden : new boolean[]{true, false}) {
                Settings.get().hide.serverPlayerCount = hidden;
                helper.assertValueEqual(players.sample().stream().map(NameAndId::name).toList(),
                        List.of("First line, with spaces", "\u00a7cSecond line"), "sample names, count hidden " + hidden);
                // Each ping reads the sample again, so the fake ids must not change between reads
                helper.assertValueEqual(players.sample().stream().map(NameAndId::id).toList(),
                        players.sample().stream().map(NameAndId::id).toList(), "sample ids from one ping to the next, count hidden " + hidden);
            }
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            Settings.get().serverList.hoverLines = lines;
        }
        helper.succeed();
    }

    @GameTest
    public void emptyHoverLinesKeepsVanilla(GameTestHelper helper) {
        NameAndId listed = new NameAndId(UUID.randomUUID(), "Listed");
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of(listed));
        boolean before = Settings.get().hide.serverPlayerCount;
        List<String> lines = Settings.get().serverList.hoverLines;
        try {
            Settings.get().serverList.hoverLines = List.of();
            Settings.get().hide.serverPlayerCount = true;
            helper.assertTrue(players.sample().isEmpty(), "sample is not empty with the count hidden and no lines");
            Settings.get().hide.serverPlayerCount = false;
            helper.assertValueEqual(players.sample(), List.of(listed), "sample with the count shown and no lines");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            Settings.get().serverList.hoverLines = lines;
        }
        helper.succeed();
    }

    @GameTest
    public void hoverLinesHideRealNames(GameTestHelper helper) {
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of(new NameAndId(UUID.randomUUID(), "RealPlayer")));
        boolean before = Settings.get().hide.serverPlayerCount;
        List<String> lines = Settings.get().serverList.hoverLines;
        try {
            Settings.get().serverList.hoverLines = List.of("Nobody here");
            Settings.get().hide.serverPlayerCount = false;
            helper.assertValueEqual(players.online(), 5, "the real count is still shown");
            helper.assertTrue(players.sample().stream().noneMatch(n -> n.name().equals("RealPlayer")),
                    "a real name is in the sample");
            helper.assertValueEqual(players.sample().size(), 1, "sample size");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            Settings.get().serverList.hoverLines = lines;
        }
        helper.succeed();
    }

    // serverList.shownMaxPlayers is the slot count every reply shows while the count is hidden: the status, the pre-1.7
    // ping and the query. Flipped and restored inside one tick.
    @GameTest
    public void shownMaxPlayersSetsTheSlotCount(GameTestHelper helper) throws ReflectiveOperationException {
        MinecraftServer server = helper.getLevel().getServer();
        Method version1 = LegacyQueryHandler.class.getDeclaredMethod("createVersion1Response", ServerInfo.class);
        version1.setAccessible(true);
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of());
        boolean before = Settings.get().hide.serverPlayerCount;
        int maxBefore = Settings.get().serverList.shownMaxPlayers;
        try {
            Settings.get().hide.serverPlayerCount = true;
            Settings.get().serverList.shownMaxPlayers = 50;
            helper.assertValueEqual(players.max(), 50, "status max players");
            helper.assertValueEqual(players.online(), 0, "status online players");
            String legacy = (String) version1.invoke(null, server);
            helper.assertTrue(legacy.endsWith("\u00000\u000050"), "legacy ping counts: " + legacy);
            String query = queryRules(server, "nobody");
            helper.assertTrue(query.contains("numplayers\u00000\u0000maxplayers\u000050\u0000"), "query counts: " + query);

            // With the count shown, the real numbers go out whatever the key says
            Settings.get().hide.serverPlayerCount = false;
            helper.assertValueEqual(players.max(), 20, "status max players with the count shown");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            Settings.get().serverList.shownMaxPlayers = maxBefore;
        }
        helper.succeed();
    }

    // The pre-1.7 ping builds its reply from the server, not from the status record. The switch is flipped and
    // restored inside one tick, and the joined player leaves in the same tick.
    @GameTest
    public void legacyPingHidesCount(GameTestHelper helper) throws ReflectiveOperationException {
        MinecraftServer server = helper.getLevel().getServer();
        Method version1 = LegacyQueryHandler.class.getDeclaredMethod("createVersion1Response", ServerInfo.class);
        version1.setAccessible(true);
        Method version0 = LegacyQueryHandler.class.getDeclaredMethod("createVersion0Response", ServerInfo.class);
        version0.setAccessible(true);
        ServerPlayer player = TestPlayers.join(helper, "Pinged", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().hide.serverPlayerCount;
        try {
            Settings.get().hide.serverPlayerCount = true;
            helper.assertTrue(((String) version1.invoke(null, server)).endsWith("\u00000\u00001"),
                    "legacy ping showed the real counts with the count hidden");
            helper.assertTrue(((String) version0.invoke(null, server)).endsWith("\u00a70\u00a71"),
                    "beta ping showed the real counts with the count hidden");

            Settings.get().hide.serverPlayerCount = false;
            String shown = (String) version1.invoke(null, server);
            helper.assertTrue(shown.endsWith("\u0000" + server.getPlayerCount() + "\u0000" + server.getMaxPlayers()),
                    "legacy ping did not show the real counts with the count shown: " + shown);
            String shownBeta = (String) version0.invoke(null, server);
            helper.assertTrue(shownBeta.endsWith("\u00a7" + server.getPlayerCount() + "\u00a7" + server.getMaxPlayers()),
                    "beta ping did not show the real counts with the count shown: " + shownBeta);
            helper.assertTrue(server.getPlayerCount() > 0, "no player was online");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // enable-query lists every online name and the real counts. The reply is built by reflection with a stand-in
    // server, since the query thread itself needs a socket.
    @GameTest
    public void queryHidesNames(GameTestHelper helper) throws ReflectiveOperationException, SocketException {
        ServerPlayer player = TestPlayers.join(helper, "Queried", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.getName().getString();
        boolean before = Settings.get().hide.serverPlayerCount;
        try {
            Settings.get().hide.serverPlayerCount = true;
            String hidden = queryRules(helper.getLevel().getServer(), name);
            helper.assertTrue(!hidden.contains(name), "query listed a name with the count hidden");
            helper.assertTrue(hidden.contains("numplayers\u00000\u0000maxplayers\u00001\u0000"),
                    "query showed the real counts with the count hidden: " + hidden);
            String hiddenStat = queryBasicStat(helper.getLevel().getServer());
            helper.assertTrue(hiddenStat.contains("\u00000\u00001\u0000"),
                    "basic stat showed the real counts with the count hidden: " + hiddenStat);

            Settings.get().hide.serverPlayerCount = false;
            String shown = queryRules(helper.getLevel().getServer(), name);
            helper.assertTrue(shown.contains(name), "query did not list the player with the count shown");
            MinecraftServer live = helper.getLevel().getServer();
            String shownStat = queryBasicStat(live);
            helper.assertTrue(shownStat.contains("\u0000" + live.getPlayerCount() + "\u0000" + live.getMaxPlayers() + "\u0000"),
                    "basic stat did not show the real counts with the count shown: " + shownStat);
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    /** A query thread over a stand-in server, with a challenge seeded for the sender; the thread itself needs a socket. */
    private record Query(QueryThreadGs4 thread, DatagramPacket packet) {}

    private static Query newQuery(MinecraftServer server) throws ReflectiveOperationException {
        ServerInterface stand = (ServerInterface) Proxy.newProxyInstance(ServerListGameTests.class.getClassLoader(),
                new Class<?>[]{ServerInterface.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayerCount" -> server.getPlayerCount();
                    case "getMaxPlayers" -> server.getMaxPlayers();
                    case "getPlayerNames" -> server.getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toArray(String[]::new);
                    case "getServerPort" -> 25565;
                    case "getServerIp", "getServerName", "getLevelIdName", "getServerVersion", "getPluginNames" -> "";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Constructor<QueryThreadGs4> make = QueryThreadGs4.class.getDeclaredConstructor(ServerInterface.class, int.class);
        make.setAccessible(true);
        QueryThreadGs4 query = make.newInstance(stand, 25566);

        // The reply is keyed to a challenge the sender asked for earlier
        byte[] request = new byte[16];
        InetSocketAddress from = new InetSocketAddress("127.0.0.1", 40000);
        DatagramPacket packet = new DatagramPacket(request, request.length, from);
        Class<?> challengeType = Class.forName(QueryThreadGs4.class.getName() + "$RequestChallenge");
        Constructor<?> challenge = challengeType.getDeclaredConstructor(DatagramPacket.class);
        challenge.setAccessible(true);
        Field challenges = QueryThreadGs4.class.getDeclaredField("validChallenges");
        challenges.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<SocketAddress, Object> valid = (Map<SocketAddress, Object>) challenges.get(query);
        valid.put(from, challenge.newInstance(packet));
        return new Query(query, packet);
    }

    private static String queryRules(MinecraftServer server, String name) throws ReflectiveOperationException {
        Query query = newQuery(server);
        Method rules = QueryThreadGs4.class.getDeclaredMethod("buildRuleResponse", DatagramPacket.class);
        rules.setAccessible(true);
        return new String((byte[]) rules.invoke(query.thread(), query.packet()), StandardCharsets.UTF_8);
    }

    // The basic stat reply is written straight from processPacket to the socket, so a socket that keeps what it is
    // sent stands in for the real one
    private static String queryBasicStat(MinecraftServer server) throws ReflectiveOperationException, SocketException {
        Query query = newQuery(server);
        Class<?> challengeType = Class.forName(QueryThreadGs4.class.getName() + "$RequestChallenge");
        Field challengeField = challengeType.getDeclaredField("challenge");
        challengeField.setAccessible(true);
        Field challenges = QueryThreadGs4.class.getDeclaredField("validChallenges");
        challenges.setAccessible(true);
        Object seeded = ((Map<?, ?>) challenges.get(query.thread())).values().iterator().next();
        int value = challengeField.getInt(seeded);

        // 0xFE 0xFD, type 0 (stat), 4 ident bytes, the 4 challenge bytes: 11 bytes is a basic stat request
        byte[] request = new byte[]{-2, -3, 0, 0, 0, 0, 0, (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
        DatagramPacket packet = new DatagramPacket(request, request.length, query.packet().getSocketAddress());
        List<byte[]> sent = new ArrayList<>();
        DatagramSocket socket = new DatagramSocket((SocketAddress) null) {
            @Override
            public void send(DatagramPacket p) {
                sent.add(java.util.Arrays.copyOf(p.getData(), p.getLength()));
            }
        };
        try {
            Field socketField = QueryThreadGs4.class.getDeclaredField("socket");
            socketField.setAccessible(true);
            socketField.set(query.thread(), socket);
            Method process = QueryThreadGs4.class.getDeclaredMethod("processPacket", DatagramPacket.class);
            process.setAccessible(true);
            process.invoke(query.thread(), packet);
        } finally {
            socket.close();
        }
        if (sent.size() != 1) {
            throw new IllegalStateException("basic stat sent " + sent.size() + " replies");
        }
        return new String(sent.get(0), StandardCharsets.ISO_8859_1);
    }
}
