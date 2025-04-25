package dev.bobbynooby.youOnlyLiveTwice.commands;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.npc.AliveNPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
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
                AliveNPC aliveNPC = new AliveNPC(player);
                aliveNPC.spawnNPC(player.getLocation());

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
