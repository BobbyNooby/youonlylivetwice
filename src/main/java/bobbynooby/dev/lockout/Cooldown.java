package bobbynooby.dev.lockout;

public final class Cooldown {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private Cooldown() {}

    /** Milliseconds until the player may join again; 0 when bypassed or expired. */
    public static long remainingMillis(DeathLog log, long nowMillis) {
        if (log.bypass()) {
            return 0;
        }
        long end = log.deathTimeMillis() + log.cooldownDays() * DAY_MS;
        return Math.max(0, end - nowMillis);
    }

    public static String message(long remainingMillis) {
        long seconds = remainingMillis / 1000;

        long years = seconds / (60 * 60 * 24 * 365);
        seconds %= (60 * 60 * 24 * 365);

        long months = seconds / (60 * 60 * 24 * 30);
        seconds %= (60 * 60 * 24 * 30);

        long weeks = seconds / (60 * 60 * 24 * 7);
        seconds %= (60 * 60 * 24 * 7);

        long days = seconds / (60 * 60 * 24);
        seconds %= (60 * 60 * 24);

        long hours = seconds / (60 * 60);
        seconds %= (60 * 60);

        long minutes = seconds / 60;
        seconds %= 60;

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
