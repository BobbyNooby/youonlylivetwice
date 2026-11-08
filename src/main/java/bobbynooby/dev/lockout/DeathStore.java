package bobbynooby.dev.lockout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

/** SQLite-backed death log. Methods are synchronized: login and death handling can run on different threads. */
public final class DeathStore {
    private final Connection connection;

    private DeathStore(Connection connection) {
        this.connection = connection;
    }

    public static DeathStore open(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS death_logs (
                            uuid TEXT,
                            username TEXT,
                            death_time_ms INTEGER,
                            cooldown_days INTEGER,
                            bypass INTEGER
                        )
                        """);
            }
            return new DeathStore(connection);
        } catch (Exception e) {
            throw new IllegalStateException("Could not open death store at " + dbFile, e);
        }
    }

    public synchronized void recordDeath(UUID uuid, String name, long nowMillis, int cooldownDays) {
        String sql = "INSERT INTO death_logs (uuid, username, death_time_ms, cooldown_days, bypass) VALUES (?, ?, ?, ?, 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setLong(3, nowMillis);
            statement.setInt(4, cooldownDays);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record death for " + name, e);
        }
    }

    public synchronized Optional<DeathLog> latest(UUID uuid) {
        String sql = "SELECT username, death_time_ms, cooldown_days, bypass FROM death_logs WHERE uuid = ? ORDER BY ROWID DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new DeathLog(uuid, rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getInt(4) != 0));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read death log for " + uuid, e);
        }
    }

    /** Sets bypass on the newest row; false when the player has no deaths. */
    public synchronized boolean revive(UUID uuid) {
        String sql = "UPDATE death_logs SET bypass = 1 WHERE ROWID = (SELECT MAX(ROWID) FROM death_logs WHERE uuid = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not revive " + uuid, e);
        }
    }

    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close death store", e);
        }
    }
}
