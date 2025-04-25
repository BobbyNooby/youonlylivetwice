package dev.bobbynooby.youOnlyLiveTwice;

import dev.bobbynooby.youOnlyLiveTwice.commands.YoltCommand;
import dev.bobbynooby.youOnlyLiveTwice.features.*;
import dev.bobbynooby.youOnlyLiveTwice.listeners.NPCListener;
import dev.bobbynooby.youOnlyLiveTwice.listeners.PlayerEventHandler;
import dev.bobbynooby.youOnlyLiveTwice.listeners.ProtocolLibHandler;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import dev.bobbynooby.youOnlyLiveTwice.utils.PluginPrint;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;


public final class YouOnlyLiveTwice extends JavaPlugin {

    public PseudoHardcore pseudoHardcore = new PseudoHardcore();
    public ChatSupressor chatSupressor = new ChatSupressor();
    public NameTagHider nameTagHider = new NameTagHider();
    public PlayerNPCS playerNPCS = new PlayerNPCS();
    public LocalDatabase db;

    @Override
    public void onEnable() {
        // Plugin startup logic

        try {
            if (!this.getDataFolder().exists()) {
                this.getDataFolder().mkdirs();
            }

            db = new LocalDatabase(this.getDataFolder().getAbsolutePath() + "/players.db");

        } catch (SQLException e) {
            e.printStackTrace();
            PluginPrint.println("Failed to connect to database!" + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }

        getCommand("yolt").setExecutor(new YoltCommand(this));

        PluginConfig.getInstance().load();
        PluginPrint.println("Settings Loaded!");

        nameTagHider.start();
        PluginPrint.println("NameTagHider Enabled!");

        pseudoHardcore.start(this);
        PluginPrint.println("PseudoHardcore Enabled!");

        playerNPCS.start(this);
        PluginPrint.println("PlayerNPCS Enabled!");


        ProtocolLibHandler.start();
        PluginPrint.println("ProtocolLib Enabled!");

        getServer().getPluginManager().registerEvents(new ServerSpoofer(), this);
        PluginPrint.println("Server Spoofer Enabled!");

        getServer().getPluginManager().registerEvents(new PlayerEventHandler(this), this);
        PluginPrint.println("Player Event Handler Enabled!");

        getServer().getPluginManager().registerEvents(new NPCListener(), this);

        PluginPrint.println("You Only Live Twice Started!");


    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic

        try {
            db.closeConnection();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        PluginConfig.getInstance().save();
        PluginPrint.println("Settings Saved!");

        nameTagHider.stop();

        PluginPrint.println("You Only Live Twice Stopped!");
    }

    public static YouOnlyLiveTwice getInstance() {
        return getPlugin(YouOnlyLiveTwice.class);
    }
}
