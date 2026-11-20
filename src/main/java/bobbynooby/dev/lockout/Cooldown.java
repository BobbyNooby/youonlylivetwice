package bobbynooby.dev.lockout;

import bobbynooby.dev.config.Messages;
import bobbynooby.dev.config.YoltConfig;
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
        long whole = log.cooldownDays() * DAY_MS;
        long end = log.deathTimeMillis() + whole;
        // Never longer than the cooldown itself: a death recorded while the server's clock ran ahead, or a clock since
        // set back, would otherwise keep the player out for the difference on top
        return Math.clamp(end - nowMillis, 0, Math.max(whole, 0));
    }

    /** The same, in the words a fresh config has. */
    public static String refusal(@Nullable String deathMessage, long remainingMillis) {
        return refusal(deathMessage, remainingMillis, new YoltConfig.MessagesSection());
    }

    /**
     * What a locked-out player is told, at login and when the death kicks them: how they died, then the wait, in the
     * config's words.
     */
    public static String refusal(@Nullable String deathMessage, long remainingMillis, YoltConfig.MessagesSection texts) {
        String died = deathMessage == null || deathMessage.isBlank() ? texts.lockoutDiedPlain
                : Messages.fill(texts.lockoutDied, "death", deathMessage);
        return died + "\n" + message(remainingMillis, texts.lockoutWait);
    }

    /** The same, in the words a fresh config has. */
    public static String message(long remainingMillis) {
        return message(remainingMillis, new YoltConfig.MessagesSection().lockoutWait);
    }

    /**
     * "Try again in …", with the wait filled into the text's {wait}. Rounded up to the second, so a kick straight after a
     * 7-day death says 1 week, and a wait under a second isn't 0.
     */
    public static String message(long remainingMillis, String text) {
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
        return Messages.fill(text, "wait", String.join(", ", parts));
    }

    private static String withUnit(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
