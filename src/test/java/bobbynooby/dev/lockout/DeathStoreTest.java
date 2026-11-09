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
import java.util.stream.Stream;

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
            store.recordDeath(uuid, "Bob", 1000L, 7, "Bob was slain by Zombie");
            assertEquals(new DeathLog(uuid, "Bob", 1000L, 7, false, "Bob was slain by Zombie", null, null),
                    store.latest(uuid).orElseThrow());
        } finally {
            store.close();
        }
    }

    @Test
    void longDeathMessageIsCappedAt256() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7, "x".repeat(300));
            assertEquals("x".repeat(256), store.latest(uuid).orElseThrow().deathMessage());
            // A character outside the basic plane is two chars; its first half would be left on its own at 256
            String split = "y".repeat(255) + "😀" + "z";
            store.recordDeath(uuid, "Bob", 2000L, 7, split);
            assertEquals("y".repeat(255), store.latest(uuid).orElseThrow().deathMessage());
            store.recordDeath(uuid, "Bob", 3000L, 7, "x".repeat(256));
            assertEquals("x".repeat(256), store.latest(uuid).orElseThrow().deathMessage());
        } finally {
            store.close();
        }
    }

    @Test
    void latestReturnsNewestOfTwo() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7, null);
            store.recordDeath(uuid, "Bob", 2000L, 7, null);
            assertEquals(2000L, store.latest(uuid).orElseThrow().deathTimeMillis());
        } finally {
            store.close();
        }
    }

    @Test
    void reviveSetsBypassOnLatestOnly() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7, null);
            store.recordDeath(uuid, "Bob", 2000L, 7, null);
            assertTrue(store.revive(uuid, 2500L, "Alex"));
            assertTrue(store.latest(uuid).orElseThrow().bypass());
            // a new death after the revive is a fresh, un-bypassed row
            store.recordDeath(uuid, "Bob", 3000L, 7, null);
            assertFalse(store.latest(uuid).orElseThrow().bypass());
        } finally {
            store.close();
        }
    }

    // The death stays as history, with who revived it and when
    @Test
    void reviveKeepsTheDeathAndWhoRevivedIt() throws SQLException {
        Path file = dir.resolve("test.db");
        DeathStore store = DeathStore.open(file);
        try {
            store.recordDeath(uuid, "Bob", 1000L, 7, "Bob drowned");
            assertTrue(store.revive(uuid, 2500L, "Alex"));
            assertEquals(new DeathLog(uuid, "Bob", 1000L, 7, true, "Bob drowned", 2500L, "Alex"), store.latest(uuid).orElseThrow());
        } finally {
            store.close();
        }
        assertEquals(1, query(file, "SELECT COUNT(*) FROM death_logs"));
    }

    @Test
    void reviveWithNoDeathsReturnsFalse() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            assertFalse(store.revive(uuid, 1000L, "Alex"));
        } finally {
            store.close();
        }
    }

    @Test
    void deathSurvivesReopen() {
        Path file = dir.resolve("test.db");
        DeathStore first = DeathStore.open(file);
        first.recordDeath(uuid, "Bob", 1000L, 7, null);
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

    // Steps past this build's schema, as the next build would add them
    private static final int NOW = DeathStore.SCHEMA_VERSION;

    @Test
    void schemaStepsRunAndSetTheVersion() throws SQLException {
        Path file = dir.resolve("test.db");
        DeathStore.open(file).close();
        DeathStore upgraded = DeathStore.open(file, NOW + 1, List.of(DeathStore.sql(NOW, "ALTER TABLE death_logs ADD COLUMN reason TEXT")));
        upgraded.close();
        assertEquals(NOW + 1, query(file, "PRAGMA user_version"));
        assertEquals(1, query(file, REASON_COLUMNS));
    }

    // The first step works and the second fails, so the rollback must undo both
    @Test
    void failedSchemaStepChangesNothing() throws SQLException {
        Path file = dir.resolve("test.db");
        DeathStore.open(file).close();
        List<Migrations.Step<Statement>> steps = List.of(
                DeathStore.sql(NOW, "ALTER TABLE death_logs ADD COLUMN reason TEXT"),
                DeathStore.sql(NOW + 1, "NOT SQL AT ALL"));
        assertThrows(IllegalStateException.class, () -> DeathStore.open(file, NOW + 2, steps));
        assertEquals(NOW, query(file, "PRAGMA user_version"));
        assertEquals(0, query(file, REASON_COLUMNS));
    }

    // A crash during a write leaves a "hot" journal beside the file. Copying the file and its journal while a write is
    // still open makes one, since nothing holds the copy, as after a crash. synchronous=OFF writes the journal's header
    // straight away, which is what makes it count as hot.
    @Test
    void hotJournalStillOpens() throws IOException, SQLException {
        Path file = dir.resolve("crashed.db");
        DeathStore before = DeathStore.open(file);
        before.recordDeath(uuid, "Bob", 1000L, 7, null);
        before.close();

        Path copy = dir.resolve("copy.db");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA synchronous = OFF");
            writer.setAutoCommit(false);
            statement.execute("INSERT INTO death_logs (uuid, username, death_time_ms, cooldown_days, bypass) "
                    + "VALUES ('" + uuid + "', 'Bob', 2000, 7, 0)");
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

    @Test
    void latestByNameIgnoresCase() {
        DeathStore store = DeathStore.open(dir.resolve("test.db"));
        try {
            store.recordDeath(uuid, "Ghost", 1000L, 7, null);
            assertEquals(uuid, store.latestByName("ghost").orElseThrow().uuid());
            // Someone else died under the name since, so they are who it means now
            UUID other = UUID.randomUUID();
            store.recordDeath(other, "GHOST", 2000L, 7, null);
            assertEquals(other, store.latestByName("ghost").orElseThrow().uuid());
            assertTrue(store.latestByName("Ghost2").isEmpty());
        } finally {
            store.close();
        }
    }

    // Every login looks a player up by uuid, and the name lookup ignores case
    @Test
    void indexesExist() throws SQLException {
        Path file = dir.resolve("test.db");
        DeathStore.open(file).close();
        assertEquals(1, query(file, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'death_logs_uuid'"));
        assertEquals(1, query(file, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'death_logs_name'"));
    }

    // A database written before deaths kept their message and who revived them
    @Test
    void oldDatabaseGetsNewColumns() throws SQLException {
        Path file = dir.resolve("v1.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE death_logs (uuid TEXT, username TEXT, death_time_ms INTEGER, cooldown_days INTEGER, bypass INTEGER)");
            statement.execute("INSERT INTO death_logs VALUES ('" + uuid + "', 'Bob', 1000, 7, 1)");
            statement.execute("INSERT INTO death_logs VALUES ('" + uuid + "', 'Bob', 2000, 7, 0)");
            statement.execute("PRAGMA user_version = 1");
        }

        DeathStore.open(file).close();
        assertEquals(DeathStore.SCHEMA_VERSION, query(file, "PRAGMA user_version"));
        assertEquals(3, query(file, "SELECT COUNT(*) FROM pragma_table_info('death_logs') "
                + "WHERE name IN ('death_message', 'revived_at', 'revived_by')"));
        assertEquals(2, query(file, "SELECT COUNT(*) FROM death_logs WHERE uuid = '" + uuid + "' AND death_time_ms IN (1000, 2000) "
                + "AND death_message IS NULL AND revived_at IS NULL AND revived_by IS NULL"));
        assertEquals(1, query(file, "SELECT bypass FROM death_logs WHERE death_time_ms = 1000"));
    }

    // The 1.21.5 release's table. Worlds start fresh after the port, so it isn't converted: it is moved aside, its
    // lockouts end, and a new database takes its place
    @Test
    void oldFormatDatabaseIsMovedAside() throws IOException, SQLException {
        Path file = dir.resolve("old.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE death_logs (uuid TEXT, username TEXT, deathTime TEXT, respawnCooldown INTEGER, bypass BOOLEAN)");
            statement.execute("INSERT INTO death_logs VALUES ('" + uuid + "', 'Bob', 'Mon Jun 02 12:00:00 UTC 2025', 7, 0)");
        }

        DeathStore store = DeathStore.open(file);
        try {
            assertTrue(store.latest(uuid).isEmpty());
            store.recordDeath(uuid, "Bob", 1000L, 7, null);
            assertEquals(1000L, store.latest(uuid).orElseThrow().deathTimeMillis());
        } finally {
            store.close();
        }
        assertEquals(DeathStore.SCHEMA_VERSION, query(file, "PRAGMA user_version"));

        List<Path> kept;
        try (Stream<Path> files = Files.list(dir)) {
            kept = files.filter(path -> path.getFileName().toString().matches("old\\.db\\.1\\.21\\.5-\\d+\\.bak")).toList();
        }
        assertEquals(1, kept.size(), "copies kept: " + kept);
        assertEquals(1, query(kept.get(0), "SELECT COUNT(*) FROM death_logs WHERE deathTime = 'Mon Jun 02 12:00:00 UTC 2025'"));
    }
}
