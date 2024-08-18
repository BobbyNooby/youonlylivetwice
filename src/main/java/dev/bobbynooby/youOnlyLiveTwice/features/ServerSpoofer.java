package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.PluginConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.Iterator;

public class ServerSpoofer implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(ServerListPingEvent event) {

        if (PluginConfig.getInstance().getSpoofServer()) {
            event.setMaxPlayers(PluginConfig.getInstance().getSpoofedPlayerCount());

            Iterator<Player> players = event.iterator();

            while (players.hasNext()) {
                players.next();
                players.remove();
            }
        }
    }

}
