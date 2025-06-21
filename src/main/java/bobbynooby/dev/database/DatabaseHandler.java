package bobbynooby.dev.database;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.features.Config;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;
import java.util.UUID;

public class DatabaseHandler {

    private static final String DATABASE_NAME = "you-only-live-twice.db";
    private static Connection connection;

    public static void initialize(MinecraftServer server) {
        try {
            // Get database path
            Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            Path databaseDir = worldDir.resolve("database");
            Files.createDirectories(databaseDir);

            // Connect to database
            Path dbFileDir = databaseDir.resolve(DATABASE_NAME);
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFileDir.toAbsolutePath());
            YouOnlyLiveTwice.LOGGER.info("Initialized database at" + databaseDir);

            // Create table if it doesn't exist
            String sql = """
                    CREATE TABLE IF NOT EXISTS death_logs (
                        uuid TEXT,
                        username TEXT,
                        deathTime TEXT,
                        respawnCooldown INTEGER,
                        bypass BOOLEAN
                    );
                    """;
            connection.createStatement().execute(sql);
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to initialize database", e);
        }

    }

    public static void addNewDeathLog(GameProfile player) {
        // Get player data
        String playerUuid = player.getId().toString();
        String playerName = player.getName();
        String deathTime = new Date().toString();
        int respawnCooldown = Config.getRespawnCooldown();

        // Add death log
        try {
            String sql = "INSERT INTO death_logs (uuid, username, deathTime, respawnCooldown, bypass) VALUES (?, ?, ?, ?, FALSE)";
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, playerUuid);
            stmt.setString(2, playerName);
            stmt.setString(3, deathTime);
            stmt.setInt(4, respawnCooldown);
            stmt.executeUpdate();

            YouOnlyLiveTwice.LOGGER.info("Death log added for " + playerName);
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to add death log", e);
        }
    }

    public static void revivePlayer(UUID playerUuid) {
        String sql = """
                UPDATE death_logs
                   SET bypass = TRUE
                 WHERE ROWID = (
                       SELECT ROWID
                         FROM death_logs
                        WHERE uuid = ?
                     ORDER BY ROWID DESC
                        LIMIT 1
                   )
                """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                YouOnlyLiveTwice.LOGGER.info("Revived latest death log for " + playerUuid);
            } else {
                YouOnlyLiveTwice.LOGGER.warn("No death logs found to revive for " + playerUuid);
            }
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to revive latest death log", e);
        }
    }


    public static void close() {
        try {
            connection.close();
            YouOnlyLiveTwice.LOGGER.info("Closed database");
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to close database", e);
        }
    }

    public static Connection getConnection() {
        return connection;
    }

    public static DeathLog getLatestDeathLog(ServerPlayerEntity player) {
        try {
            String sql = "SELECT * FROM death_logs WHERE uuid = ? ORDER BY ROWID DESC LIMIT 1";
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, player.getUuidAsString());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String username = rs.getString("username");
                String deathTime = rs.getString("deathTime");
                int cooldown = rs.getInt("respawnCooldown");
                boolean bypass = rs.getBoolean("bypass");

                return new DeathLog(player.getUuidAsString(), username, deathTime, cooldown, bypass);

            }
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to get latest death log", e);
            return null;
        }

        return null;
    }

    public static class DeathLog {
        public String uuid;
        public String username;
        public String deathTime;
        public int respawnCooldown;
        public boolean bypass;

        public DeathLog(String uuid, String username, String deathTime, int respawnCooldown, boolean bypass) {
            this.uuid = uuid;
            this.username = username;
            this.deathTime = deathTime;
            this.respawnCooldown = respawnCooldown;
            this.bypass = bypass;
        }

        public String toString() {
            return "DeathLog{" +
                    "uuid='" + uuid + '\'' +
                    ", username='" + username + '\'' +
                    ", deathTime='" + deathTime + '\'' +
                    ", respawnCooldown=" + respawnCooldown +
                    '}';
        }
    }
}
