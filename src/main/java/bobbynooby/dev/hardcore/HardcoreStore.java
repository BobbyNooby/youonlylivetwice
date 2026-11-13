package bobbynooby.dev.hardcore;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * SQLite file holding each player's hardcore state as JSON, and a few per-world values. Kept apart from the death
 * database so its schema can change without touching that one. Methods are synchronized, like the death store's.
 */
public final class HardcoreStore implements AutoCloseable {
    /** The schema this build writes, kept in SQLite's user_version. */
    public static final int SCHEMA_VERSION = 1;
    // Same waits as the death store: a backup tool holding the file shouldn't stall the server thread for long on a read
    static final int READ_WAIT_MILLIS = 250;
    static final int WRITE_WAIT_MILLIS = 3000;

    private final Connection connection;

    private HardcoreStore(Connection connection) {
        this.connection = connection;
    }

    /** Opens the file, making it and its folder if needed. One from a newer YOLT is refused and left as it is. */
    public static HardcoreStore open(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            if (Files.exists(dbFile)) {
                OptionalInt found = readOnlyVersion(url);
                if (found.isPresent()) {
                    refuseNewer(dbFile, found.getAsInt());
                }
            }
            Connection connection = DriverManager.getConnection(url);
            try {
                setUp(connection, dbFile);
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.close();
                } catch (SQLException closing) {
                    e.addSuppressed(closing);
                }
                throw e;
            }
            return new HardcoreStore(connection);
        } catch (Exception e) {
            throw new IllegalStateException("Could not open hardcore store at " + dbFile, e);
        }
    }

    // Empty when a crash left a journal beside the file; the read-write open rolls it back, then checks the version again
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

    // All or nothing, so a failed open leaves the file as it was
    private static void setUp(Connection connection, Path dbFile) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            int found = userVersion(statement);
            refuseNewer(dbFile, found);
            // The v1 tables. A schema change is a new step on top of these, never an edit to them
            statement.execute("CREATE TABLE IF NOT EXISTS player (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS world (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            if (found != SCHEMA_VERSION) {
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
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
        connection.setAutoCommit(true);
    }

    private static void refuseNewer(Path dbFile, int found) {
        if (found > SCHEMA_VERSION) {
            throw new IllegalStateException("The hardcore database " + dbFile + " is schema v" + found
                    + ", newer than this version of YOLT reads (v" + SCHEMA_VERSION + "), so it is left as it is");
        }
    }

    private static int userVersion(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized Optional<String> player(UUID uuid) {
        return read("SELECT data FROM player WHERE uuid = ?", uuid.toString(), "Could not read the hardcore state of " + uuid);
    }

    public synchronized void savePlayer(UUID uuid, String data) {
        write("INSERT OR REPLACE INTO player (uuid, data) VALUES (?, ?)", uuid.toString(), data,
                "Could not save the hardcore state of " + uuid);
    }

    public synchronized Optional<String> world(String key) {
        return read("SELECT value FROM world WHERE key = ?", key, "Could not read the hardcore world value " + key);
    }

    public synchronized void saveWorld(String key, String value) {
        write("INSERT OR REPLACE INTO world (key, value) VALUES (?, ?)", key, value,
                "Could not save the hardcore world value " + key);
    }

    private Optional<String> read(String sql, String key, String failure) {
        try (PreparedStatement statement = prepare(sql, READ_WAIT_MILLIS)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    private void write(String sql, String key, String value, String failure) {
        try (PreparedStatement statement = prepare(sql, WRITE_WAIT_MILLIS)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    private PreparedStatement prepare(String sql, int waitMillis) throws SQLException {
        connection.unwrap(SQLiteConnection.class).setBusyTimeout(waitMillis);
        return connection.prepareStatement(sql);
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close hardcore store", e);
        }
    }
}
