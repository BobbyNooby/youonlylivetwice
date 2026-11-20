package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

class HardcoreStoreTest {
    @TempDir
    Path dir;

    private final UUID uuid = UUID.randomUUID();

    @Test
    void storeKeepsAPlayersStateAcrossAReopen() {
        Path file = dir.resolve("database/hardcore.db");
        HardcoreState state = new HardcoreState();
        state.legsBroken = true;
        state.curseHeartsLost = 2;
        state.protein = 0.6;
        try (HardcoreStore store = HardcoreStore.open(file)) {
            store.savePlayer(uuid, state.encode());
        }
        try (HardcoreStore store = HardcoreStore.open(file)) {
            HardcoreState read = HardcoreState.decode(store.player(uuid).orElseThrow());
            assertTrue(read.legsBroken);
            assertEquals(2, read.curseHeartsLost);
            assertEquals(0.6, read.protein);
            assertEquals(HardcoreState.UNSET, read.vitamins);
            assertTrue(store.player(UUID.randomUUID()).isEmpty());
        }
    }

    @Test
    void storeKeepsAWorldValue() {
        Path file = dir.resolve("hardcore.db");
        try (HardcoreStore store = HardcoreStore.open(file)) {
            store.saveWorld("lastBloodMoonDay", "12");
        }
        try (HardcoreStore store = HardcoreStore.open(file)) {
            assertEquals("12", store.world("lastBloodMoonDay").orElseThrow());
            assertTrue(store.world("somethingElse").isEmpty());
        }
    }

    @Test
    void stateDecodesTextFromAnOlderOrDamagedRow() {
        HardcoreState empty = HardcoreState.decode("{}");
        assertFalse(empty.legsBroken);
        assertEquals(0, empty.curseHeartsLost);
        assertEquals(0.0, empty.chill);
        assertEquals(HardcoreState.UNSET, empty.protein);
        assertEquals(HardcoreState.UNSET, empty.vitamins);
        assertEquals(HardcoreState.UNSET, empty.sugar);

        HardcoreState damaged = assertDoesNotThrow(() -> HardcoreState.decode("not json"));
        assertFalse(damaged.legsBroken);
        assertEquals(HardcoreState.UNSET, damaged.protein);

        // A field this build doesn't know is skipped, and the ones it does are still read
        HardcoreState newer = HardcoreState.decode("{\"version\": 2, \"chill\": 0.25, \"thirst\": 3}");
        assertEquals(0.25, newer.chill);
    }

    // A NaN that slipped into one value is written as it is, so the row still reads back with the curse in it rather than
    // starting the player fresh
    @Test
    void aNaNValueSurvivesARoundTrip() {
        HardcoreState state = new HardcoreState();
        state.chill = Double.NaN;
        state.curseHeartsLost = 3;
        HardcoreState decoded = HardcoreState.decode(state.encode());
        assertEquals(3, decoded.curseHeartsLost);
        assertTrue(Double.isNaN(decoded.chill));

        Path file = dir.resolve("hardcore.db");
        try (HardcoreStore store = HardcoreStore.open(file)) {
            store.savePlayer(uuid, state.encode());
        }
        try (HardcoreStore store = HardcoreStore.open(file)) {
            assertEquals(3, HardcoreState.decode(store.player(uuid).orElseThrow()).curseHeartsLost);
        }
    }

    // Whatever sits at the database's path, if it isn't a database, is never written over: states live in memory instead
    @Test
    void aFileThatIsNotADatabaseIsLeftAsItIs() throws IOException {
        Path file = dir.resolve("hardcore.db");
        byte[] garbage = "not a database, but someone's file all the same".repeat(40).getBytes(StandardCharsets.UTF_8);
        Files.write(file, garbage);
        HardcoreStates.useFileForTesting(file);
        try {
            assertDoesNotThrow(() -> HardcoreStates.update(uuid, state -> state.curseHeartsLost = 2));
            assertEquals(2, HardcoreStates.read(uuid).curseHeartsLost);
            HardcoreStates.setWorld("lastBloodMoonDay", 12);
            HardcoreStates.flushAll();
            assertEquals(2, HardcoreStates.read(uuid).curseHeartsLost);
            assertEquals(12, HardcoreStates.world("lastBloodMoonDay").orElseThrow());
            assertArrayEquals(garbage, Files.readAllBytes(file));
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
        assertArrayEquals(garbage, Files.readAllBytes(file), "the file changed at the stop");
        try (var beside = Files.list(dir)) {
            assertEquals(List.of(file), beside.toList(), "something was made beside the file");
        }
    }

    // A file from a newer YOLT is read and written by nobody: the session runs from memory and the file keeps its version
    // and has no table of ours
    @Test
    void aNewerStoreRunsFromMemoryUntouched() throws SQLException {
        Path file = dir.resolve("hardcore.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        int newer = HardcoreStore.SCHEMA_VERSION + 1;
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + newer);
        }
        HardcoreStates.useFileForTesting(file);
        try {
            HardcoreStates.update(uuid, state -> state.legsBroken = true);
            assertTrue(HardcoreStates.read(uuid).legsBroken);
            HardcoreStates.flushAll();
            assertTrue(HardcoreStates.read(uuid).legsBroken);
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertEquals(newer, version.getInt(1));
            }
            try (ResultSet tables = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
                assertEquals(0, tables.getInt(1), "a table was made in the newer file");
            }
        }
    }

    // Another program holding the write lock past the wait leaves the change in memory, and the next flush once the lock
    // is gone writes it. Reads still work under that lock. Takes the write wait, about 3 seconds.
    @Test
    void aWriteLockPastTheWaitIsRetriedLater() throws SQLException {
        Path file = dir.resolve("hardcore.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        HardcoreStates.useFileForTesting(file);
        try {
            // The store is open before the lock, as on a running server
            HardcoreStates.setWorld("lastBloodMoonDay", 1);
            try (Connection holder = DriverManager.getConnection(url); Statement statement = holder.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                HardcoreStates.update(uuid, state -> state.legsBroken = true);
                assertTrue(HardcoreStates.read(uuid).legsBroken, "the change wasn't kept in memory");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM player")) {
                    assertEquals(0, rows.getInt(1), "the write went through the lock");
                }
                statement.execute("ROLLBACK");
            }
            HardcoreStates.flushAll();
            try (HardcoreStore reading = HardcoreStore.open(file)) {
                assertTrue(HardcoreState.decode(reading.player(uuid).orElseThrow()).legsBroken, "the next flush didn't write it");
            }
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
    }

    @Test
    void storeFromANewerVersionIsRefused() throws SQLException {
        Path file = dir.resolve("hardcore.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + (HardcoreStore.SCHEMA_VERSION + 1));
        }
        assertThrows(IllegalStateException.class, () -> HardcoreStore.open(file));
    }

    // The database folder can't be made where a file already is
    @Test
    void storeThatCantOpenRunsFromMemory() throws IOException {
        Path blocker = Files.writeString(dir.resolve("database"), "");
        HardcoreStates.useFileForTesting(blocker.resolve("hardcore.db"));
        try {
            assertDoesNotThrow(() -> HardcoreStates.update(uuid, state -> state.chill = 0.5));
            assertEquals(0.5, HardcoreStates.read(uuid).chill);
            assertDoesNotThrow(() -> HardcoreStates.setWorld("lastBloodMoonDay", 12));
            assertEquals(12, HardcoreStates.world("lastBloodMoonDay").orElseThrow());
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
    }

    @Test
    void storeFileIsNotMadeUntilTheFirstWrite() {
        Path file = dir.resolve("database/hardcore.db");
        HardcoreStates.useFileForTesting(file);
        try {
            HardcoreState fresh = HardcoreStates.read(uuid);
            assertEquals(HardcoreState.UNSET, fresh.protein);
            assertTrue(HardcoreStates.world("lastBloodMoonDay").isEmpty());
            assertFalse(Files.exists(file));
            assertFalse(Files.exists(file.getParent()));

            HardcoreStates.update(uuid, state -> state.legsBroken = true);
            assertTrue(Files.exists(file));
            assertTrue(HardcoreStates.read(uuid).legsBroken);
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
    }

    // A closed store fails every write, as one held by another program would; the stop then retries on a working one
    @Test
    void storeRetriesAWriteThatFailedBeforeTheStop() {
        Path file = dir.resolve("hardcore.db");
        HardcoreStore broken = HardcoreStore.open(file);
        broken.close();
        HardcoreStates.useFileForTesting(null);
        HardcoreStates.useStoreForTesting(broken);
        try {
            HardcoreStates.update(uuid, state -> state.legsBroken = true);
            HardcoreStates.setWorld("lastBloodMoonDay", 12);
            // Kept in memory meanwhile
            assertTrue(HardcoreStates.read(uuid).legsBroken);
            assertEquals(12, HardcoreStates.world("lastBloodMoonDay").orElseThrow());

            // Handed over: the reset closes it after the last try
            HardcoreStates.useStoreForTesting(HardcoreStore.open(file));
            HardcoreStates.useFileForTesting(null);
            try (HardcoreStore reopened = HardcoreStore.open(file)) {
                assertTrue(HardcoreState.decode(reopened.player(uuid).orElseThrow()).legsBroken);
                assertEquals("12", reopened.world("lastBloodMoonDay").orElseThrow());
            }
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
    }

    // A read that fails must not start the player fresh for good: the next save would wipe their curse and broken legs
    @Test
    void aFailedReadNeverWritesOverTheStoredState() {
        Path file = dir.resolve("hardcore.db");
        try (HardcoreStore first = HardcoreStore.open(file)) {
            HardcoreState stored = new HardcoreState();
            stored.legsBroken = true;
            first.savePlayer(uuid, stored.encode());
        }
        HardcoreStore broken = HardcoreStore.open(file);
        broken.close();
        HardcoreStates.useFileForTesting(null);
        HardcoreStates.useStoreForTesting(broken);
        try {
            HardcoreStates.update(uuid, state -> state.chill = 0.5);
            HardcoreStates.flushAll();
            try (HardcoreStore reading = HardcoreStore.open(file)) {
                assertTrue(HardcoreState.decode(reading.player(uuid).orElseThrow()).legsBroken, "the stored state was written over");
            }

            // Once the row can be read, the change is made on it
            HardcoreStates.useStoreForTesting(HardcoreStore.open(file));
            HardcoreStates.flushAll();
            try (HardcoreStore reading = HardcoreStore.open(file)) {
                HardcoreState now = HardcoreState.decode(reading.player(uuid).orElseThrow());
                assertTrue(now.legsBroken, "the stored state was lost");
                assertEquals(0.5, now.chill, "the change made while it couldn't be read was lost");
            }
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
    }

    // One transaction: a row the file refuses rolls back the rows before it, and every state waits for the next flush
    @Test
    void flushStopsAtAFailedWriteAndKeepsEveryState() throws SQLException {
        Path file = dir.resolve("hardcore.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        UUID refused = UUID.randomUUID();
        HardcoreStore broken = HardcoreStore.open(file);
        broken.close();
        HardcoreStates.useFileForTesting(null);
        HardcoreStates.useStoreForTesting(broken);
        try {
            HardcoreStates.update(uuid, state -> state.legsBroken = true);
            HardcoreStates.update(refused, state -> state.chill = 0.5);
            HardcoreStates.setWorld("lastBloodMoonDay", 12);
            try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER refuse BEFORE INSERT ON player WHEN NEW.uuid = '" + refused
                        + "' BEGIN SELECT RAISE(ABORT, 'refused'); END");
            }
            HardcoreStates.useStoreForTesting(HardcoreStore.open(file));

            HardcoreStates.flushAll();
            try (HardcoreStore reading = HardcoreStore.open(file)) {
                assertTrue(reading.player(uuid).isEmpty(), "a row before the failed one was kept");
                assertTrue(reading.player(refused).isEmpty());
                assertTrue(reading.world("lastBloodMoonDay").isEmpty(), "a world value was kept");
            }
            assertTrue(HardcoreStates.read(uuid).legsBroken);
            assertEquals(0.5, HardcoreStates.read(refused).chill);
            assertEquals(12, HardcoreStates.world("lastBloodMoonDay").orElseThrow());

            try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER refuse");
            }
            HardcoreStates.flushAll();
            try (HardcoreStore reading = HardcoreStore.open(file)) {
                assertTrue(HardcoreState.decode(reading.player(uuid).orElseThrow()).legsBroken);
                assertEquals(0.5, HardcoreState.decode(reading.player(refused).orElseThrow()).chill);
                assertEquals("12", reading.world("lastBloodMoonDay").orElseThrow());
            }
        } finally {
            HardcoreStates.useFileForTesting(null);
        }
    }
}
