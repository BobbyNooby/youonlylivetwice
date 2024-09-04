package dev.bobbynooby.youOnlyLiveTwice.commands;

import com.mojang.authlib.GameProfile;
import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.npc.NPC;
import dev.bobbynooby.youOnlyLiveTwice.utils.DummyServerGamePacketListenerImpl;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class YoltCommand implements CommandExecutor, TabExecutor {

    private final YouOnlyLiveTwice plugin;

    public YoltCommand(YouOnlyLiveTwice plugin) {
        this.plugin = plugin;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage("Usage: /yolt [options]");
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            if (sender instanceof Player player) {

                NPC npc = new NPC(player);

                npc.spawnNPC(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());

            }


            return true;
        }


        if (args.length > 1) {

            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("kill")) {
                    plugin.pseudoHardcore.killPlayer(args[1]);

                }

                if (args[0].equalsIgnoreCase("revive")) {
                    plugin.pseudoHardcore.revivePlayer(args[1]);
                }

                return true;
            }
        }


        ;


        return false;
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("kill", "revive", "test");
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("kill") || args[0].equalsIgnoreCase("revive")) {
                return null;
            }
        }

        return new ArrayList<>();
    }
}
