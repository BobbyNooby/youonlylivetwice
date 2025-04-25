package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.npc.AliveNPC;
import dev.bobbynooby.youOnlyLiveTwice.npc.RegistryHandler;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerNPCS {

    private YouOnlyLiveTwice plugin;
    private LocalDatabase db;
    private RegistryHandler registryHandler;

    public void start(YouOnlyLiveTwice plugin) {
        this.db = plugin.db;
        this.plugin = plugin;
        this.registryHandler = new RegistryHandler(db, plugin);
    }


    public void handleLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();


        try {
            if (!db.playerExists(player)) {
                AliveNPC npc = new AliveNPC(player, db, plugin);
                db.addPlayer(player, npc.getNPCUUID());
            } else {
                registryHandler.despawnNPC(db.getNPCUUIDFromPlayerUUID(player.getUniqueId()), event);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void handleLogout(PlayerQuitEvent event) {
        if (!event.getPlayer().hasMetadata("NPC")) {
            Player player = event.getPlayer();
            try {
                if (db.playerIsAlive(player)) {
                    UUID npcUUID = db.getNPCUUIDFromPlayerUUID(player.getUniqueId());

                    registryHandler.spawnNPC(npcUUID, event.getPlayer().getLocation(), event);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void handleAliveNPCDeath(NPC npc) {
        UUID npcUUID = npc.getUniqueId();


    }
}
