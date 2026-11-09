package bobbynooby.dev.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

// Fake players that join through PlayerList.placeNewPlayer, so join events and our mixins run like for a real client
public final class TestPlayers {
    // Tests share one server, so names get a suffix to stay unique
    private static final AtomicInteger COUNTER = new AtomicInteger();
    // The fake client end of each test player's connection, which keeps every packet the server sent
    private static final Map<UUID, EmbeddedChannel> CHANNELS = new ConcurrentHashMap<>();

    private TestPlayers() {
    }

    public static ServerPlayer join(GameTestHelper helper, String name, GameType mode, BlockPos relativePos) {
        return join(helper, uniqueProfile(name), mode, relativePos);
    }

    public static ServerPlayer join(GameTestHelper helper, GameProfile profile, GameType mode, BlockPos relativePos) {
        return join(helper.getLevel(), profile, mode, helper.absolutePos(relativePos));
    }

    public static ServerPlayer join(MinecraftServer server, String name, GameType mode, BlockPos absolutePos) {
        return join(server.overworld(), uniqueProfile(name), mode, absolutePos);
    }

    public static Recording joinRecording(GameTestHelper helper, String name, GameType mode, BlockPos relativePos) {
        return join(helper.getLevel(), uniqueProfile(name), mode, helper.absolutePos(relativePos), Recording::new);
    }

    // Vanilla makes a player invulnerable until their client says the world has loaded, which a test player never does
    public static void markLoaded(ServerPlayer player) {
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    public static void leave(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
        CHANNELS.remove(player.getUUID());
    }

    // Every packet the server has sent this player so far, oldest first
    public static List<Packet<?>> sentPackets(ServerPlayer player) {
        EmbeddedChannel channel = CHANNELS.get(player.getUUID());
        // Sends during a tick wait in the channel until the end of the tick
        channel.flushOutbound();
        List<Packet<?>> packets = new ArrayList<>();
        for (Object message : channel.outboundMessages()) {
            if (message instanceof Packet<?> packet) {
                packets.add(packet);
            }
        }
        return packets;
    }

    // What a real client does every tick that makes players start tracking each other:
    // it confirms chunk batches, so more chunks get sent, and its movement packets re-check who it can see
    public static void driveTracking(ServerPlayer player) {
        player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64.0F));
        player.level().getChunkSource().move(player);
    }

    private static GameProfile uniqueProfile(String name) {
        return new GameProfile(UUID.randomUUID(), name + COUNTER.incrementAndGet());
    }

    private static ServerPlayer join(ServerLevel level, GameProfile profile, GameType mode, BlockPos pos) {
        return join(level, profile, mode, pos,
                (l, cookie) -> new ServerPlayer(l.getServer(), l, cookie.gameProfile(), cookie.clientInformation()));
    }

    // Same steps as vanilla's makeMockServerPlayerInLevel, which always forces creative
    private static <P extends ServerPlayer> P join(ServerLevel level, GameProfile profile, GameType mode, BlockPos pos,
                                                   BiFunction<ServerLevel, CommonListenerCookie, P> create) {
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        P player = create.apply(level, cookie);
        player.snapTo(pos, 0.0F, 0.0F);

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        CHANNELS.put(profile.id(), new EmbeddedChannel(connection));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);

        // setGameMode sends a packet, so it has to wait for the connection placeNewPlayer sets up
        player.setGameMode(mode);
        return player;
    }

    // A test player that keeps every system message sent to it, since it has no client to show them
    public static final class Recording extends ServerPlayer {
        public final List<Component> messages = new ArrayList<>();

        private Recording(ServerLevel level, CommonListenerCookie cookie) {
            super(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            messages.add(message);
            super.sendSystemMessage(message, overlay);
        }
    }
}
