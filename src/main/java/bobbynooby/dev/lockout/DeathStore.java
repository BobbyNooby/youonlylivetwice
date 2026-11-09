package bobbynooby.dev.lockout;

import bobbynooby.dev.core.Migrations;
import bobbynooby.dev.core.Yolt;
import org.jspecify.annotations.Nullable;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
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
import java.util.OptionalInt;
import java.util.UUID;

/** SQLite-backed death log. Methods are synchronized to be safe, though the login check and death handling both run on the server thread. */
public final class DeathStore {
    /** The schema this build writes, kept in SQLite's user_version. */
    public static final int SCHEMA_VERSION = 2;
    // Each step upgrades the schema from its version to the next; they all run in one transaction
    static final List<Migrations.Step<Statement>> STEPS = List.of(
            // How the player died, and who revived them and when: a revive keeps the row as history. Old rows keep
            // nulls, and a bypass from before still counts as a revive.
            sql(1, "ALTER TABLE death_logs ADD COLUMN death_message TEXT",
                    "ALTER TABLE death_logs ADD COLUMN revived_at INTEGER",
                    "ALTER TABLE death_logs ADD COLUMN revived_by TEXT",
                    // Every login looks a player up by uuid, and the commands by name, ignoring case
                    "CREATE INDEX IF NOT EXISTS death_logs_uuid ON death_logs (uuid)",
                    "CREATE INDEX IF NOT EXISTS death_logs_name ON death_logs (username COLLATE NOCASE)"));
    private static final String COLUMNS = "uuid, username, death_time_ms, cooldown_days, bypass, death_message, revived_at, revived_by";

    private final Connection connection;

    private DeathStore(Connection connection) {
        this.connection = connection;
    }

    public static DeathStore open(Path dbFile) {
        return open(dbFile, SCHEMA_VERSION, STEPS);
    }

    /**
     * Opens the database and brings its schema up to date. A database from a newer YOLT is only ever opened read-only
     * and is refused, so the caller fails open as for any database error, and nothing is written to it. One from the
     * 1.21.5 build is moved aside and a new one started.
     */
    static DeathStore open(Path dbFile, int schemaVersion, List<Migrations.Step<Statement>> steps) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            if (Files.exists(dbFile)) {
                OptionalInt found = readOnlyVersion(url);
                if (found.isPresent()) {
                    refuseNewer(dbFile, found.getAsInt(), schemaVersion);
                }
            }
            Connection connection = connect(url, dbFile, schemaVersion, steps);
            if (connection == null) {
                moveAside(dbFile);
                connection = connect(url, dbFile, schemaVersion, steps);
                if (connection == null) {
                    throw new IllegalStateException("The new death database " + dbFile + " reads as the 1.21.5 format");
                }
            }
            return new DeathStore(connection);
        } catch (Exception e) {
            throw new IllegalStateException("Could not open death store at " + dbFile, e);
        }
    }

    // Null, with the connection closed again, when the file is the 1.21.5 build's
    private static @Nullable Connection connect(String url, Path dbFile, int schemaVersion,
                                                List<Migrations.Step<Statement>> steps) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        boolean upgraded;
        try {
            upgraded = upgrade(connection, dbFile, schemaVersion, steps);
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        if (upgraded) {
            return connection;
        }
        connection.close();
        return null;
    }

    // The 1.21.5 release kept deaths in the same file and table, with other columns. Worlds start fresh after the
    // port, so it isn't converted. It is kept beside the new one, and its deaths no longer lock anyone out.
    private static void moveAside(Path dbFile) throws IOException {
        Path kept = dbFile.resolveSibling(dbFile.getFileName() + ".1.21.5-" + System.currentTimeMillis() + ".bak");
        Files.move(dbFile, kept);
        Yolt.LOGGER.warn("The death database {} is from the 1.21.5 build, so it was moved to {} and a new one started. "
                + "Lockouts from before the move have ended.", dbFile, kept);
    }

    /**
     * Empty when a crash cut off a write and left its journal beside the file: a read-only connection can't roll that
     * back. The read-write open then does, which is SQLite's own crash recovery and only puts back the last committed
     * state. The check inside the upgrade's transaction still refuses a newer schema before anything of ours is written.
     */
    private static OptionalInt readOnlyVersion(String url) throws SQLException {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection = DriverManager.getConnection(url, readOnly.toProperties());
             Statement statement = connection.createStatement()) {
            return OptionalInt.of(userVersion(statement));
        } catch (SQLiteException e) {
            if (e.getResultCode() == SQLiteErrorCode.SQLITE_READONLY_ROLLBACK) {
                return OptionalInt.empty();
            }
            throw e;
        }
    }

    // All or nothing: a step that fails leaves the file as it was. False, with nothing written, for the 1.21.5 build's.
    private static boolean upgrade(Connection connection, Path dbFile, int schemaVersion,
                                   List<Migrations.Step<Statement>> steps) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            int found = userVersion(statement);
            refuseNewer(dbFile, found, schemaVersion);
            if (found == 0 && isFromBeforeThePort(statement)) {
                connection.rollback();
                return false;
            }
            // The v1 table. Never edit it: a schema change is a new step in STEPS, so a new database goes through the
            // same steps as an old one and both end up the same
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
            try {
                connection.rollback();
            } catch (SQLException rollingBack) {
                e.addSuppressed(rollingBack);
            }
            throw e;
        }
        // Only on success: a failed open closes the connection anyway
        connection.setAutoCommit(true);
        return true;
    }

    private static void refuseNewer(Path dbFile, int found, int schemaVersion) {
        if (found > schemaVersion) {
            throw new IllegalStateException("The death database " + dbFile + " is schema v" + found
                    + ", newer than this version of YOLT reads (v" + schemaVersion + "), so it is left as it is");
        }
    }

    // The 1.21.5 table has no death_time_ms column; stamping it v1 would only hide that it can't be read
    private static boolean isFromBeforeThePort(Statement statement) throws SQLException {
        boolean hasTable = false;
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(death_logs)")) {
            while (columns.next()) {
                hasTable = true;
                if ("death_time_ms".equals(columns.getString("name"))) {
                    return false;
                }
            }
        }
        return hasTable;
    }

    private static int userVersion(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** A schema step that runs SQL statements in order, for STEPS. */
    static Migrations.Step<Statement> sql(int from, String... statements) {
        return new Migrations.Step<>(from, statement -> {
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw new IllegalStateException("Could not upgrade the death database with: " + sql, e);
                }
            }
            return statement;
        });
    }

    public synchronized void recordDeath(UUID uuid, String name, long nowMillis, int cooldownDays, @Nullable String deathMessage) {
        String sql = "INSERT INTO death_logs (uuid, username, death_time_ms, cooldown_days, bypass, death_message) VALUES (?, ?, ?, ?, 0, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setLong(3, nowMillis);
            statement.setInt(4, cooldownDays);
            statement.setString(5, deathMessage);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record death for " + name, e);
        }
    }

    public synchronized Optional<DeathLog> latest(UUID uuid) {
        return newest("SELECT " + COLUMNS + " FROM death_logs WHERE uuid = ? ORDER BY ROWID DESC LIMIT 1",
                uuid.toString(), "Could not read death log for " + uuid);
    }

    /** The newest death recorded under this name, ignoring case: whoever last died as it. */
    public synchronized Optional<DeathLog> latestByName(String name) {
        return newest("SELECT " + COLUMNS + " FROM death_logs WHERE username = ? COLLATE NOCASE ORDER BY ROWID DESC LIMIT 1",
                name, "Could not look up the death log for " + name);
    }

    private Optional<DeathLog> newest(String sql, String key, String failure) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    private static DeathLog read(ResultSet rs) throws SQLException {
        long revivedAt = rs.getLong("revived_at");
        // getLong reads a null as 0
        Long revived = rs.wasNull() ? null : revivedAt;
        return new DeathLog(UUID.fromString(rs.getString("uuid")), rs.getString("username"), rs.getLong("death_time_ms"),
                rs.getInt("cooldown_days"), rs.getInt("bypass") != 0, rs.getString("death_message"), revived,
                rs.getString("revived_by"));
    }

    /** Marks the newest row revived, by whom and when, and keeps it; false when the player has no deaths. */
    public synchronized boolean revive(UUID uuid, long nowMillis, String by) {
        String sql = "UPDATE death_logs SET bypass = 1, revived_at = ?, revived_by = ? "
                + "WHERE ROWID = (SELECT MAX(ROWID) FROM death_logs WHERE uuid = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMillis);
            statement.setString(2, by);
            statement.setString(3, uuid.toString());
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
