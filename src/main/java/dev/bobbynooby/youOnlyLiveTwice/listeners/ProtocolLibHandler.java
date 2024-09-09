package dev.bobbynooby.youOnlyLiveTwice.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.InternalStructure;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;

import java.security.PublicKey;

public class ProtocolLibHandler {

    public static void start() {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        protocolManager.addPacketListener(new PacketAdapter(YouOnlyLiveTwice.getInstance(), PacketType.Play.Client.USE_ENTITY) {

            @Override
            public void onPacketReceiving(PacketEvent event) {

                PacketContainer packet = event.getPacket();

                PluginPrint.println(packet.getEnumEntityUseActions().read(0).getAction().name());
            }
        });


    }
}
