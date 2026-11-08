package bobbynooby.dev.features;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.database.DatabaseHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.text.SimpleDateFormat;
import java.util.Date;

public class PseudoHardcore {


    public static void initialize() {


        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            handleJoin(handler.getPlayer(), server);
        });

        // Graves used to start the lockout, so start it here on death instead
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                handleDeath(player);
            }
        });

    }

    public static void handleDeath(ServerPlayer player) {
        player.connection.disconnect(Component.literal("You have died."));
        DatabaseHandler.addNewDeathLog(player.nameAndId());
    }

    public static void handleRevive(NameAndId playerProfile) {
        DatabaseHandler.revivePlayer(playerProfile.id());

    }

    public static void handleJoin(ServerPlayer player, MinecraftServer server) {
        DatabaseHandler.DeathLog latestDeathlog = DatabaseHandler.getLatestDeathLog(player);


        if (latestDeathlog != null) {
            try {
                // Parse the stored death time
                SimpleDateFormat format = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.ENGLISH);
                Date deathDate = format.parse(latestDeathlog.deathTime);
                int cooldownValue = latestDeathlog.respawnCooldown;
                long cooldownMillis = cooldownValue * 1000L * 60L * 60L * 24L;

                // Check if the player is on cooldown
                long allowedTimeMillis = deathDate.getTime() + (cooldownMillis);
                long nowMillis = System.currentTimeMillis();
                long remaining = (allowedTimeMillis - nowMillis);

                if (nowMillis < allowedTimeMillis && !latestDeathlog.bypass) {
                    player.connection.disconnect(getCooldownFromMillis(remaining));
                    YouOnlyLiveTwice.LOGGER.info("Kicked {} due to active cooldown. {}s remaining.", player.getGameProfile().name(), remaining);
                } else {
                    // Player's cooldown has expired — allow join and unban them
                    server.getPlayerList().getBans().remove(player.nameAndId());
                    YouOnlyLiveTwice.LOGGER.info("Player {} cooldown expired. Allowed to join.", player.getGameProfile().name());
                }
            } catch (Exception e) {
                YouOnlyLiveTwice.LOGGER.error("Failed to parse death time for " + player.getGameProfile().name(), e);
            }
        }


    }


    public static Component getCooldownFromMillis(long millis) {
        long seconds = millis / 1000;

        long years = seconds / (60 * 60 * 24 * 365);
        seconds %= (60 * 60 * 24 * 365);

        long months = seconds / (60 * 60 * 24 * 30);
        seconds %= (60 * 60 * 24 * 30);

        long weeks = seconds / (60 * 60 * 24 * 7);
        seconds %= (60 * 60 * 24 * 7);

        long days = seconds / (60 * 60 * 24);
        seconds %= (60 * 60 * 24);

        long hours = seconds / (60 * 60);
        seconds %= (60 * 60);

        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder sb = new StringBuilder("You are dead.\nTry again in ");

        if (years > 0) sb.append(years).append(" year").append(years > 1 ? "s" : "").append(", ");
        if (months > 0) sb.append(months).append(" month").append(months > 1 ? "s" : "").append(", ");
        if (weeks > 0) sb.append(weeks).append(" week").append(weeks > 1 ? "s" : "").append(", ");
        if (days > 0) sb.append(days).append(" day").append(days > 1 ? "s" : "").append(", ");
        if (hours > 0) sb.append(hours).append(" hour").append(hours > 1 ? "s" : "").append(", ");
        if (minutes > 0) sb.append(minutes).append(" minute").append(minutes > 1 ? "s" : "").append(", ");
        if (seconds > 0 || sb.toString().endsWith("in "))
            sb.append(seconds).append(" second").append(seconds != 1 ? "s" : "");

        // Clean up trailing comma and space
        String result = sb.toString().replaceAll(", $", "");
        return Component.literal(result + ".");
    }

}
