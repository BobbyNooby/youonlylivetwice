package bobbynooby.dev.lockout;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** How long a dead player stays out, and how that is told to them. */
public final class Cooldown {
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    private static final long SECOND_MS = Duration.ofSeconds(1).toMillis();
    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();
    // Longest first. A month is 30 days and a year 365.
    private static final List<Unit> UNITS = List.of(
            new Unit("year", 365 * DAY_SECONDS),
            new Unit("month", 30 * DAY_SECONDS),
            new Unit("week", 7 * DAY_SECONDS),
            new Unit("day", DAY_SECONDS),
            new Unit("hour", Duration.ofHours(1).toSeconds()),
            new Unit("minute", Duration.ofMinutes(1).toSeconds()));

    private record Unit(String name, long seconds) {
    }

    private Cooldown() {}

    /** Milliseconds until the player may join again; 0 when revived or expired. */
    public static long remainingMillis(DeathLog log, long nowMillis) {
        if (log.revived()) {
            return 0;
        }
        long end = log.deathTimeMillis() + log.cooldownDays() * DAY_MS;
        return Math.max(0, end - nowMillis);
    }

    /** What a locked-out player is told, at login and when the death kicks them: how they died, then the wait. */
    public static String refusal(@Nullable String deathMessage, long remainingMillis) {
        String died = deathMessage == null || deathMessage.isBlank() ? "You died." : "You died: " + deathMessage;
        return died + "\n" + message(remainingMillis);
    }

    /**
     * "Try again in …". Rounded up to the second, so a kick straight after a 7-day death says 1 week, and a wait
     * under a second isn't 0.
     */
    public static String message(long remainingMillis) {
        long seconds = Math.ceilDiv(remainingMillis, SECOND_MS);
        List<String> parts = new ArrayList<>();
        for (Unit unit : UNITS) {
            long count = seconds / unit.seconds();
            seconds %= unit.seconds();
            if (count > 0) {
                parts.add(withUnit(count, unit.name()));
            }
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(withUnit(seconds, "second"));
        }
        return "Try again in " + String.join(", ", parts) + ".";
    }

    private static String withUnit(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
