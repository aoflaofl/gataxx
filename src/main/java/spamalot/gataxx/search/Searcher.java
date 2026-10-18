package spamalot.gataxx.search;

import java.util.Arrays;
import spamalot.gataxx.board.Bitboards;
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
    /** Default of {@link #setDeferMinDepth}. */
    public static final int DEFAULT_DEFER_MIN_DEPTH = 4;

    private final Evaluator evaluator;
    private final TranspositionTable tt;
    private final TranspositionTable.Entry ttEntry = new TranspositionTable.Entry();
    private final int[][] moveBuf = new int[MAX_PLY][Position.MAX_MOVES];
    private final int[][] scoreBuf = new int[MAX_PLY][Position.MAX_MOVES];
    /** Moves put off because another thread is searching them, and their original positions in the move order, per ply. */
    private int[][] deferredMoveBuf;
    private int[][] deferredIndexBuf;
    private InProgress inProgress;
    private int deferMinDepth = DEFAULT_DEFER_MIN_DEPTH;
    private final int[][] pv = new int[MAX_PLY][MAX_PLY];
    private final int[] pvLen = new int[MAX_PLY];

    private volatile boolean stopRequested;
    private boolean aborted;
    private long nodes;
    /** {@link #nodes} as of the last periodic check, readable from other threads. */
    private volatile long publishedNodes;
    private boolean helper;
    private int helperId;
    private long deadlineNanos;
    private long maxNodes;
    private int[] prevPv = new int[0];
    private boolean exactDepthHitsOnly;
    private int qMinCaptures;
    private int qMaxPly;
    private int qJumpMode = Position.JUMPS_ALL;
    private int jumpMode = Position.JUMPS_ALL;
    private int jumpCutMinEmpties;
    private int iirMinDepth;
    private int futilityReductionDiv;
    private boolean usePartialIterations;
    private boolean usePvs;
    private boolean useLmr;
    private int lmrFullMoves = 4;
    private int lmrMinDepth = 3;
    private int lmrDeepMoves;

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
     * Which jumps quiescence search tries: one of the {@code Position.JUMPS_} modes. A jump to a square a clone could also
     * reach converts the same pieces but leaves the origin empty, so cutting it is cheap and, in quiescence, nearly free.
     */
    public void setQuiescenceJumpMode(int mode) {
        checkJumpMode(mode);
        this.qJumpMode = mode;
    }

    /**
     * The same cuts in the main search, applied only while at least {@code minEmpties} squares are empty (near the end of a
     * game a jump can be the only way to avoid filling the board in a lost position).
     */
    public void setJumpMode(int mode, int minEmpties) {
        checkJumpMode(mode);
        if (minEmpties < 0) {
            throw new IllegalArgumentException("minEmpties must not be negative");
        }
        this.jumpMode = mode;
        this.jumpCutMinEmpties = minEmpties;
    }

    private static void checkJumpMode(int mode) {
        if (mode < Position.JUMPS_ALL || mode > Position.JUMPS_CUT_QUIET_DOMINATED) {
            throw new IllegalArgumentException("unknown jump mode " + mode);
        }
    }

    private int jumpModeFor(Position pos) {
        return jumpMode != Position.JUMPS_ALL && Long.bitCount(pos.empty()) >= jumpCutMinEmpties ? jumpMode : Position.JUMPS_ALL;
    }

    /**
     * Internal iterative reduction: a node of at least {@code minDepth} remaining plies with no move in the transposition
     * table (so nothing to order by) is searched one ply shallower; the cheaper search fills the table for the next visit.
     * 0 turns it off (the default).
     */
    public void setIir(int minDepth) {
        if (minDepth < 0) {
            throw new IllegalArgumentException("minDepth must not be negative");
        }
        this.iirMinDepth = minDepth;
    }

    /**
     * When the time runs out in the middle of an iteration, play the best move that iteration had completed instead of the
     * previous iteration's move. The first root move searched is the previous best, and a later move only replaces it after a
     * full-window re-search at the new depth, so the interrupted iteration's choice is never worse.
     */
    public void setPartialIterations(boolean enabled) {
        this.usePartialIterations = enabled;
    }

    /**
     * Graded futility reduction: from the second move on, a move whose child's static score (from our side) is below alpha is
     * searched {@code (alpha - score) / div} plies shallower, in addition to any late-move reduction; a move whose depth
     * runs out that way is not searched at all. A reduced search that nevertheless beats alpha is repeated at full depth.
     * 0 turns it off (the default).
     */
    public void setFutilityReduction(int div) {
        if (div < 0) {
            throw new IllegalArgumentException("div must not be negative");
        }
        this.futilityReductionDiv = div;
    }

    /**
     * Principal variation search: search every move after the first with a zero-width window and re-search
     * only when one turns out to beat the best so far. A pure efficiency change: the score of a completed
     * search is the same.
     */
    public void setPvs(boolean pvs) {
        this.usePvs = pvs;
    }

    /**
     * Late-move reductions: in a zero-window search, moves that the ordering ranks {@code fullMoves} or later are searched
     * one ply shallower; if such a move nevertheless beats the best score, it is searched again at full depth. A selective,
     * non-exact technique that bets the ordering puts the best move early. Needs {@link #setPvs PVS} to have any effect.
     *
     * @param fullMoves how many moves per node are always searched at full depth, at least 1
     * @param minDepth the smallest remaining depth at which reductions apply, at least 2
     */
    public void setLmr(boolean enabled, int fullMoves, int minDepth) {
        if (fullMoves < 1 || minDepth < 2) {
            throw new IllegalArgumentException("late-move-reduction parameters out of range");
        }
        this.useLmr = enabled;
        this.lmrFullMoves = fullMoves;
        this.lmrMinDepth = minDepth;
    }

    /**
     * Reduce very late moves by two plies instead of one: moves ranked {@code deepMoves} or later, when the remaining
     * depth is at least {@code minDepth + 1} (see {@link #setLmr}). Zero turns it off. Same caveats as {@link #setLmr}.
     */
    public void setLmrDeep(int deepMoves) {
        if (deepMoves < 0) {
            throw new IllegalArgumentException("deepMoves must not be negative");
        }
        this.lmrDeepMoves = deepMoves;
    }

    /**
     * Test hook: only accept table hits searched to exactly the depth now required. Then the result
     * is identical to an unpruned NegaMax, which lets tests check the bound logic exactly. Normal
     * play accepts deeper hits, which is stronger but not reproducible by a fixed-depth search.
     */
    void setExactDepthHitsOnly(boolean exact) {
        this.exactDepthHitsOnly = exact;
    }

    /**
     * Marks this searcher as helper number {@code id} (1, 2, ...) in a shared-table parallel search: it leaves the table's age
     * to the main searcher and skips some iterations of the iterative deepening, in a pattern that differs between helpers, so
     * that the threads work on different depths at the same moment and the main thread finds their results in the table.
     */
    void asHelper(int id) {
        this.helper = true;
        this.helperId = id;
    }

    /**
     * In a parallel search, a move whose position another thread is already searching is put off until the other moves have been
     * tried, at nodes with at least this much depth left ({@code 0} turns this off). It has no effect in a single-threaded search.
     */
    public void setDeferMinDepth(int depth) {
        this.deferMinDepth = depth;
    }

    /** Shares the table of positions being searched with the other threads of a parallel search. */
    void shareInProgress(InProgress shared) {
        this.inProgress = shared;
        if (shared != null && deferredMoveBuf == null) {
            deferredMoveBuf = new int[MAX_PLY][Position.MAX_MOVES];
            deferredIndexBuf = new int[MAX_PLY][Position.MAX_MOVES];
        }
    }

    // Skip pattern of the iterative-deepening helpers (the scheme popularised by Stockfish's Lazy SMP).
    private static final int[] SKIP_SIZE = {1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4};
    private static final int[] SKIP_PHASE = {0, 1, 0, 1, 2, 3, 0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 5, 6, 7};

    private boolean skipsDepth(int depth) {
        int i = (helperId - 1) % SKIP_SIZE.length;
        return ((depth + SKIP_PHASE[i]) / SKIP_SIZE[i]) % 2 != 0;
    }

    /** Nodes searched as of the last periodic check; safe to read from another thread while the search runs. */
    public long publishedNodes() {
        return publishedNodes;
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

        if (tt != null && !helper) {
            tt.newSearch();
        }
        int[] rootMoves = moveBuf[0];
        int n = root.generateMoves(rootMoves, Position.JUMPS_ALL);
        if (n == 0) {
            return new SearchResult(Move.NONE, terminalScore(root, 0), 0, 0, 0, new int[0]);
        }
        orderMoves(root, rootMoves, scoreBuf[0], n, Move.NONE, Move.NONE);
        SearchResult best = new SearchResult(rootMoves[bestScoredIndex(scoreBuf[0], n)], 0, 0, 0, 0, new int[0]);

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (shouldAbort()) {
                break;
            }
            if (helper && skipsDepth(depth)) {
                continue;
            }
            int score = negamax(root, depth, -INF, INF, 0, true);
            if (aborted) {
                if (usePartialIterations && pvLen[0] > 0 && depth > 1) {
                    // The root move that was best when time ran out had its search at this depth completed.
                    best = new SearchResult(pv[0][0], best.score(), best.depth(), nodes, best.timeMs(),
                            Arrays.copyOf(pv[0], pvLen[0]));
                }
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
        if ((nodes & NODE_CHECK_MASK) == 0) {
            publishedNodes = nodes;
            if (shouldAbort()) {
                aborted = true;
            }
        }
        if (aborted) {
            return 0;
        }
        if (depth == 0) {
            return pos.isGameOver() ? terminalScore(pos, ply) : evaluator.evaluate(pos);
        }
        final int alphaAtEntry = alpha;

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

        if (iirMinDepth > 0 && depth >= iirMinDepth && ttMove == Move.NONE) {
            depth--;
        }

        int[] moves = moveBuf[ply];
        int n = pos.generateMoves(moves, jumpModeFor(pos));
        if (n == 0) {
            return terminalScore(pos, ply);
        }
        int[] scores = scoreBuf[ply];
        int hint = onPv && ply < prevPv.length ? prevPv[ply] : Move.NONE;
        orderMoves(pos, moves, scores, n, hint, ttMove);

        int originalAlpha = alphaAtEntry;
        int bestMove = Move.NONE;
        boolean canDefer = inProgress != null && deferMinDepth > 0 && depth >= deferMinDepth && n > 1;
        int[] deferredMoves = canDefer ? deferredMoveBuf[ply] : null;
        int[] deferredIndex = canDefer ? deferredIndexBuf[ply] : null;
        int deferred = 0;
        for (int step = 0; step < n + deferred; step++) {
            int i;
            int move;
            boolean fromDeferred = step >= n;
            if (!fromDeferred) {
                // Lazy selection sort: only pays for ordering as far as we get before a cutoff.
                swap(moves, scores, step, bestIndex(scores, step, n));
                i = step;
                move = moves[step];
            } else {
                i = deferredIndex[step - n];
                move = deferredMoves[step - n];
            }
            Position child = pos.makeMove(move);
            long childHash = 0;
            if (canDefer && move != Move.PASS) {
                childHash = child.hash();
                if (!fromDeferred && i > 0 && inProgress.contains(childHash)) {
                    // Another thread is searching this move right now: try the others first.
                    deferredMoves[deferred] = move;
                    deferredIndex[deferred] = i;
                    deferred++;
                    continue;
                }
                inProgress.enter(childHash);
            }
            int score;
            if (usePvs && i > 0) {
                boolean reduce = useLmr && i >= lmrFullMoves && depth >= lmrMinDepth;
                int reduction = 0;
                if (reduce) {
                    reduction = lmrDeepMoves > 0 && i >= lmrDeepMoves && depth >= lmrMinDepth + 1 ? 2 : 1;
                }
                if (futilityReductionDiv > 0 && move != Move.PASS && alpha > -(WIN - MAX_PLY) && alpha < WIN - MAX_PLY
                        && !child.isGameOver()) {
                    int childEval = evaluator.evaluate(child, -alpha - 1, -alpha);
                    int ours = -childEval;
                    if (ours < alpha) {
                        int extra = (alpha - ours) / futilityReductionDiv;
                        if (extra > 0) {
                            if (depth - 1 - reduction - extra <= 0) {
                                if (childHash != 0) {
                                    inProgress.leave(childHash);
                                }
                                continue;
                            }
                            reduction += extra;
                            reduce = true;
                        }
                    }
                }
                int searchDepth = Math.max(0, depth - 1 - reduction);
                score = -negamax(child, searchDepth, -alpha - 1, -alpha, ply + 1, false);
                if (!aborted && reduce && score > alpha) {
                    score = -negamax(child, depth - 1, -alpha - 1, -alpha, ply + 1, false);
                }
                if (!aborted && score > alpha && score < beta) {
                    score = -negamax(child, depth - 1, -beta, -alpha, ply + 1, false);
                }
            } else {
                score = -negamax(child, depth - 1, -beta, -alpha, ply + 1, onPv && move == hint);
            }
            if (childHash != 0) {
                inProgress.leave(childHash);
            }
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
        if ((nodes & NODE_CHECK_MASK) == 0) {
            publishedNodes = nodes;
            if (shouldAbort()) {
                aborted = true;
            }
        }
        if (aborted) {
            return 0;
        }
        if (pos.isGameOver()) {
            return terminalScore(pos, ply);
        }
        int standPat = evaluator.evaluate(pos, alpha, beta);
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
        int k = pos.generateScoredCaptureMoves(moves, scores, qMinCaptures, qJumpMode);
        for (int i = 0; i < k; i++) {
            // Keys hold score and reversed index; the maximum (a vectorisable int reduction) is the first best-scoring move.
            int best = scores[i];
            for (int j = i + 1; j < k; j++) {
                best = Math.max(best, scores[j]);
            }
            int bi = Position.KEY_INDEX_MASK - (best & Position.KEY_INDEX_MASK);
            int moved = moves[i];
            moves[i] = moves[bi];
            moves[bi] = moved;
            scores[bi] = (scores[i] & ~Position.KEY_INDEX_MASK) | (Position.KEY_INDEX_MASK - bi);
            int score = -quiesce(pos.makeMoveUnhashed(moves[i]), -beta, -alpha, ply + 1, qply + 1);
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

    /** One step of material swing in the ordering score; the tie-breakers below are fractions of it. */
    private static final int SWING_STEP = 1024;
    /** Ordering bonus for landing on the outer ring (as the evaluation rewards edge pieces); tuned with `bench`. */
    private static final int EDGE_BONUS = 1536;

    private static final long EDGE_MASK;

    static {
        long m = 0;
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            int f = sq % Bitboards.SIZE;
            int r = sq / Bitboards.SIZE;
            if (f == 0 || f == Bitboards.SIZE - 1 || r == 0 || r == Bitboards.SIZE - 1) {
                m |= 1L << sq;
            }
        }
        EDGE_MASK = m;
    }

    /**
     * Scores moves for ordering: the PV move, then the table move, then by material swing (clones first),
     * with landing on the edge as a tie-breaker (the bonus is smaller than one swing step, so it only
     * reorders moves with equal swing).
     */
    private void orderMoves(Position pos, int[] moves, int[] scores, int n, int pvMove, int ttMove) {
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (m == pvMove) {
                scores[i] = Integer.MAX_VALUE;
            } else if (m == ttMove) {
                scores[i] = Integer.MAX_VALUE - 1;
            } else if (m == Move.PASS) {
                scores[i] = 0;
            } else {
                int clone = Move.isClone(m) ? 1 : 0;
                int to = Move.to(m);
                int s = ((pos.captureCount(m) + clone) * 2 + clone) * SWING_STEP;
                if ((EDGE_MASK >>> to & 1) != 0) {
                    s += EDGE_BONUS;
                }
                scores[i] = s;
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

    /**
     * Index in {@code [from, to)} of the first highest score. Packs each score with its index so that the maximum is found
     * without a data-dependent branch (this scan is hot and its comparison is hard to predict). Needs {@code to <= 1024}.
     */
    private static int bestIndex(int[] scores, int from, int to) {
        long best = ((long) scores[from] << 10) | (1023 - from);
        for (int j = from + 1; j < to; j++) {
            best = Math.max(best, ((long) scores[j] << 10) | (1023 - j));
        }
        return 1023 - (int) (best & 1023);
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
