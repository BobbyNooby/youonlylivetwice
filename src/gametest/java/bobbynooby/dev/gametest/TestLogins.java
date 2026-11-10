package bobbynooby.dev.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

// A login as vanilla's login listener runs it, from the profile check to telling the client it is in. Nothing on the
// server ticks this listener, so the test does, one login tick per call. The GameTest server holds one player, so a
// test that logs in runs where no other test's player is online.
final class TestLogins {
    private TestLogins() {
    }

    // The profile an offline server gives a joining name (ServerLoginPacketListenerImpl.handleHello)
    static GameProfile offline(String name) {
        return UUIDUtil.createOfflineProfile(name);
    }

    static Login start(MinecraftServer server, GameProfile profile) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        // The handlers vanilla puts its compression next to, so the login's compression step has them to find
        channel.pipeline().addFirst("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addFirst("prepender", new ChannelOutboundHandlerAdapter());
        ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(server, connection, false);
        try {
            // Where both of vanilla's paths end: the offline hello with its made-up profile, and the online check with
            // the profile Mojang gave
            Method verify = ServerLoginPacketListenerImpl.class.getDeclaredMethod("startClientVerification", GameProfile.class);
            verify.setAccessible(true);
            verify.invoke(listener, profile);
        } catch (ReflectiveOperationException e) {
            channel.close();
            throw new IllegalStateException("could not start the login", e);
        }
        return new Login(listener, channel);
    }

    static final class Login implements AutoCloseable {
        private final ServerLoginPacketListenerImpl listener;
        private final EmbeddedChannel channel;

        private Login(ServerLoginPacketListenerImpl listener, EmbeddedChannel channel) {
            this.listener = listener;
            this.channel = channel;
        }

        void tick() {
            listener.tick();
        }

        // The profile the client was told it has, once the login has gone through
        Optional<GameProfile> finished() {
            if (channel.isOpen()) {
                channel.flushOutbound();
            }
            for (Object message : channel.outboundMessages()) {
                if (message instanceof ClientboundLoginFinishedPacket packet) {
                    return Optional.of(packet.gameProfile());
                }
            }
            return Optional.empty();
        }

        // What vanilla makes the player from once the client acknowledges the login (handleLoginAcknowledgement)
        GameProfile profile() {
            try {
                Field field = ServerLoginPacketListenerImpl.class.getDeclaredField("authenticatedProfile");
                field.setAccessible(true);
                return (GameProfile) field.get(listener);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not read the login's profile", e);
            }
        }

        @Override
        public void close() {
            channel.close();
        }
    }
}
