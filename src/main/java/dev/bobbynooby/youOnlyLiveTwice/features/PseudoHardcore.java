package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class PseudoHardcore {

    private LocalDatabase db;

    public void start(YouOnlyLiveTwice plugin) {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            db = new LocalDatabase(plugin.getDataFolder().getAbsolutePath() + "/players.db");

        } catch (SQLException e) {
            e.printStackTrace();
            PluginPrint.println("Failed to connect to database!" + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(plugin);
        }
    }

    public void stop() {
        try {
            db.closeConnection();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void handleLogin(PlayerLoginEvent event) throws SQLException {
        Player player = event.getPlayer();
        PluginPrint.println("Player " + player.getName() + " logged in.");

        //Check if exists
        if (!db.playerExists(player)) {
            db.addPlayer(player);
        }
        ;

        //Check if alive
        if (!db.playerIsAlive(player)) {
            PluginPrint.println("Player " + player.getName() + " " + db.playerIsAlive(player));
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "You are dead.");
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
            db.killPlayer(player);
            player.kickPlayer(updatedDeathMessage);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
