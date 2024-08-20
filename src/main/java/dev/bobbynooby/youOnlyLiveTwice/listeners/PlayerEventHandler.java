package dev.bobbynooby.youOnlyLiveTwice.listeners;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.features.ChatSupressor;
import dev.bobbynooby.youOnlyLiveTwice.features.NameTagHider;
import dev.bobbynooby.youOnlyLiveTwice.features.PseudoHardcore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class PlayerEventHandler implements Listener {

    private final ChatSupressor chatSupressor;
    private final NameTagHider nameTagHider;
    private final PseudoHardcore pseudoHardcore;

    public PlayerEventHandler(YouOnlyLiveTwice plugin) {
        chatSupressor = plugin.chatSupressor;
        nameTagHider = plugin.nameTagHider;
        pseudoHardcore = plugin.pseudoHardcore;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        chatSupressor.handleJoin(event);
        nameTagHider.handleJoin(event);

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event) {
        chatSupressor.handleLeave(event);


    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        chatSupressor.handleDeath(event);

        try {
            pseudoHardcore.handleDeath(event);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        nameTagHider.handleWorldChange(event);

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        chatSupressor.handleMessage(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        chatSupressor.handleWhisper(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) throws SQLException {
        pseudoHardcore.handleLogin(event);
    }

}
