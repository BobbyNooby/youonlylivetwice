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
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // A returning player: like a real login, their save is read before they are placed in the world, and the pearls
    // and vehicle it holds come back once they are (PrepareSpawnTask)
    public static ServerPlayer rejoin(GameTestHelper helper, GameProfile profile, GameType mode, BlockPos relativePos) {
        MinecraftServer server = helper.getLevel().getServer();
        Optional<ValueInput> saved = server.getPlayerList().loadPlayerData(new NameAndId(profile.id(), profile.name()))
                .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
        ServerPlayer player = join(helper.getLevel(), profile, mode, helper.absolutePos(relativePos), (level, cookie) -> {
            ServerPlayer loaded = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
            saved.ifPresent(loaded::load);
            return loaded;
        });
        saved.ifPresent(input -> {
            player.loadAndSpawnEnderPearls(input);
            player.loadAndSpawnParentVehicle(input);
        });
        return player;
    }

    public static Recording joinRecording(GameTestHelper helper, String name, GameType mode, BlockPos relativePos) {
        return join(helper.getLevel(), uniqueProfile(name), mode, helper.absolutePos(relativePos), Recording::new);
    }

    public static Recording joinRecording(MinecraftServer server, String name, GameType mode, BlockPos absolutePos) {
        return join(server.overworld(), uniqueProfile(name), mode, absolutePos, Recording::new);
    }

    // Vanilla makes a player invulnerable until their client says the world has loaded, which a test player never does
    public static void markLoaded(ServerPlayer player) {
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    // The test server gives ops level 0 unless told otherwise, below the level /yolt and admin view need
    public static void op(ServerPlayer player) {
        op(player, LevelBasedPermissionSet.GAMEMASTER);
    }

    public static void op(ServerPlayer player, LevelBasedPermissionSet level) {
        player.level().getServer().getPlayerList().op(player.nameAndId(), Optional.of(level), Optional.empty());
    }

    public static void deop(ServerPlayer player) {
        player.level().getServer().getPlayerList().deop(player.nameAndId());
    }

    public static void leave(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
        CHANNELS.remove(player.getUUID());
    }

    // The part of a client's disconnect that changes the player, as ServerGamePacketListenerImpl.removePlayerFromWorld
    // runs it: a sleeper is woken, anyone riding gets off and the player counts as disconnected, all before the player
    // list lets them go. The "left the game" message, chat chain and text filter are left out.
    public static void disconnect(ServerPlayer player) {
        player.disconnect();
        leave(player);
    }

    // Every packet the server has sent this player so far, oldest first
    public static List<Packet<?>> sentPackets(ServerPlayer player) {
        EmbeddedChannel channel = CHANNELS.get(player.getUUID());
        // Sends during a tick wait in the channel until the end of the tick. A kicked player's channel is closed and
        // can't be flushed, but what it sent before, the disconnect included, is still there.
        if (channel.isOpen()) {
            channel.flushOutbound();
        }
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
