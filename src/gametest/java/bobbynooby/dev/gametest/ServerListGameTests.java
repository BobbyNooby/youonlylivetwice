package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInfo;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.LegacyQueryHandler;
import net.minecraft.server.rcon.thread.QueryThreadGs4;
import net.minecraft.world.level.GameType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.players.NameAndId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServerListGameTests {
    // The switch is flipped and restored inside one tick, so no other test sees it
    @GameTest
    public void statusHidesPlayerCount(GameTestHelper helper) {
        ServerStatus.Players players = new ServerStatus.Players(20, 5, List.of(new NameAndId(UUID.randomUUID(), "Listed")));
        boolean before = Settings.get().hide.serverPlayerCount;
        try {
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
        ServerPlayer player = TestPlayers.join(helper, "Pinged", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().hide.serverPlayerCount;
        try {
            Settings.get().hide.serverPlayerCount = true;
            helper.assertTrue(((String) version1.invoke(null, server)).endsWith("\u00000\u00001"),
                    "legacy ping showed the real counts with the count hidden");

            Settings.get().hide.serverPlayerCount = false;
            String shown = (String) version1.invoke(null, server);
            helper.assertTrue(shown.endsWith("\u0000" + server.getPlayerCount() + "\u0000" + server.getMaxPlayers()),
                    "legacy ping did not show the real counts with the count shown: " + shown);
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
    public void queryHidesNames(GameTestHelper helper) throws ReflectiveOperationException {
        ServerPlayer player = TestPlayers.join(helper, "Queried", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.getName().getString();
        boolean before = Settings.get().hide.serverPlayerCount;
        try {
            Settings.get().hide.serverPlayerCount = true;
            String hidden = queryRules(helper.getLevel().getServer(), name);
            helper.assertTrue(!hidden.contains(name), "query listed a name with the count hidden");
            helper.assertTrue(hidden.contains("numplayers\u00000\u0000maxplayers\u00001\u0000"),
                    "query showed the real counts with the count hidden: " + hidden);

            Settings.get().hide.serverPlayerCount = false;
            String shown = queryRules(helper.getLevel().getServer(), name);
            helper.assertTrue(shown.contains(name), "query did not list the player with the count shown");
        } finally {
            Settings.get().hide.serverPlayerCount = before;
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static String queryRules(MinecraftServer server, String name) throws ReflectiveOperationException {
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

        Method rules = QueryThreadGs4.class.getDeclaredMethod("buildRuleResponse", DatagramPacket.class);
        rules.setAccessible(true);
        return new String((byte[]) rules.invoke(query, packet), StandardCharsets.UTF_8);
    }
}
