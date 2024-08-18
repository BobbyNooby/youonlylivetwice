package dev.bobbynooby.youOnlyLiveTwice;

import dev.bobbynooby.youOnlyLiveTwice.features.NameTagHider;
import dev.bobbynooby.youOnlyLiveTwice.features.ServerSpoofer;
import dev.bobbynooby.youOnlyLiveTwice.listeners.PlayerEventHandler;
import org.bukkit.plugin.java.JavaPlugin;

public final class YouOnlyLiveTwice extends JavaPlugin {


    @Override
    public void onEnable() {
        // Plugin startup logic

        PluginConfig.getInstance().load();
        System.out.println("Settings Loaded!");

        NameTagHider.start();


        getServer().getPluginManager().registerEvents(new ServerSpoofer(), this);
        System.out.println("Server Spoofer Enabled!");

        getServer().getPluginManager().registerEvents(new PlayerEventHandler(), this);
        System.out.println("Player Event Handler Enabled!");

        System.out.println("You Only Live Twice Started!");


    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        NameTagHider.stop();
        System.out.println("Name Tag Hider Disabled!");

        PluginConfig.getInstance().save();
        System.out.println("Settings Saved!");

        System.out.println("You Only Live Twice Stopped!");
    }

    public static YouOnlyLiveTwice getInstance() {
        return getPlugin(YouOnlyLiveTwice.class);
    }
}
