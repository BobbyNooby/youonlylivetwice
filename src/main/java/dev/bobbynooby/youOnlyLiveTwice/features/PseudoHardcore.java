package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;

public class PseudoHardcore {

    private LocalDatabase db;

    public void start(YouOnlyLiveTwice plugin) {
        this.db = plugin.db;
    }


    public void handleLogin(PlayerLoginEvent event) throws SQLException {
        Player player = event.getPlayer();
        PluginPrint.println("Player " + player.getName() + " logged in.");

        //Check if alive
        if (!db.playerIsAlive(player)) {
            PluginPrint.println("Player " + player.getName() + " " + db.playerIsAlive(player));
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "You are dead. You only live once. Make sure to cherish it.");
        }

    }

    public void handleEntityDamage(EntityDamageEvent event) throws SQLException {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        double playerHealth = player.getHealth();
        double damage = event.getFinalDamage();
        if (playerHealth - damage <= 0) {
            event.setCancelled(true);
            db.killPlayer(player.getUniqueId());
            player.kickPlayer("You are have died.");
        }
    }


    public void handleDeath(PlayerDeathEvent event) throws SQLException {
        Player player = event.getEntity();
        String playerName = player.getName();
        String deathMessage = event.getDeathMessage();
        String updatedDeathMessage = deathMessage;


        if (deathMessage.startsWith(playerName)) {
            updatedDeathMessage = "You" + deathMessage.substring(playerName.length());
        }
        updatedDeathMessage = updatedDeathMessage.replace(playerName, "you");

        try {
            db.killPlayer(player.getUniqueId());
            player.kickPlayer(updatedDeathMessage);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void killPlayer(String username) {
        try {
            UUID uuid = db.getUUIDFromUsername(username);
            Player player;

            if (uuid == null) {
                return;
            }

            if (Bukkit.getPlayer(uuid) != null) {
                player = Bukkit.getPlayer(uuid);
                player.setHealth(0);
                player.kickPlayer("You are dead. You only live once. Make sure to cherish it.");
            } else {
                player = Bukkit.getOfflinePlayer(uuid).getPlayer();
                player.setHealth(0);

            }
            db.killPlayer(player.getUniqueId());


        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    public void revivePlayer(String username) {
        try {
            UUID uuid = db.getUUIDFromUsername(username);
            PluginPrint.println("Reviving " + username + " with UUID " + uuid);
            Player player;
            if (uuid == null) {
                return;
            }
            if (Bukkit.getPlayer(uuid) != null) {
                player = Bukkit.getPlayer(uuid);
                player.setHealth(10);
            } else {
                player = Bukkit.getOfflinePlayer(uuid).getPlayer();
                player.setHealth(10);
            }
            db.revivePlayer(player.getUniqueId());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
