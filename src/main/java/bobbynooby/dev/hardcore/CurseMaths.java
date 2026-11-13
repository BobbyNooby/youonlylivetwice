package bobbynooby.dev.hardcore;

/** The Curse of the Undead's sums, on a stored state and nothing else, so they can be checked without a server. */
public final class CurseMaths {
    private static final int TICKS_PER_MINUTE = 1200;

    private CurseMaths() {}

    /**
     * A return from death, by dying or by a totem: adds up to hearts, never past maxLost in all, and returns what it
     * added. A death also keeps that amount, so an unkill can give it back.
     */
    public static int onReturn(HardcoreState s, int hearts, int maxLost, boolean isDeath) {
        // A loss stored over a lowered cap has no room left, rather than a negative amount
        int room = Math.max(0, maxLost - s.curseHeartsLost);
        int added = Math.min(Math.max(hearts, 0), room);
        s.curseHeartsLost += added;
        if (isDeath) {
            s.curseLastDeathAdded = added;
        }
        return added;
    }

    /**
     * One tick of online play. It only counts while a heart is lost; once ticksPerHeal have counted, healHearts come
     * back and the count goes on from what is left over. Returns the hearts healed.
     */
    public static int onOnlineTick(HardcoreState s, int healHearts, long ticksPerHeal) {
        if (s.curseHeartsLost <= 0) {
            return 0;
        }
        s.curseOnlineTicks++;
        if (s.curseOnlineTicks < ticksPerHeal) {
            return 0;
        }
        s.curseOnlineTicks -= ticksPerHeal;
        return giveBack(s, healHearts);
    }

    /** Gives back what the latest death added, and returns what came back: never more than is still lost. */
    public static int refundLatestDeath(HardcoreState s) {
        int given = giveBack(s, s.curseLastDeathAdded);
        s.curseLastDeathAdded = 0;
        return given;
    }

    /** Gives back up to hearts and returns how many came back. A curse that is gone starts its timer over next time. */
    public static int giveBack(HardcoreState s, int hearts) {
        int given = Math.min(Math.max(hearts, 0), Math.max(s.curseHeartsLost, 0));
        s.curseHeartsLost -= given;
        if (s.curseHeartsLost <= 0) {
            s.curseOnlineTicks = 0;
        }
        return given;
    }

    /** The hearts the player is missing now: what is stored, up to the cap in force. */
    public static int shown(HardcoreState s, int maxLost) {
        return Math.min(Math.max(s.curseHeartsLost, 0), Math.max(maxLost, 0));
    }

    /** The max health modifier's amount, two health points a heart. */
    public static double modifier(HardcoreState s, int maxLost) {
        return -2.0 * shown(s, maxLost);
    }

    /** Ticks of play left as "1h 12m", "1h" or "12m", rounded up to the minute. */
    static String playTime(long ticks) {
        long minutes = Math.max(1, (Math.max(ticks, 0) + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE);
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }
}
