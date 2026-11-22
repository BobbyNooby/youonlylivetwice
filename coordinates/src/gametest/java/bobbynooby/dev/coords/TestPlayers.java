package bobbynooby.dev.coords;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test players that join through PlayerList.placeNewPlayer, as a client's login does, so a frame starts the way it does
 * for a real client. The far end of each one's connection is a channel the test holds: it keeps every packet the server
 * sent, and takes in packets as the player's client would send them.
 */
final class TestPlayers {
    // Tests share one server, so each name ends in a count no other player has
    private static final AtomicInteger COUNTER = new AtomicInteger();
    // The longest base that leaves room for a four-digit count within vanilla's 16 characters
    private static final int BASE_LIMIT = 12;
    private static final Map<UUID, EmbeddedChannel> CHANNELS = new ConcurrentHashMap<>();

    private TestPlayers() {}

    /** A player who joins at a spot of the test's, in the game mode asked for. */
    static ServerPlayer join(GameTestHelper helper, String base, GameType mode, BlockPos relativePos) {
        if (base.length() > BASE_LIMIT) {
            throw new IllegalArgumentException("The base " + base + " is longer than " + BASE_LIMIT);
        }
        String name = base + COUNTER.incrementAndGet();
        if (!StringUtil.isValidPlayerName(name)) {
            throw new IllegalStateException("No valid player name from the base " + base + ": " + name);
        }
        MinecraftServer server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        player.snapTo(helper.absolutePos(relativePos), 0.0F, 0.0F);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        CHANNELS.put(profile.id(), new EmbeddedChannel(connection));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // setGameMode sends a packet, so it waits for the connection placeNewPlayer sets up
        player.setGameMode(mode);
        return player;
    }

    /**
     * A respawn through the server's own, at a spot of the test's, so no spot is searched for. As vanilla's own callers
     * do, the new player goes on the connection right after.
     */
    static ServerPlayer respawn(GameTestHelper helper, ServerPlayer player, BlockPos relativePos) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(helper.getLevel().dimension(), helper.absolutePos(relativePos), 0.0F, 0.0F),
                true), false);
        ServerPlayer back = player.level().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        back.connection.player = back;
        back.connection.resetPosition();
        return back;
    }

    // Closing the fake client's end, as a real client's going does, ends Fabric's networking session for the connection
    static void leave(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
        EmbeddedChannel channel = CHANNELS.remove(player.getUUID());
        if (channel != null) {
            channel.close();
        }
    }

    // The test server gives ops level 0 unless told otherwise, below the level the commands need
    static void op(ServerPlayer player) {
        player.level().getServer().getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER),
                Optional.empty());
    }

    static void deop(ServerPlayer player) {
        player.level().getServer().getPlayerList().deop(player.nameAndId());
    }

    // Vanilla ignores a player's moves until their client says the world has loaded, which a test player never does
    static void markLoaded(ServerPlayer player) {
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /** Every packet the server has sent this player so far, oldest first, with a bundle's parts in their place after it. */
    static List<Packet<?>> sent(ServerPlayer player) {
        EmbeddedChannel channel = CHANNELS.get(player.getUUID());
        // Sends during a tick wait in the channel until the end of the tick
        channel.flushOutbound();
        List<Packet<?>> packets = new ArrayList<>();
        for (Object message : channel.outboundMessages()) {
            if (message instanceof Packet<?> packet) {
                packets.add(packet);
                if (packet instanceof ClientboundBundlePacket bundle) {
                    bundle.subPackets().forEach(packets::add);
                }
            }
        }
        return packets;
    }

    /** The last packet of the type sent from packet "from" on. */
    static <T> T last(GameTestHelper helper, ServerPlayer player, int from, Class<T> type) {
        List<Packet<?>> packets = sent(player);
        for (int i = packets.size() - 1; i >= from; i--) {
            if (type.isInstance(packets.get(i))) {
                return type.cast(packets.get(i));
            }
        }
        throw helper.assertionException("no " + type.getSimpleName() + " was sent");
    }

    /** Every packet of the type sent from packet "from" on, oldest first. */
    static <T> List<T> all(ServerPlayer player, int from, Class<T> type) {
        List<Packet<?>> packets = sent(player);
        return packets.subList(from, packets.size()).stream().filter(type::isInstance).map(type::cast).toList();
    }

    // A packet from the fake client: it goes in through the connection's own read, as a real client's does, and is
    // handled at once, as the test runs on the server thread
    static void receive(ServerPlayer player, Packet<?> packet) {
        CHANNELS.get(player.getUUID()).writeInbound(packet);
    }
}
