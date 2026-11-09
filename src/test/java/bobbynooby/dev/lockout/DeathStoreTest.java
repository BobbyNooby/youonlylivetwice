package bobbynooby.dev.lockout;

import bobbynooby.dev.core.Migrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeathStoreTest {
    @TempDir
    Path dir;

    private final UUID uuid = UUID.randomUUID();

    @Test
    void latestIsEmptyForUnknownPlayer() {
        DeathStore store = DeathStore.open(dir.resolve("db/test.db"));
        try {
            assertTrue(store.latest(uuid).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void recordThenLatestRoundTrips() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7);
            assertEquals(new DeathLog(uuid, "Bob", 1000L, 7, false), store.latest(uuid).orElseThrow());
        } finally {
            store.close();
        }
    }

    @Test
    void latestReturnsNewestOfTwo() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7);
            store.recordDeath(uuid, "Bob", 2000L, 7);
            assertEquals(2000L, store.latest(uuid).orElseThrow().deathTimeMillis());
        } finally {
            store.close();
        }
    }

    @Test
    void reviveSetsBypassOnLatestOnly() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7);
            store.recordDeath(uuid, "Bob", 2000L, 7);
            assertTrue(store.revive(uuid));
            assertTrue(store.latest(uuid).orElseThrow().bypass());
            // a new death after the revive is a fresh, un-bypassed row
            store.recordDeath(uuid, "Bob", 3000L, 7);
            assertFalse(store.latest(uuid).orElseThrow().bypass());
        } finally {
            store.close();
        }
    }

    @Test
    void reviveWithNoDeathsReturnsFalse() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            assertFalse(store.revive(uuid));
        } finally {
            store.close();
        }
    }

    @Test
    void deathSurvivesReopen() {
        Path file = dir.resolve("test.db");
        DeathStore first = DeathStore.open(file);
        first.recordDeath(uuid, "Bob", 1000L, 7);
        first.close();

        DeathStore second = DeathStore.open(file);
        try {
            assertTrue(second.latest(uuid).isPresent());
        } finally {
            second.close();
        }
    }

    private static int query(Path file, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static final String REASON_COLUMNS =
            "SELECT COUNT(*) FROM pragma_table_info('death_logs') WHERE name = 'reason'";

    @Test
    void schemaStepsRunAndSetTheVersion() throws SQLException {
        Path file = dir.resolve("test.db");
        DeathStore.open(file).close();
        DeathStore upgraded = DeathStore.open(file, 2, List.of(DeathStore.sql(1, "ALTER TABLE death_logs ADD COLUMN reason TEXT")));
        upgraded.close();
        assertEquals(2, query(file, "PRAGMA user_version"));
        assertEquals(1, query(file, REASON_COLUMNS));
    }

    // The first step works and the second fails, so the rollback must undo both
    @Test
    void failedSchemaStepChangesNothing() throws SQLException {
        Path file = dir.resolve("test.db");
        DeathStore.open(file).close();
        List<Migrations.Step<Statement>> steps = List.of(
                DeathStore.sql(1, "ALTER TABLE death_logs ADD COLUMN reason TEXT"),
                DeathStore.sql(2, "NOT SQL AT ALL"));
        assertThrows(IllegalStateException.class, () -> DeathStore.open(file, 3, steps));
        assertEquals(1, query(file, "PRAGMA user_version"));
        assertEquals(0, query(file, REASON_COLUMNS));
    }

    // A crash during a write leaves a "hot" journal beside the file. Copying the file and its journal while a write is
    // still open makes one, since nothing holds the copy, as after a crash. synchronous=OFF writes the journal's header
    // straight away, which is what makes it count as hot.
    @Test
    void hotJournalStillOpens() throws IOException, SQLException {
        Path file = dir.resolve("crashed.db");
        DeathStore before = DeathStore.open(file);
        before.recordDeath(uuid, "Bob", 1000L, 7);
        before.close();

        Path copy = dir.resolve("copy.db");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA synchronous = OFF");
            writer.setAutoCommit(false);
            statement.execute("INSERT INTO death_logs VALUES ('" + uuid + "', 'Bob', 2000, 7, 0)");
            Files.copy(file, copy);
            Files.copy(dir.resolve("crashed.db-journal"), dir.resolve("copy.db-journal"));
            writer.rollback();
        }

        // What failed before: a read-only connection can't roll the journal back
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        SQLiteException refused = assertThrows(SQLiteException.class, () -> {
            try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + copy.toAbsolutePath(), readOnly.toProperties());
                 Statement statement = reader.createStatement()) {
                statement.executeQuery("PRAGMA user_version").close();
            }
        });
        assertEquals(SQLiteErrorCode.SQLITE_READONLY_ROLLBACK, refused.getResultCode());

        DeathStore recovered = DeathStore.open(copy);
        try {
            assertEquals(1000L, recovered.latest(uuid).orElseThrow().deathTimeMillis());
        } finally {
            recovered.close();
        }
        assertEquals(1, query(copy, "SELECT COUNT(*) FROM death_logs"));
        assertEquals(DeathStore.SCHEMA_VERSION, query(copy, "PRAGMA user_version"));
    }
}
