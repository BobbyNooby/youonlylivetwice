package bobbynooby.dev.lockout;

import bobbynooby.dev.core.Migrations;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQLite-backed death log. Methods are synchronized to be safe, though the login check and death handling both run on the server thread. */
public final class DeathStore {
    /** The schema this build writes, kept in SQLite's user_version. */
    public static final int SCHEMA_VERSION = 1;
    // Each step upgrades the schema from its version to the next; they all run in one transaction
    static final List<Migrations.Step<Statement>> STEPS = List.of();

    private final Connection connection;

    private DeathStore(Connection connection) {
        this.connection = connection;
    }

    public static DeathStore open(Path dbFile) {
        return open(dbFile, SCHEMA_VERSION, STEPS);
    }

    /**
     * Opens the database and brings its schema up to date. A database from a newer YOLT is only ever opened read-only
     * and is refused, so the caller fails open as for any database error, and nothing is written to it.
     */
    static DeathStore open(Path dbFile, int schemaVersion, List<Migrations.Step<Statement>> steps) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            if (Files.exists(dbFile)) {
                refuseNewer(dbFile, readOnlyVersion(url), schemaVersion);
            }
            Connection connection = DriverManager.getConnection(url);
            try {
                upgrade(connection, dbFile, schemaVersion, steps);
            } catch (Exception e) {
                connection.close();
                throw e;
            }
            return new DeathStore(connection);
        } catch (Exception e) {
            throw new IllegalStateException("Could not open death store at " + dbFile, e);
        }
    }

    private static int readOnlyVersion(String url) throws SQLException {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection = DriverManager.getConnection(url, readOnly.toProperties());
             Statement statement = connection.createStatement()) {
            return userVersion(statement);
        }
    }

    // All or nothing: a step that fails leaves the file as it was
    private static void upgrade(Connection connection, Path dbFile, int schemaVersion,
                                List<Migrations.Step<Statement>> steps) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            int found = userVersion(statement);
            refuseNewer(dbFile, found, schemaVersion);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS death_logs (
                        uuid TEXT,
                        username TEXT,
                        death_time_ms INTEGER,
                        cooldown_days INTEGER,
                        bypass INTEGER
                    )
                    """);
            // Version 0 is a new file, or one from before the schema had a version; both now hold the v1 table
            Migrations.run("death database", Math.max(found, 1), schemaVersion, steps, statement);
            if (found != schemaVersion) {
                statement.execute("PRAGMA user_version = " + schemaVersion);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void refuseNewer(Path dbFile, int found, int schemaVersion) {
        if (found > schemaVersion) {
            throw new IllegalStateException("The death database " + dbFile + " is schema v" + found
                    + ", newer than this version of YOLT reads (v" + schemaVersion + "), so it is left as it is");
        }
    }

    private static int userVersion(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** A schema step that runs one SQL statement, for STEPS. */
    static Migrations.Step<Statement> sql(int from, String sql) {
        return new Migrations.Step<>(from, statement -> {
            try {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not upgrade the death database with: " + sql, e);
            }
            return statement;
        });
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
