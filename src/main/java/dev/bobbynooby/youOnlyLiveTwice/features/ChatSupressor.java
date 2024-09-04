package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.PluginConfig;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

public class ChatSupressor {
    public void handleJoin(PlayerJoinEvent event) {
        if (PluginConfig.getInstance().getHidePlayerJoin()) {
            event.setJoinMessage("");
        }
    }

    public void handleLeave(PlayerQuitEvent event) {
        if (PluginConfig.getInstance().getHidePlayerLeave()) {
            event.setQuitMessage("");
        }
    }

    public void handleDeath(PlayerDeathEvent event) {
        if (PluginConfig.getInstance().getHidePlayerDeath()) {
            event.setDeathMessage("");
        }
    }

    public void handleMessage(AsyncPlayerChatEvent event) {
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

    public void handleWhisper(PlayerCommandPreprocessEvent event) {

        if (event.getMessage().startsWith("/w") || event.getMessage().startsWith("/msg")) {
            String finalMessage = "";
            String[] wordArray = event.getMessage().split(" ");
            if (wordArray.length > 2) {
                finalMessage = String.join(" ", Arrays.copyOfRange(wordArray, 2, wordArray.length));
            }

            Player sender = event.getPlayer();
            Player recipient = Bukkit.getPlayer(wordArray[1]);


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
