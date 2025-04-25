package dev.bobbynooby.youOnlyLiveTwice.npc;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.trait.EntityPoseTrait;
import net.citizensnpcs.util.Pose;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class RegistryHandler {

    private final LocalDatabase db;
    private final YouOnlyLiveTwice plugin;

    public RegistryHandler(LocalDatabase db, YouOnlyLiveTwice plugin) {
        this.db = db;
        this.plugin = plugin;
    }


    public void spawnNPC(UUID npcUUID, Location location, PlayerQuitEvent event) {
        Player player = event.getPlayer();
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUUID);

        npc.getTraitNullable(Inventory.class).setContents(player.getInventory().getContents());

        Equipment equipment = npc.getTraitNullable(Equipment.class);
        equipment.set(Equipment.EquipmentSlot.HELMET, player.getEquipment().getHelmet());
        equipment.set(Equipment.EquipmentSlot.CHESTPLATE, player.getEquipment().getChestplate());
        equipment.set(Equipment.EquipmentSlot.LEGGINGS, player.getEquipment().getLeggings());
        equipment.set(Equipment.EquipmentSlot.BOOTS, player.getEquipment().getBoots());
        equipment.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.AIR));
        equipment.set(Equipment.EquipmentSlot.OFF_HAND, new ItemStack(Material.AIR));


        npc.spawn(location);

        CitizensAPI.getNPCRegistry().saveToStore();
    }

    public void despawnNPC(UUID npcUUID, PlayerLoginEvent event) {
        Player player = event.getPlayer();
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUUID);
        player.teleport(npc.getStoredLocation());
        npc.despawn();

        CitizensAPI.getNPCRegistry().saveToStore();
    }

}
