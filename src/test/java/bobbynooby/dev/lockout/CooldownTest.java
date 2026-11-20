package bobbynooby.dev.lockout;

import bobbynooby.dev.config.YoltConfig;
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
        return new DeathLog(UUID_A, "Bob", 0L, days, bypass, null, null, null);
    }

    @Test
    void remainingCountsDown() {
        assertEquals(6 * DAY, Cooldown.remainingMillis(log(7, false), DAY));
    }

    // A death stamped ahead of now: the server's clock ran fast when it was recorded, or was set back since
    @Test
    void aClockSetBackNeverMakesTheWaitLongerThanTheCooldown() {
        DeathLog ahead = new DeathLog(UUID_A, "Bob", 30 * DAY, 7, false, null, null, null);
        assertEquals(7 * DAY, Cooldown.remainingMillis(ahead, DAY));
        assertEquals(7 * DAY, Cooldown.remainingMillis(ahead, 30 * DAY));
        assertEquals(6 * DAY, Cooldown.remainingMillis(ahead, 31 * DAY));
    }

    @Test
    void expiredIsZero() {
        assertEquals(0, Cooldown.remainingMillis(log(7, false), 8 * DAY));
    }

    @Test
    void bypassIsZero() {
        assertEquals(0, Cooldown.remainingMillis(log(7, true), DAY));
    }

    // A revive fills in who and when; it ends the lockout without the bypass flag too
    @Test
    void revivedIsZero() {
        assertEquals(0, Cooldown.remainingMillis(new DeathLog(UUID_A, "Bob", 0L, 7, false, null, DAY / 2, "Alex"), DAY));
    }

    @Test
    void zeroDaysMeansNoWait() {
        assertEquals(0, Cooldown.remainingMillis(log(0, false), 0));
    }

    @Test
    void messageFormatsUnits() {
        assertEquals("Try again in 1 day, 1 hour, 1 minute, 1 second.", Cooldown.message(90_061_000L));
    }

    // Rounded up, so a player still kept out is never told 0
    @Test
    void messageForUnderOneSecond() {
        assertEquals("Try again in 1 second.", Cooldown.message(500L));
    }

    // Straight after a 7-day death, a few milliseconds have gone
    @Test
    void messageRoundsUpToTheSecond() {
        assertEquals("Try again in 1 week.", Cooldown.message(7 * DAY - 3));
    }

    @Test
    void refusalSaysHowTheyDiedThenTheWait() {
        assertEquals("You died: Bob fell from a high place\nTry again in 1 week.",
                Cooldown.refusal("Bob fell from a high place", 7 * DAY));
    }

    @Test
    void refusalWithoutMessageStillGivesTheWait() {
        assertEquals("You died.\nTry again in 2 days.", Cooldown.refusal(null, 2 * DAY));
    }

    // The config's words, with the same death message and wait filled in; the plain line when there is no message
    @Test
    void refusalTakesTheConfigsWords() {
        YoltConfig.MessagesSection texts = new YoltConfig.MessagesSection();
        texts.lockoutDied = "\u00a74Gone: {death}";
        texts.lockoutDiedPlain = "\u00a74Gone.";
        texts.lockoutWait = "Back in {wait}, maybe.";
        assertEquals("\u00a74Gone: Bob fell from a high place\nBack in 1 week, maybe.",
                Cooldown.refusal("Bob fell from a high place", 7 * DAY, texts));
        assertEquals("\u00a74Gone.\nBack in 2 days, maybe.", Cooldown.refusal(null, 2 * DAY, texts));
        assertEquals("\u00a74Gone.\nBack in 2 days, maybe.", Cooldown.refusal(" ", 2 * DAY, texts));
        assertEquals("Wait 1 day, 1 hour, 1 minute, 1 second", Cooldown.message(90_061_000L, "Wait {wait}"));
        // A death message is filled in once: a mob named {wait} stays as named
        assertEquals("You died: Bob was slain by {wait}\nTry again in 1 week.",
                Cooldown.refusal("Bob was slain by {wait}", 7 * DAY, new YoltConfig.MessagesSection()));
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
