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
        return forClock(timeLeftMs, incrementMs, movesToGo, maxDepth, -1, 0, 40, 30);
    }

    /**
     * @param empties empty squares on the board, or -1 if unknown
     * @param model 0: a fixed number of moves left; 1: moves left estimated from the empty squares
     * @param softPercent percent of the budget after which no new iteration is started
     * @param hardTenths the hard limit as a multiple (in tenths) of the budget
     */
    public static SearchLimits forClock(long timeLeftMs, long incrementMs, int movesToGo, int maxDepth, int empties,
                                        int model, int softPercent, int hardTenths) {
        long usable = Math.max(1, timeLeftMs - OVERHEAD_MS);
        int moves = movesToGo > 0 ? movesToGo : DEFAULT_MOVES_TO_GO;
        if (model == 1 && movesToGo <= 0 && empties >= 0) {
            moves = Math.max(8, Math.min(40, (int) Math.round(0.55 * empties + 6)));
        }
        long budget = usable / moves + incrementMs * 3 / 4;
        long hard = Math.max(1, Math.min(budget * hardTenths / 10, usable / 2));
        budget = Math.min(budget, hard);
        // The next iteration typically costs several times the last one, so stop starting
        // new ones well before the budget is used up.
        long soft = Math.max(1, budget * softPercent / 100);
        return new SearchLimits(maxDepth, soft, hard, 0);
    }
}
