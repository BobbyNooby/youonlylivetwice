package bobbynooby.dev.lockout;

import java.time.Duration;

/** How long a dead player stays out, and how that is told to them. */
public final class Cooldown {
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    private static final long MINUTE_SECONDS = Duration.ofMinutes(1).toSeconds();
    private static final long HOUR_SECONDS = Duration.ofHours(1).toSeconds();
    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();
    private static final long WEEK_SECONDS = 7 * DAY_SECONDS;
    private static final long MONTH_SECONDS = 30 * DAY_SECONDS;
    private static final long YEAR_SECONDS = 365 * DAY_SECONDS;

    private Cooldown() {}

    /** Milliseconds until the player may join again; 0 when revived or expired. */
    public static long remainingMillis(DeathLog log, long nowMillis) {
        if (log.revived()) {
            return 0;
        }
        long end = log.deathTimeMillis() + log.cooldownDays() * DAY_MS;
        return Math.max(0, end - nowMillis);
    }

    public static String message(long remainingMillis) {
        long seconds = remainingMillis / 1000;

        long years = seconds / YEAR_SECONDS;
        seconds %= YEAR_SECONDS;

        long months = seconds / MONTH_SECONDS;
        seconds %= MONTH_SECONDS;

        long weeks = seconds / WEEK_SECONDS;
        seconds %= WEEK_SECONDS;

        long days = seconds / DAY_SECONDS;
        seconds %= DAY_SECONDS;

        long hours = seconds / HOUR_SECONDS;
        seconds %= HOUR_SECONDS;

        long minutes = seconds / MINUTE_SECONDS;
        seconds %= MINUTE_SECONDS;

        StringBuilder sb = new StringBuilder("You are dead.\nTry again in ");

        if (years > 0) sb.append(years).append(" year").append(years > 1 ? "s" : "").append(", ");
        if (months > 0) sb.append(months).append(" month").append(months > 1 ? "s" : "").append(", ");
        if (weeks > 0) sb.append(weeks).append(" week").append(weeks > 1 ? "s" : "").append(", ");
        if (days > 0) sb.append(days).append(" day").append(days > 1 ? "s" : "").append(", ");
        if (hours > 0) sb.append(hours).append(" hour").append(hours > 1 ? "s" : "").append(", ");
        if (minutes > 0) sb.append(minutes).append(" minute").append(minutes > 1 ? "s" : "").append(", ");
        if (seconds > 0 || sb.toString().endsWith("in "))
            sb.append(seconds).append(" second").append(seconds != 1 ? "s" : "");

        return sb.toString().replaceAll(", $", "") + ".";
    }
}
