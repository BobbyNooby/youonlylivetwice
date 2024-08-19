package dev.bobbynooby.youOnlyLiveTwice.features;

import dev.bobbynooby.youOnlyLiveTwice.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

@SuppressWarnings("deprecation")
public class NameTagHider {
    public static Scoreboard scoreboard;
    public static Team team;

    public static void start() {
        scoreboard = Bukkit.getServer().getScoreboardManager().getNewScoreboard();
        team = scoreboard.registerNewTeam("hidden_name_tag");


        if (PluginConfig.getInstance().getHideNameTags()) {
            hideNameTag();
        } else {
            showNameTag();
        }

        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            nameTagCheck(player);
        }

    }

    public static void stop() {
        scoreboard = null;
        team = null;
    }

    public void reload() {
        stop();
        start();
    }

    public static void showNameTag() {
        team.setNameTagVisibility(NameTagVisibility.ALWAYS);
    }

    public static void hideNameTag() {
        team.setNameTagVisibility(NameTagVisibility.NEVER);
    }

    public static void addPlayer(Player player) {
        if (!team.hasPlayer(player)) {
            team.addPlayer(player);
            player.setScoreboard(scoreboard);
        }
    }

    public void removePlayer(Player player) {
        if (team.hasPlayer(player)) {
            team.removePlayer(player);
            player.setScoreboard(Bukkit.getServer().getScoreboardManager().getMainScoreboard());
        }
    }


    public static void nameTagCheck(Player player) {
        addPlayer(player);
    }

    public static void handleJoin(PlayerJoinEvent event) {
        if (PluginConfig.getInstance().getHideNameTags()) {
            nameTagCheck(event.getPlayer());
        }
    }

    public static void handleWorldChange(PlayerChangedWorldEvent event) {
        if (PluginConfig.getInstance().getHideNameTags()) {
            nameTagCheck(event.getPlayer());
        }
    }
}
