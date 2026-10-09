package spamalot.gataxx.search;

import java.util.Arrays;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;

/**
 * Iterative-deepening NegaMax search with alpha-beta pruning.
 *
 * <p>A {@code Searcher} is single-use: create one per search. {@link #stop()} may be called from
 * any thread, including before {@link #search} has started, in which case the search returns
 * immediately with a fallback move.
 */
public final class Searcher {
    /** Score of a win at the root; a win in {@code n} plies scores {@code WIN - n}. */
    public static final int WIN = 10_000;

    public static final int MAX_DEPTH = 60;

    /** Largest quiescence extension accepted by {@link #setQuiescence}. */
    public static final int MAX_QUIESCENCE_PLY = 16;

    private static final int MAX_PLY = MAX_DEPTH + MAX_QUIESCENCE_PLY + 4;
    private static final int INF = 30_000;
    private static final int NODE_CHECK_MASK = 1023;

    private final Evaluator evaluator;
    private final TranspositionTable tt;
    private final TranspositionTable.Entry ttEntry = new TranspositionTable.Entry();
    private final int[][] moveBuf = new int[MAX_PLY][Position.MAX_MOVES];
    private final int[][] scoreBuf = new int[MAX_PLY][Position.MAX_MOVES];
    private final int[][] pv = new int[MAX_PLY][MAX_PLY];
    private final int[] pvLen = new int[MAX_PLY];

    private volatile boolean stopRequested;
    private boolean aborted;
    private long nodes;
    private long deadlineNanos;
    private long maxNodes;
    private int[] prevPv = new int[0];
    private boolean exactDepthHitsOnly;
    private int qMinCaptures;
    private int qMaxPly;

    /** A searcher without a transposition table. */
    public Searcher(Evaluator evaluator) {
        this(evaluator, null);
    }

    /**
     * @param tt shared across searches so later moves benefit from earlier work; may be null. The
     *     caller must not use it from another thread while this searcher runs.
     */
    public Searcher(Evaluator evaluator, TranspositionTable tt) {
        this.evaluator = evaluator;
        this.tt = tt;
    }

    /**
     * Enables quiescence search: at the horizon, instead of evaluating at once, keep searching moves
     * that convert at least {@code minCaptures} enemy pieces, so a pending big capture is not
     * missed. The side to move may always "stand pat" on the static score, which assumes it has some
     * quiet move that doesn't lose material.
     *
     * @param minCaptures capture threshold for a move to count as noisy; 0 disables quiescence
     * @param maxPly most extra plies searched beyond the horizon, at most {@link #MAX_QUIESCENCE_PLY}
     */
    public void setQuiescence(int minCaptures, int maxPly) {
        if (minCaptures < 0 || maxPly < 0 || maxPly > MAX_QUIESCENCE_PLY) {
            throw new IllegalArgumentException("quiescence parameters out of range");
        }
        this.qMinCaptures = minCaptures;
        this.qMaxPly = maxPly;
    }

    /**
     * Test hook: only accept table hits searched to exactly the depth now required. Then the result
     * is identical to an unpruned NegaMax, which lets tests check the bound logic exactly. Normal
     * play accepts deeper hits, which is stronger but not reproducible by a fixed-depth search.
     */
    void setExactDepthHitsOnly(boolean exact) {
        this.exactDepthHitsOnly = exact;
    }

    /** Asks the running (or about-to-run) search to finish as soon as possible. */
    public void stop() {
        stopRequested = true;
    }

    /** True for scores that encode a forced win or loss rather than a material evaluation. */
    public static boolean isMateScore(int score) {
        return Math.abs(score) >= WIN - MAX_PLY;
    }

    /** Score, for the side to move, of a position where the game is over. */
    static int terminalScore(Position pos, int ply) {
        int diff = pos.count(pos.sideToMove()) - pos.count(1 - pos.sideToMove());
        return diff > 0 ? WIN - ply : diff < 0 ? -(WIN - ply) : 0;
    }

    public SearchResult search(Position root, SearchLimits limits, SearchListener listener) {
        long start = System.nanoTime();
        deadlineNanos = limits.hardMs() > 0 ? start + limits.hardMs() * 1_000_000L : Long.MAX_VALUE;
        maxNodes = limits.maxNodes();
        long softNanos = limits.softMs() > 0 ? limits.softMs() * 1_000_000L : Long.MAX_VALUE;
        int maxDepth = limits.maxDepth() > 0 ? Math.min(limits.maxDepth(), MAX_DEPTH) : MAX_DEPTH;

        if (tt != null) {
            tt.newSearch();
        }
        int[] rootMoves = moveBuf[0];
        int n = root.generateMoves(rootMoves);
        if (n == 0) {
            return new SearchResult(Move.NONE, terminalScore(root, 0), 0, 0, 0, new int[0]);
        }
        orderMoves(root, rootMoves, scoreBuf[0], n, Move.NONE, Move.NONE);
        SearchResult best = new SearchResult(rootMoves[bestScoredIndex(scoreBuf[0], n)], 0, 0, 0, 0, new int[0]);

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (shouldAbort()) {
                break;
            }
            int score = negamax(root, depth, -INF, INF, 0, true);
            if (aborted) {
                break;
            }
            long elapsed = System.nanoTime() - start;
            best = new SearchResult(pv[0][0], score, depth, nodes, elapsed / 1_000_000L, Arrays.copyOf(pv[0], pvLen[0]));
            prevPv = best.pv();
            if (listener != null) {
                listener.onIteration(best);
            }
            if (Math.abs(score) >= WIN - MAX_PLY || elapsed >= softNanos) {
                break;
            }
        }
        return new SearchResult(best.bestMove(), best.score(), best.depth(), nodes,
                (System.nanoTime() - start) / 1_000_000L, best.pv());
    }

    private boolean shouldAbort() {
        return stopRequested
                || System.nanoTime() >= deadlineNanos
                || (maxNodes > 0 && nodes >= maxNodes);
    }

    private int negamax(Position pos, int depth, int alpha, int beta, int ply, boolean onPv) {
        if (depth == 0 && qMinCaptures > 0) {
            return quiesce(pos, alpha, beta, ply, 0);
        }
        pvLen[ply] = 0;
        nodes++;
        if ((nodes & NODE_CHECK_MASK) == 0 && shouldAbort()) {
            aborted = true;
        }
        if (aborted) {
            return 0;
        }
        if (depth == 0) {
            return pos.isGameOver() ? terminalScore(pos, ply) : evaluator.evaluate(pos);
        }

        // The 100-half-move rule makes a result depend on the clock, which the hash ignores. Only
        // use the table where the subtree cannot reach the limit.
        boolean useTt = tt != null && pos.halfmoveClock() + depth < Position.HALFMOVE_LIMIT;
        int ttMove = Move.NONE;
        long hash = pos.hash();
        if (useTt) {
            tt.probe(hash, ttEntry);
            if (ttEntry.found) {
                ttMove = ttEntry.move;
                boolean deepEnough = exactDepthHitsOnly ? ttEntry.depth == depth : ttEntry.depth >= depth;
                if (ply > 0 && deepEnough) {
                    int s = scoreFromTable(ttEntry.score, ply);
                    switch (ttEntry.bound) {
                        case TranspositionTable.BOUND_EXACT -> {
                            return Math.max(alpha, Math.min(beta, s));
                        }
                        case TranspositionTable.BOUND_LOWER -> {
                            if (s >= beta) {
                                return beta;
                            }
                        }
                        case TranspositionTable.BOUND_UPPER -> {
                            if (s <= alpha) {
                                return alpha;
                            }
                        }
                        default -> { }
                    }
                }
            }
        }

        int[] moves = moveBuf[ply];
        int n = pos.generateMoves(moves);
        if (n == 0) {
            return terminalScore(pos, ply);
        }
        int[] scores = scoreBuf[ply];
        int hint = onPv && ply < prevPv.length ? prevPv[ply] : Move.NONE;
        orderMoves(pos, moves, scores, n, hint, ttMove);

        int originalAlpha = alpha;
        int bestMove = Move.NONE;
        for (int i = 0; i < n; i++) {
            // Lazy selection sort: only pays for ordering as far as we get before a cutoff.
            int bi = i;
            for (int j = i + 1; j < n; j++) {
                if (scores[j] > scores[bi]) {
                    bi = j;
                }
            }
            swap(moves, scores, i, bi);

            int move = moves[i];
            int score = -negamax(pos.makeMove(move), depth - 1, -beta, -alpha, ply + 1, onPv && move == hint);
            if (aborted) {
                return 0;
            }
            if (score >= beta) {
                if (useTt) {
                    tt.store(hash, depth, TranspositionTable.BOUND_LOWER, scoreToTable(beta, ply), move);
                }
                return beta;
            }
            if (score > alpha) {
                alpha = score;
                bestMove = move;
                pv[ply][0] = move;
                System.arraycopy(pv[ply + 1], 0, pv[ply], 1, pvLen[ply + 1]);
                pvLen[ply] = pvLen[ply + 1] + 1;
            }
        }
        if (useTt) {
            int bound = alpha > originalAlpha ? TranspositionTable.BOUND_EXACT : TranspositionTable.BOUND_UPPER;
            tt.store(hash, depth, bound, scoreToTable(alpha, ply), bestMove);
        }
        return alpha;
    }

    /** Fail-hard alpha-beta over noisy moves only, with the static score as a lower bound (stand pat). */
    private int quiesce(Position pos, int alpha, int beta, int ply, int qply) {
        pvLen[ply] = 0;
        nodes++;
        if ((nodes & NODE_CHECK_MASK) == 0 && shouldAbort()) {
            aborted = true;
        }
        if (aborted) {
            return 0;
        }
        if (pos.isGameOver()) {
            return terminalScore(pos, ply);
        }
        int standPat = evaluator.evaluate(pos);
        if (standPat >= beta) {
            return beta;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }
        if (qply >= qMaxPly) {
            return alpha;
        }

        int[] moves = moveBuf[ply];
        int[] scores = scoreBuf[ply];
        int n = pos.generateMoves(moves);
        int k = 0;
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (m == Move.PASS) {
                continue;
            }
            int captures = pos.captureCount(m);
            if (captures >= qMinCaptures) {
                moves[k] = m;
                scores[k] = captures * 2 + (Move.isClone(m) ? 1 : 0);
                k++;
            }
        }
        for (int i = 0; i < k; i++) {
            int bi = i;
            for (int j = i + 1; j < k; j++) {
                if (scores[j] > scores[bi]) {
                    bi = j;
                }
            }
            swap(moves, scores, i, bi);
            int score = -quiesce(pos.makeMove(moves[i]), -beta, -alpha, ply + 1, qply + 1);
            if (aborted) {
                return 0;
            }
            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    /** Mate scores are relative to the root; in the table they must be relative to the node. */
    private static int scoreToTable(int score, int ply) {
        if (score >= WIN - MAX_PLY) {
            return score + ply;
        }
        if (score <= -(WIN - MAX_PLY)) {
            return score - ply;
        }
        return score;
    }

    private static int scoreFromTable(int score, int ply) {
        if (score >= WIN - MAX_PLY) {
            return score - ply;
        }
        if (score <= -(WIN - MAX_PLY)) {
            return score + ply;
        }
        return score;
    }

    /** Scores moves for ordering: the PV move, then the table move, then by material swing (clones first). */
    private static void orderMoves(Position pos, int[] moves, int[] scores, int n, int pvMove, int ttMove) {
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (m == pvMove) {
                scores[i] = Integer.MAX_VALUE;
            } else if (m == ttMove) {
                scores[i] = Integer.MAX_VALUE - 1;
            } else {
                int clone = Move.isClone(m) ? 1 : 0;
                scores[i] = (pos.captureCount(m) + clone) * 2 + clone;
            }
        }
    }

    private static int bestScoredIndex(int[] scores, int n) {
        int bi = 0;
        for (int i = 1; i < n; i++) {
            if (scores[i] > scores[bi]) {
                bi = i;
            }
        }
        return bi;
    }

    private static void swap(int[] moves, int[] scores, int a, int b) {
        if (a == b) {
            return;
        }
        int m = moves[a];
        moves[a] = moves[b];
        moves[b] = m;
        int s = scores[a];
        scores[a] = scores[b];
        scores[b] = s;
    }
}
