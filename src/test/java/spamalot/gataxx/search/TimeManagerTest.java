package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimeManagerTest {
    @Test
    void budgetsAFractionOfTheClock() {
        SearchLimits l = TimeManager.forClock(60_000, 0, 0, 0);
        assertTrue(l.hardMs() > 0 && l.hardMs() < 60_000 / 2);
        assertTrue(l.softMs() > 0 && l.softMs() < l.hardMs());
        assertEquals(0, l.maxDepth());
    }

    @Test
    void incrementAddsTime() {
        assertTrue(TimeManager.forClock(10_000, 2_000, 0, 0).hardMs()
                > TimeManager.forClock(10_000, 0, 0, 0).hardMs());
    }

    @Test
    void movesToGoSpreadsTime() {
        assertTrue(TimeManager.forClock(60_000, 0, 5, 0).hardMs()
                > TimeManager.forClock(60_000, 0, 0, 0).hardMs());
    }

    @Test
    void neverExceedsClockEvenWhenAlmostOut() {
        for (long left : new long[] {0, 1, 40, 100, 500}) {
            SearchLimits l = TimeManager.forClock(left, 5_000, 0, 0);
            assertTrue(l.hardMs() >= 1);
            assertTrue(l.softMs() >= 1 && l.softMs() <= l.hardMs());
            assertTrue(l.hardMs() <= Math.max(1, left), "left=" + left + " hard=" + l.hardMs());
        }
    }

    @Test
    void depthCapPassedThrough() {
        assertEquals(7, TimeManager.forClock(1000, 0, 0, 7).maxDepth());
    }

    @Test
    void emptySquaresPredictTheMovesLeft() {
        long early = TimeManager.forClock(60_000, 0, 0, 0, 45, 1, 40, 30).hardMs();
        long late = TimeManager.forClock(60_000, 0, 0, 0, 8, 1, 40, 30).hardMs();
        assertTrue(late > early, "early=" + early + " late=" + late);
        assertEquals(TimeManager.forClock(60_000, 0, 0, 0).hardMs(),
                TimeManager.forClock(60_000, 0, 0, 0, 8, 0, 40, 30).hardMs());
    }
}
