package dev.bobbynooby.youOnlyLiveTwice.listeners;

import dev.bobbynooby.youOnlyLiveTwice.PluginConfig;
import dev.bobbynooby.youOnlyLiveTwice.features.ChatSupressor;
import dev.bobbynooby.youOnlyLiveTwice.features.NameTagHider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

public class PlayerEventHandler implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        ChatSupressor.handleJoin(event);
        NameTagHider.handleJoin(event);

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event) {
        ChatSupressor.handleLeave(event);


    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        ChatSupressor.handleDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        NameTagHider.handleWorldChange(event);

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        ChatSupressor.handleMessage(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        ChatSupressor.handleWhisper(event);
    }
}
