package bobbynooby.dev.lockout;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownTest {
    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
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

    // A month is 30 days and a year 365
    @Test
    void messageCountsYearsMonthsWeeks() {
        assertEquals("Try again in 1 year, 1 month, 5 days.", Cooldown.message(400 * DAY));
    }

    @Test
    void messageUsesSingularAndPlural() {
        long oneOfEach = (365 + 30 + 7 + 1) * DAY + HOUR + MINUTE + SECOND;
        assertEquals("Try again in 1 year, 1 month, 1 week, 1 day, 1 hour, 1 minute, 1 second.", Cooldown.message(oneOfEach));
        assertEquals("Try again in 2 years, 2 months, 2 weeks, 2 days, 2 hours, 2 minutes, 2 seconds.",
                Cooldown.message(2 * oneOfEach));
    }
}
