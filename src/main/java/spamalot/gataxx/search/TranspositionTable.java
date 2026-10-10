package spamalot.gataxx.search;

import java.util.Arrays;
import spamalot.gataxx.board.Move;

/**
 * Fixed-size hash table of search results keyed by Zobrist hash. Not thread-safe: one search at a
 * time, which is how the engine uses it.
 *
 * <p>Each entry is 16 bytes: the full 64-bit key (to reject index collisions) and one packed word
 * holding move, depth, bound type, age and score.
 */
public final class TranspositionTable {
    public static final int BOUND_NONE = 0;
    /** The stored score is the exact value. */
    public static final int BOUND_EXACT = 1;
    /** The true value is at least the stored score (a beta cutoff happened). */
    public static final int BOUND_LOWER = 2;
    /** The true value is at most the stored score (no move raised alpha). */
    public static final int BOUND_UPPER = 3;

    private static final int ENTRY_BYTES = 16;

    // data layout: bits 0-13 move+1 (0 = none), 14-21 depth, 22-23 bound, 24-31 age, 32-47 score
    private static final int MOVE_BITS = 14;
    private static final long MOVE_MASK = (1L << MOVE_BITS) - 1;

    /** Slot {@code i} is {@code slots[2 * i]} (key) and {@code slots[2 * i + 1]} (data), adjacent so a probe is one cache line. */
    private final long[] slots;
    private final int mask;
    private int age;

    /** Result of a probe; valid only when {@link #found} is true. */
    public static final class Entry {
        public boolean found;
        public int move = Move.NONE;
        public int depth;
        public int bound;
        public int score;
    }

    /** @param megabytes approximate size; rounded down to a power-of-two entry count (minimum 1024 entries) */
    public TranspositionTable(int megabytes) {
        long wanted = Math.max(1024, (long) megabytes * 1024 * 1024 / ENTRY_BYTES);
        int entries = (int) Math.min(1L << 30, Long.highestOneBit(wanted));
        slots = new long[2 * entries];
        mask = entries - 1;
    }

    public int capacity() {
        return slots.length / 2;
    }

    public void clear() {
        Arrays.fill(slots, 0);
        age = 0;
    }

    /** Call at the start of each search so stale entries become replaceable. */
    public void newSearch() {
        age = (age + 1) & 0xFF;
    }

    /** Looks up {@code hash}; fills and returns {@code out}. */
    public Entry probe(long hash, Entry out) {
        int i = ((int) hash & mask) << 1;
        long d = slots[i + 1];
        if (slots[i] == hash && (d >>> 22 & 3) != BOUND_NONE) {
            out.found = true;
            int m = (int) (d & MOVE_MASK);
            out.move = m == 0 ? Move.NONE : m - 1;
            out.depth = (int) (d >>> MOVE_BITS & 0xFF);
            out.bound = (int) (d >>> 22 & 3);
            out.score = (short) (d >>> 32);
        } else {
            out.found = false;
            out.move = Move.NONE;
        }
        return out;
    }

    /**
     * Stores a result. Replaces the slot if it is empty, holds the same position, is from an older
     * search, or is not deeper than the new result.
     */
    public void store(long hash, int depth, int bound, int score, int move) {
        int i = ((int) hash & mask) << 1;
        long old = slots[i + 1];
        boolean sameKey = slots[i] == hash;
        int oldBound = (int) (old >>> 22 & 3);
        if (!sameKey && oldBound != BOUND_NONE
                && (int) (old >>> 24 & 0xFF) == age
                && (int) (old >>> MOVE_BITS & 0xFF) > depth) {
            return;
        }
        int keepMove = move;
        if (move == Move.NONE && sameKey && oldBound != BOUND_NONE) {
            int m = (int) (old & MOVE_MASK);
            keepMove = m == 0 ? Move.NONE : m - 1;
        }
        long m = keepMove == Move.NONE ? 0 : keepMove + 1;
        slots[i] = hash;
        slots[i + 1] = m
                | ((long) Math.min(depth, 255) << MOVE_BITS)
                | ((long) bound << 22)
                | ((long) age << 24)
                | ((long) (score & 0xFFFF) << 32);
    }

    /** Fraction of slots in use, in permille (the UCI {@code hashfull} convention). */
    public int hashfull() {
        int sample = Math.min(1000, slots.length / 2);
        int used = 0;
        for (int i = 0; i < sample; i++) {
            if ((slots[2 * i + 1] >>> 22 & 3) != BOUND_NONE) {
                used++;
            }
        }
        return used * 1000 / sample;
    }
}
