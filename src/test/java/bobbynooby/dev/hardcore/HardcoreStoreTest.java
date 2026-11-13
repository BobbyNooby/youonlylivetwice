package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
}
