package dev.bobbynooby.youOnlyLiveTwice.npc;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.InventoryHandler;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import net.bytebuddy.asm.Advice;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.Inventory;

import java.util.UUID;

public class AliveNPCTrait extends Trait {

    private final LocalDatabase db;
    private final YouOnlyLiveTwice plugin;

    public AliveNPCTrait(LocalDatabase db, YouOnlyLiveTwice plugin) {
        super("AliveNPCTrait");
        this.db = db;
        this.plugin = plugin;
    }


    @EventHandler
    public void onDeath(NPCDeathEvent event) {
        try {
            NPC npc = event.getNPC();
            UUID npcUUID = npc.getUniqueId();
            UUID playerUUID = plugin.db.getPlayerUUIDFromNPCUUID(npcUUID);
            db.killPlayer(playerUUID);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


}
