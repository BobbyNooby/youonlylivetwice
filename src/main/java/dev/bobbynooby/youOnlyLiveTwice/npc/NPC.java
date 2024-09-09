package dev.bobbynooby.youOnlyLiveTwice.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mysql.cj.xdevapi.Client;
import dev.bobbynooby.youOnlyLiveTwice.utils.DummyServerGamePacketListenerImpl;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Pose;
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class NPC {

    Player player;


    public NPC(Player player) {
        this.player = player;
    }

    public void spawnNPC(double x, double y, double z) {
        CraftPlayer craftPlayer = (CraftPlayer) player;
        ServerPlayer serverPlayer = craftPlayer.getHandle();

        GameProfile gameProfile = new GameProfile(UUID.randomUUID(), player.getDisplayName());

        try {
            Property textures = (Property) craftPlayer.getProfile().getProperties().get("textures").toArray()[0];
            gameProfile.getProperties().put("textures", new Property("textures", textures.value(), textures.signature()));

        } catch (Exception e) {
            PluginPrint.println("Failed to set NPC Skin :" + e.getMessage());
        }


        MinecraftServer server = serverPlayer.getServer();
        ServerLevel serverLevel = serverPlayer.serverLevel();

        ServerPlayer npc = new ServerPlayer(server, serverLevel, gameProfile, ClientInformation.createDefault());
        npc.connection = new DummyServerGamePacketListenerImpl(server, npc);
        npc.setPos(x, y, z);
        npc.setPose(Pose.SWIMMING);

        SynchedEntityData synchedEntityData = npc.getEntityData();
        EntityDataAccessor<Byte> accessor = new EntityDataAccessor<>(17, EntityDataSerializers.BYTE);
        synchedEntityData.set(accessor, (byte) 127);


        ServerEntity npcServerEntity = new ServerEntity(npc.serverLevel(), npc, 0, false, packet -> {
        }, Set.of());
        

        ServerGamePacketListenerImpl playerPacketListener = serverPlayer.connection;

        ClientboundPlayerInfoUpdatePacket playerInfoPacket = new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, npc);
        ClientboundAddEntityPacket addEntityPacket = new ClientboundAddEntityPacket(npc, npcServerEntity);
        ClientboundSetEntityDataPacket entityMetadataPacket = new ClientboundSetEntityDataPacket(npc.getId(), synchedEntityData.getNonDefaultValues());

        playerPacketListener.send(playerInfoPacket);
        playerPacketListener.send(addEntityPacket);
        playerPacketListener.send(entityMetadataPacket);
    }

}
