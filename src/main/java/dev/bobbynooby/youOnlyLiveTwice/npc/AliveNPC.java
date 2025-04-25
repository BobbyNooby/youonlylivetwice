package dev.bobbynooby.youOnlyLiveTwice.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.DummyServerGamePacketListenerImpl;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.trait.EntityPoseTrait;
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
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class AliveNPC {
    protected NPC npc;
    protected LocalDatabase db;
    protected YouOnlyLiveTwice plugin;

    public AliveNPC(Player player, LocalDatabase db, YouOnlyLiveTwice plugin) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, player.getDisplayName());

        npc.addTrait(new AliveNPCTrait(db, plugin));
        npc.addTrait(Equipment.class);
        npc.addTrait(EntityPoseTrait.class);

        npc.getTraitNullable(EntityPoseTrait.class).setPose(EntityPoseTrait.EntityPose.STANDING);

        npc.setProtected(false);
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, true);
        npc.data().set(NPC.Metadata.SPAWN_NODAMAGE_TICKS, 0);
        npc.data().set(NPC.Metadata.TARGETABLE, true);
        npc.data().set(NPC.Metadata.FLUID_PUSHABLE, true);
        npc.data().set(NPC.Metadata.COLLIDABLE, true);

        CitizensAPI.getNPCRegistry().saveToStore();
    }

    public UUID getNPCUUID() {
        return npc.getUniqueId();
    }

    public UUID getPlayerUUID() throws Exception {
        return db.getPlayerUUIDFromNPCUUID(npc.getUniqueId());
    }


}
