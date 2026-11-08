package bobbynooby.dev.lockout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
