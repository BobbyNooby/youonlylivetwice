package bobbynooby.dev.hardcore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcoreCurseTest {
    private static final long PER_HEAL = 120_000;

    @Test
    void curse1DeathAndTotemCostOneEach() {
        HardcoreState s = new HardcoreState();
        assertEquals(1, CurseMaths.onReturn(s, 1, 2, true));
        assertEquals(1, s.curseHeartsLost);
        assertEquals(1, s.curseLastDeathAdded);
        assertEquals(1, CurseMaths.onReturn(s, 1, 2, false));
        assertEquals(2, s.curseHeartsLost);
        // A totem isn't a death, so an unkill would still give back only the death's heart
        assertEquals(1, s.curseLastDeathAdded);
    }

    @Test
    void curse1StopsAtTheCap() {
        HardcoreState s = new HardcoreState();
        s.curseHeartsLost = 2;
        s.curseLastDeathAdded = 1;
        assertEquals(0, CurseMaths.onReturn(s, 1, 2, true));
        assertEquals(2, s.curseHeartsLost);
        assertEquals(0, s.curseLastDeathAdded);
    }

    @Test
    void curse1HealsOnOnlineTime() {
        HardcoreState s = new HardcoreState();
        s.curseHeartsLost = 2;
        for (int i = 0; i < PER_HEAL - 1; i++) {
            assertEquals(0, CurseMaths.onOnlineTick(s, 1, PER_HEAL));
        }
        assertEquals(2, s.curseHeartsLost);
        assertEquals(1, CurseMaths.onOnlineTick(s, 1, PER_HEAL));
        assertEquals(1, s.curseHeartsLost);
        int healed = 0;
        for (int i = 0; i < PER_HEAL; i++) {
            healed += CurseMaths.onOnlineTick(s, 1, PER_HEAL);
        }
        assertEquals(1, healed);
        assertEquals(0, s.curseHeartsLost);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, CurseMaths.onOnlineTick(s, 1, PER_HEAL));
        }
        assertEquals(0, s.curseOnlineTicks);
    }

    @Test
    void curse1ASecondLossKeepsTheTimer() {
        HardcoreState s = new HardcoreState();
        s.curseHeartsLost = 1;
        s.curseOnlineTicks = 100_000;
        assertEquals(1, CurseMaths.onReturn(s, 1, 2, true));
        assertEquals(2, s.curseHeartsLost);
        for (int i = 0; i < 19_999; i++) {
            assertEquals(0, CurseMaths.onOnlineTick(s, 1, PER_HEAL));
        }
        assertEquals(1, CurseMaths.onOnlineTick(s, 1, PER_HEAL));
        assertEquals(1, s.curseHeartsLost);
    }

    @Test
    void curse1UnkillRefundsTheLatestDeath() {
        HardcoreState s = new HardcoreState();
        CurseMaths.onReturn(s, 1, 2, true);
        assertEquals(1, CurseMaths.refundLatestDeath(s));
        assertEquals(0, s.curseHeartsLost);
        assertEquals(0, s.curseLastDeathAdded);

        HardcoreState atCap = new HardcoreState();
        atCap.curseHeartsLost = 2;
        CurseMaths.onReturn(atCap, 1, 2, true);
        assertEquals(0, CurseMaths.refundLatestDeath(atCap));
        assertEquals(2, atCap.curseHeartsLost);
    }

    @Test
    void curse1Modifier() {
        HardcoreState s = new HardcoreState();
        s.curseHeartsLost = 2;
        assertEquals(-4.0, CurseMaths.modifier(s, 2));
        // A loss stored before the cap was lowered shows as the new cap
        s.curseHeartsLost = 3;
        assertEquals(-2.0, CurseMaths.modifier(s, 1));
    }

    @Test
    void cursePlayTimeRoundsUpToTheMinute() {
        assertEquals("1h 12m", CurseMaths.playTime(72 * 1200));
        assertEquals("1h", CurseMaths.playTime(60 * 1200));
        assertEquals("1m", CurseMaths.playTime(1));
        assertEquals("2m", CurseMaths.playTime(1201));
    }
}
