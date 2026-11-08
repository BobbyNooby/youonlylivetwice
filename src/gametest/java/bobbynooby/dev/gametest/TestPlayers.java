package bobbynooby.dev.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// Fake players that join through PlayerList.placeNewPlayer, so join events and our mixins run like for a real client
public final class TestPlayers {
    // Tests share one server, so names get a suffix to stay unique
    private static final AtomicInteger COUNTER = new AtomicInteger();

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

    // Vanilla makes a player invulnerable until their client says the world has loaded, which a test player never does
    public static void markLoaded(ServerPlayer player) {
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    public static void leave(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
    }

    private static GameProfile uniqueProfile(String name) {
        return new GameProfile(UUID.randomUUID(), name + COUNTER.incrementAndGet());
    }

    // Same steps as vanilla's makeMockServerPlayerInLevel, which always forces creative
    private static ServerPlayer join(ServerLevel level, GameProfile profile, GameType mode, BlockPos pos) {
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        player.snapTo(pos, 0.0F, 0.0F);

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);

        // setGameMode sends a packet, so it has to wait for the connection placeNewPlayer sets up
        player.setGameMode(mode);
        return player;
    }
}
