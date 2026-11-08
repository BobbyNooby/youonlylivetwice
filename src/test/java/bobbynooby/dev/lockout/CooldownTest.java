package bobbynooby.dev.lockout;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final UUID UUID_A = UUID.randomUUID();

    private static DeathLog log(int days, boolean bypass) {
        return new DeathLog(UUID_A, "Bob", 0L, days, bypass);
    }

    @Test
    void remainingCountsDown() {
        assertEquals(6 * DAY, Cooldown.remainingMillis(log(7, false), DAY));
    }

    @Test
    void expiredIsZero() {
        assertEquals(0, Cooldown.remainingMillis(log(7, false), 8 * DAY));
    }

    @Test
    void bypassIsZero() {
        assertEquals(0, Cooldown.remainingMillis(log(7, true), DAY));
    }

    @Test
    void zeroDaysMeansNoWait() {
        assertEquals(0, Cooldown.remainingMillis(log(0, false), 0));
    }

    @Test
    void messageFormatsUnits() {
        assertEquals("You are dead.\nTry again in 1 day, 1 hour, 1 minute, 1 second.", Cooldown.message(90_061_000L));
    }

    @Test
    void messageForUnderOneSecond() {
        assertEquals("You are dead.\nTry again in 0 seconds.", Cooldown.message(500L));
    }
}
