package spamalot.gataxx.search;

/** Turns game-clock information into {@link SearchLimits}. */
public final class TimeManager {
    /** Assumed number of our moves left in the game when the GUI doesn't say. */
    static final int DEFAULT_MOVES_TO_GO = 30;

    /** Milliseconds always kept in reserve for I/O and scheduling latency. */
    static final long OVERHEAD_MS = 50;

    private TimeManager() {}

    /**
     * @param timeLeftMs our remaining clock time
     * @param incrementMs our increment per move
     * @param movesToGo moves until the next time control, or 0 if unknown
     * @param maxDepth depth cap, or 0 for none
     */
    public static SearchLimits forClock(long timeLeftMs, long incrementMs, int movesToGo, int maxDepth) {
        long usable = Math.max(1, timeLeftMs - OVERHEAD_MS);
        int moves = movesToGo > 0 ? movesToGo : DEFAULT_MOVES_TO_GO;
        long budget = usable / moves + incrementMs * 3 / 4;
        long hard = Math.max(1, Math.min(budget * 3, usable / 2));
        budget = Math.min(budget, hard);
        // The next iteration typically costs several times the last one, so stop starting
        // new ones well before the budget is used up.
        long soft = Math.max(1, budget * 2 / 5);
        return new SearchLimits(maxDepth, soft, hard, 0);
    }
}
