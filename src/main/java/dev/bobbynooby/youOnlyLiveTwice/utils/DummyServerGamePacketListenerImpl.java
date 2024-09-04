package dev.bobbynooby.youOnlyLiveTwice.utils;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

public class DummyServerGamePacketListenerImpl extends ServerGamePacketListenerImpl {

    public DummyServerGamePacketListenerImpl(MinecraftServer server, ServerPlayer player) {
        super(server, new DummyConnection(PacketFlow.CLIENTBOUND), player, CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "Skibidee"), true));

    }

    @Override
    public void send(Packet<?> packet) {

    }

    @Override
    public void sendPacket(Packet<?> packet) {

    }
}
