package spamalot.gataxx.search;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The positions the threads of a parallel search are searching right now, shared by all of them. A thread that is about to search
 * a move whose position is in here puts that move off until it has tried the others, so that the threads do not all search the
 * same subtree at the same time (the idea of the ABDADA algorithm). Entries can be lost or overwritten by another thread; that only
 * costs some efficiency, never correctness, because the table only changes the order in which moves are searched.
 */
final class InProgress {
    private static final int BITS = 16;
    private final AtomicLongArray slots = new AtomicLongArray(1 << BITS);

    private static int slot(long hash) {
        return (int) (hash >>> (64 - BITS));
    }

    /** True if some thread has announced that it is searching the position with this hash. */
    boolean contains(long hash) {
        return slots.getOpaque(slot(hash)) == hash;
    }

    /** Announces that this thread starts searching the position. */
    void enter(long hash) {
        slots.setOpaque(slot(hash), hash);
    }

    /** Announces that this thread has finished searching the position (if nobody has taken its slot meanwhile). */
    void leave(long hash) {
        int s = slot(hash);
        if (slots.getOpaque(s) == hash) {
            slots.setOpaque(s, 0);
        }
    }
}
