package spamalot.gataxx.search;

/**
 * Outcome of a completed search iteration.
 *
 * @param bestMove best move found, or {@code Move.NONE} if the game is already over
 * @param score score for the side to move; see {@link Searcher#WIN}
 * @param depth depth of the last completed iteration (0 if none completed)
 * @param nodes nodes visited so far
 * @param timeMs elapsed milliseconds
 * @param pv principal variation, starting with {@code bestMove}
 */
public record SearchResult(int bestMove, int score, int depth, long nodes, long timeMs, int[] pv) {}
