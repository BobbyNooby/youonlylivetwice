package dev.bobbynooby.youOnlyLiveTwice;

import dev.bobbynooby.youOnlyLiveTwice.commands.YoltCommand;
import dev.bobbynooby.youOnlyLiveTwice.features.ChatSupressor;
import dev.bobbynooby.youOnlyLiveTwice.features.NameTagHider;
import dev.bobbynooby.youOnlyLiveTwice.features.PseudoHardcore;
import dev.bobbynooby.youOnlyLiveTwice.features.ServerSpoofer;
import dev.bobbynooby.youOnlyLiveTwice.listeners.PlayerEventHandler;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import org.bukkit.plugin.java.JavaPlugin;

import javax.naming.Name;

public final class YouOnlyLiveTwice extends JavaPlugin {

    public PseudoHardcore pseudoHardcore = new PseudoHardcore();
    public ChatSupressor chatSupressor = new ChatSupressor();
    public NameTagHider nameTagHider = new NameTagHider();

    @Override
    public void onEnable() {
        // Plugin startup logic

        getCommand("yolt").setExecutor(new YoltCommand(this));

        PluginConfig.getInstance().load();
        PluginPrint.println("Settings Loaded!");

        nameTagHider.start();
        PluginPrint.println("NameTagHider Enabled!");

        pseudoHardcore.start(this);
        PluginPrint.println("PseudoHardcore Enabled!");


        getServer().getPluginManager().registerEvents(new ServerSpoofer(), this);
        PluginPrint.println("Server Spoofer Enabled!");

        getServer().getPluginManager().registerEvents(new PlayerEventHandler(this), this);
        PluginPrint.println("Player Event Handler Enabled!");

        PluginPrint.println("You Only Live Twice Started!");


    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic


        PluginConfig.getInstance().save();
        PluginPrint.println("Settings Saved!");

        pseudoHardcore.stop();
        nameTagHider.stop();

        PluginPrint.println("You Only Live Twice Stopped!");
    }

    public static YouOnlyLiveTwice getInstance() {
        return getPlugin(YouOnlyLiveTwice.class);
    }
}
