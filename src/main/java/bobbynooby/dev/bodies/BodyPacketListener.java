package bobbynooby.dev.bodies;

import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.impl.networking.UntrackedPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A player body's connection. Vanilla reads its latency for the player-info entry and sends it packets, as a player
 * and as a viewer of what is around it; with no client they go nowhere. Fabric's own fake players use the untracked
 * marker, so Fabric's networking doesn't keep the listener for good or announce it as a new connection.
 * Lives outside PlayerBody, where Connection would mean the waypoint interface a player inherits.
 * <p>
 * The marker is internal to Fabric API (its impl package), so any Fabric API update may move or drop it. That is why
 * fabric.mod.json asks for at least the version this was built against; check the marker again on each update.
 */
final class BodyPacketListener extends ServerGamePacketListenerImpl implements UntrackedPacketListener {
    BodyPacketListener(ServerPlayer body) {
        super(body.level().getServer(), new NoConnection(), body, CommonListenerCookie.createInitial(body.getGameProfile(), false));
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
    }

    // Vanilla keeps a player unhurtable until its client says the world has loaded, which a body never does
    @Override
    public boolean hasClientLoaded() {
        return true;
    }

    // Never connected, so anything sent straight to it would otherwise queue up for good
    private static final class NoConnection extends Connection {
        private NoConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        }

        @Override
        public void runOnceConnected(Consumer<Connection> action) {
        }

        @Override
        public void flushChannel() {
        }
    }
}
