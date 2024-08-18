package dev.bobbynooby.youOnlyLiveTwice.listeners;

import dev.bobbynooby.youOnlyLiveTwice.PluginConfig;
import dev.bobbynooby.youOnlyLiveTwice.features.NameTagHider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerEventHandler implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (PluginConfig.getInstance().getHidePlayerJoin()) {
            event.setJoinMessage("");
        }

        if (PluginConfig.getInstance().getHideNameTags()) {
            NameTagHider.addPlayer(event.getPlayer());
        }

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event) {
        if (PluginConfig.getInstance().getHidePlayerLeave()) {
            event.setQuitMessage("");
        }


    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (PluginConfig.getInstance().getHidePlayerDeath()) {
            event.setDeathMessage("");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {

        if (PluginConfig.getInstance().getHideNameTags()) {
            NameTagHider.addPlayer(event.getPlayer());
        }
    }
}
