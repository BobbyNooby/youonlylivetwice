package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Array;
import java.util.*;

public class ChatSupressor {
    public static void handleJoin(PlayerJoinEvent event) {
        if (PluginConfig.getInstance().getHidePlayerJoin()) {
            event.setJoinMessage("");
        }
    }

    public static void handleLeave(PlayerQuitEvent event) {
        if (PluginConfig.getInstance().getHidePlayerLeave()) {
            event.setQuitMessage("");
        }
    }

    public static void handleDeath(PlayerDeathEvent event) {
        if (PluginConfig.getInstance().getHidePlayerDeath()) {
            event.setDeathMessage("");
        }
    }

    public static void handleMessage(AsyncPlayerChatEvent event) {
        final Iterator<Player> iterator = event.getRecipients().iterator();
        int chatRadius = PluginConfig.getInstance().getProximityRadius();


        while (iterator.hasNext()) {
            final Player recipient = iterator.next();
            if (recipient.getWorld() == event.getPlayer().getWorld()) {
                if (recipient.getLocation().distance(event.getPlayer().getLocation()) > chatRadius) {
                    iterator.remove();
                }
            }
        }


    }

    public static void handleWhisper(PlayerCommandPreprocessEvent event) {

        if (event.getMessage().startsWith("/w") || event.getMessage().startsWith("/msg")) {
            String finalMessage = "";
            String[] wordArray = event.getMessage().split(" ");
            if (wordArray.length > 2) {
                finalMessage = String.join(" ", Arrays.copyOfRange(wordArray, 2, wordArray.length));
            }

            Player sender = event.getPlayer();
            Player recipient = Bukkit.getPlayer(wordArray[1]);

            System.out.println(sender.getName() + " is whispering to " + recipient.getName());

            String greyItalics = ChatColor.GRAY + "" + ChatColor.ITALIC;
            String senderPre = "You whisper to " + recipient.getName() + ": ";
            String recipientPre = sender.getName() + " whispers to you: ";

            int chatRadius = PluginConfig.getInstance().getProximityRadius();


            event.setCancelled(true);

            sender.sendMessage(greyItalics + senderPre + finalMessage);

            if (recipient.getWorld() == sender.getWorld()) {
                if (!(recipient.getLocation().distance(sender.getLocation()) > chatRadius)) {
                    recipient.sendMessage(greyItalics + recipientPre + finalMessage);
                }
            }

            event.setCancelled(true);

        }
    }
}
