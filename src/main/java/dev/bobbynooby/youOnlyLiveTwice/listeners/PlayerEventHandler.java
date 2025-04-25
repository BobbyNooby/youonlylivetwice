package dev.bobbynooby.youOnlyLiveTwice.listeners;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.features.ChatSupressor;
import dev.bobbynooby.youOnlyLiveTwice.features.NameTagHider;
import dev.bobbynooby.youOnlyLiveTwice.features.PlayerNPCS;
import dev.bobbynooby.youOnlyLiveTwice.features.PseudoHardcore;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class PlayerEventHandler implements Listener {

    private final ChatSupressor chatSupressor;
    private final NameTagHider nameTagHider;
    private final PseudoHardcore pseudoHardcore;
    private final PlayerNPCS playerNPCS;

    public PlayerEventHandler(YouOnlyLiveTwice plugin) {
        this.chatSupressor = plugin.chatSupressor;
        this.nameTagHider = plugin.nameTagHider;
        this.pseudoHardcore = plugin.pseudoHardcore;
        this.playerNPCS = plugin.playerNPCS;

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        chatSupressor.handleJoin(event);
        nameTagHider.handleJoin(event);

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event) {
        playerNPCS.handleLogout(event);
        chatSupressor.handleLeave(event);


    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        chatSupressor.handleDeath(event);

//        try {
//            pseudoHardcore.handleDeath(event);
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        try {
            pseudoHardcore.handleEntityDamage(event);
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
        playerNPCS.handleLogin(event);

        pseudoHardcore.handleLogin(event);
    }

}
