package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InProgressTest {
    @Test
    void enterAndLeaveAnnounceAPosition() {
        InProgress p = new InProgress();
        long hash = 0x9E3779B97F4A7C15L;
        assertFalse(p.contains(hash));
        p.enter(hash);
        assertTrue(p.contains(hash));
        p.leave(hash);
        assertFalse(p.contains(hash));
    }

    @Test
    void aSlotTakenByAnotherPositionIsNotClearedByTheOldOwner() {
        InProgress p = new InProgress();
        long a = 0x9E3779B97F4A7C15L;
        long b = a ^ 1L; // the same top bits, so the same slot, but a different position
        p.enter(a);
        p.enter(b);
        assertFalse(p.contains(a), "the later entry replaced the earlier one");
        assertTrue(p.contains(b));
        p.leave(a); // must not remove b's announcement
        assertTrue(p.contains(b));
        p.leave(b);
        assertFalse(p.contains(b));
    }
}
