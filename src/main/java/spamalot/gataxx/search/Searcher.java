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
    private int qJumpCutFromPly;
    private int jumpMode = Position.JUMPS_ALL;
    private int jumpCutMinEmpties;
    private int jumpNearMode = Position.JUMPS_ALL;
    private int iirMinDepth;
    private int futilityReductionDiv;
    private boolean futilityKeepZero;
    private boolean ttNarrow;
    private int historyWeight;
    private int historyPenaltyDiv = 24;
    private boolean orderEdge = true;
    private final float[][] history = new float[2][49 * 49];
    private int lmrHalfLife;
    private int lmrFormulaMinMove = 7;
    private int lmrFormulaSub;
    private int nullStyle;
    private int nullDepthSub = 4;
    private int nullDepthDiv = 2;
    private int nullMinTargets = 11;
    private boolean usePartialIterations;
    private int jumpNearDepth;
    private boolean usePvs;
    private boolean useFutility;
    private int futilityMargin = 48;
    private int futilityMaxDepth = 2;
    private int futilityUnitsPerPiece = 1;
    private boolean useLmr;
    private int lmrFullMoves = 4;
    private int lmrMinDepth = 3;
    private int lmrDeepMoves;
    private boolean useNullMove;
    private int nullReduction = 2;
    private int nullMinEmpties = 12;

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
        setQuiescenceJumpMode(mode, 0);
    }

    /** As {@link #setQuiescenceJumpMode(int)}, but the cut applies only from quiescence ply {@code fromPly} (0 = all). */
    public void setQuiescenceJumpMode(int mode, int fromPly) {
        checkJumpMode(mode);
        this.qJumpMode = mode;
        this.qJumpCutFromPly = Math.max(0, fromPly);
    }

    /**
     * The same cuts in the main search, applied only while at least {@code minEmpties} squares are empty (near the end of a
     * game a jump can be the only way to avoid filling the board in a lost position).
     */
    public void setJumpMode(int mode, int minEmpties) {
        setJumpMode(mode, minEmpties, mode, 0);
    }

    /**
     * As {@link #setJumpMode(int, int)}, with a different mode, {@code nearMode}, at nodes that have at most
     * {@code nearDepth} plies left to search (0 = none): near the leaves a quiescence search follows and the cut is cheaper.
     */
    public void setJumpMode(int mode, int minEmpties, int nearMode, int nearDepth) {
        checkJumpMode(mode);
        checkJumpMode(nearMode);
        if (minEmpties < 0 || nearDepth < 0) {
            throw new IllegalArgumentException("minEmpties and nearDepth must not be negative");
        }
        this.jumpMode = mode;
        this.jumpCutMinEmpties = minEmpties;
        this.jumpNearMode = nearMode;
        this.jumpNearDepth = nearDepth;
    }

    private static void checkJumpMode(int mode) {
        if (mode < Position.JUMPS_ALL || mode > Position.JUMPS_CUT_QUIET_DOMINATED) {
            throw new IllegalArgumentException("unknown jump mode " + mode);
        }
    }

    private int jumpModeFor(Position pos, int depth) {
        int mode = jumpNearDepth > 0 && depth <= jumpNearDepth ? jumpNearMode : jumpMode;
        return mode != Position.JUMPS_ALL && Long.bitCount(pos.empty()) >= jumpCutMinEmpties ? mode : Position.JUMPS_ALL;
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
     * Late-move reduction by formula: from move number {@code minMove} (1-based, the table move included) on, a move is
     * searched at depth {@code floor(depth * halfLife / (halfLife + k)) - sub}, where {@code k} is the number of earlier moves
     * that were eligible; the reduction therefore grows gently with both depth and move number. 0 keeps the fixed-step rule.
     */
    public void setLmrFormula(int halfLife, int minMove, int sub) {
        if (halfLife < 0 || minMove < 1 || sub < 0) {
            throw new IllegalArgumentException("late-move-reduction formula parameters out of range");
        }
        this.lmrHalfLife = halfLife;
        this.lmrFormulaMinMove = minMove;
        this.lmrFormulaSub = sub;
    }

    /**
     * With graded futility reduction, skip a move only when its remaining depth reaches zero (a child that would be searched
     * at depth zero still gets its quiescence search) instead of whenever the child would be searched at depth zero.
     */
    public void setFutilityKeepZero(boolean keep) {
        this.futilityKeepZero = keep;
    }

    /**
     * Null-move style 1: at any non-root node whose table entry is missing or a lower bound, and where the side to move can
     * reach at least {@code minTargets} distinct empty squares, pass and search at depth
     * {@code max(0, depth - depthSub - depth / depthDiv)}; a score of at least beta cuts the node. No static-score gate and
     * no verification. Needs {@link #setNullMove} enabled.
     */
    public void setNullStyle(int style, int depthSub, int depthDiv, int minTargets) {
        if (style < 0 || style > 1 || depthSub < 0 || depthDiv < 1 || minTargets < 0) {
            throw new IllegalArgumentException("null-move style parameters out of range");
        }
        this.nullStyle = style;
        this.nullDepthSub = depthSub;
        this.nullDepthDiv = depthDiv;
        this.nullMinTargets = minTargets;
    }

    /**
     * Use bounds from the transposition table to narrow the window of a node instead of only cutting off: a deep enough lower
     * bound raises alpha and an upper bound lowers beta. Default off.
     */
    public void setTtNarrow(boolean narrow) {
        this.ttNarrow = narrow;
    }

    /**
     * History ordering after Moonbird's rules: a table by colour, source and destination square (a clone has source equal to
     * destination). The move that cuts off, or the final best move of a node that raised alpha, gains {@code depth^2}; each
     * searched move that did not raise alpha loses {@code depth^2 / penaltyDiv}; entries are clamped at +-107000 and every entry of
     * the colour is divided by 1.25 when one hits the clamp. In the ordering it adds at most {@code weightTenths / 10} swing
     * steps. 0 turns it off (the default).
     */
    public void setHistory(int weightTenths, int penaltyDiv) {
        if (weightTenths < 0 || penaltyDiv < 1) {
            throw new IllegalArgumentException("history parameters out of range");
        }
        this.historyWeight = weightTenths;
        this.historyPenaltyDiv = penaltyDiv;
    }

    /** Whether the ordering gives a bonus for landing on the edge (default true). */
    public void setOrderEdgeBonus(boolean on) {
        this.orderEdge = on;
    }

    private static final float HISTORY_LIMIT = 107_000f;

    private void historyAdd(int side, int move, float delta) {
        int from = Move.isClone(move) ? Move.to(move) : Move.from(move);
        float[] h = history[side];
        int idx = from * 49 + Move.to(move);
        float v = h[idx] + delta;
        if (v >= HISTORY_LIMIT || v <= -HISTORY_LIMIT) {
            v = v > 0 ? HISTORY_LIMIT : -HISTORY_LIMIT;
            h[idx] = v;
            for (int i = 0; i < h.length; i++) {
                h[i] /= 1.25f;
            }
        } else {
            h[idx] = v;
        }
    }

    private float historyOf(int side, int move) {
        int from = Move.isClone(move) ? Move.to(move) : Move.from(move);
        return history[side][from * 49 + Move.to(move)];
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
     * Futility pruning: in a zero-window node with at most {@code maxDepth} plies left, a move is skipped without being
     * searched if the static score, plus the most the move itself can win, plus {@code margin} per remaining ply, still
     * cannot reach alpha. A move wins {@code 1} piece for a clone plus {@code 2} per piece it converts. Selective and
     * not exact (the opponent's reply, covered only by the margin, may change the balance); needs {@link #setPvs PVS}.
     *
     * @param margin safety margin in evaluation units per remaining ply, at least 0
     * @param maxDepth deepest remaining depth at which it applies, at least 1
     * @param unitsPerPiece evaluation units per piece (for example {@code FeatureEvaluator.SCALE}), at least 1
     */
    public void setFutility(boolean enabled, int margin, int maxDepth, int unitsPerPiece) {
        if (margin < 0 || maxDepth < 1 || unitsPerPiece < 1) {
            throw new IllegalArgumentException("futility parameters out of range");
        }
        this.useFutility = enabled;
        this.futilityMargin = margin;
        this.futilityMaxDepth = maxDepth;
        this.futilityUnitsPerPiece = unitsPerPiece;
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
     * Null-move pruning: at a zero-window node whose static score already reaches beta, let the opponent move twice
     * (pass our turn) and search that to a reduced depth; if even then the score reaches beta, the node is pruned.
     * It is a selective, non-exact technique that assumes passing is never better than moving ("no zugzwang").
     * In Ataxx the mover can almost always clone to gain material, so that holds until the late endgame, where jumps
     * can open holes: the pruning is therefore skipped when fewer than {@code minEmpties} squares are empty. Only
     * zero-window nodes are pruned, so it needs {@link #setPvs PVS} to have any effect.
     *
     * @param reduction extra depth removed from the pass search (the usual "R"), at least 1
     */
    public void setNullMove(boolean enabled, int reduction, int minEmpties) {
        if (reduction < 1 || minEmpties < 0) {
            throw new IllegalArgumentException("null-move parameters out of range");
        }
        this.useNullMove = enabled;
        this.nullReduction = reduction;
        this.nullMinEmpties = minEmpties;
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
            int score = negamax(root, depth, -INF, INF, 0, true, true);
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

    private int negamax(Position pos, int depth, int alpha, int beta, int ply, boolean onPv, boolean nullAllowed) {
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
        boolean ttMissingOrLower = true;
        if (useTt) {
            tt.probe(hash, ttEntry);
            ttMissingOrLower = !ttEntry.found || ttEntry.bound == TranspositionTable.BOUND_LOWER;
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
                            if (ttNarrow && s > alpha) {
                                alpha = s;
                            }
                        }
                        case TranspositionTable.BOUND_UPPER -> {
                            if (s <= alpha) {
                                return alpha;
                            }
                            if (ttNarrow && s < beta) {
                                beta = s;
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

        if (useNullMove && nullStyle == 1 && ply > 0 && ttMissingOrLower
                && beta < WIN - MAX_PLY && beta > -(WIN - MAX_PLY)
                && !pos.isGameOver() && pos.hasMove(pos.sideToMove())
                && Long.bitCount(Bitboards.expand2(pos.pieces(pos.sideToMove())) & pos.empty()) >= nullMinTargets) {
            int childDepth = Math.max(0, depth - nullDepthSub - depth / nullDepthDiv);
            int s = -negamax(pos.makeMove(Move.PASS), childDepth, -beta, -beta + 1, ply + 1, false, true);
            if (aborted) {
                return 0;
            }
            if (s >= beta) {
                return beta;
            }
        }

        if (useNullMove && nullStyle == 0 && nullAllowed && !onPv && beta - alpha == 1 && depth > nullReduction
                && beta < WIN - MAX_PLY && beta > -(WIN - MAX_PLY)
                && !pos.isGameOver() && pos.hasMove(pos.sideToMove())
                && Long.bitCount(pos.empty()) >= nullMinEmpties
                && evaluator.evaluate(pos) >= beta) {
            int s = -negamax(pos.makeMove(Move.PASS), depth - 1 - nullReduction, -beta, -beta + 1, ply + 1, false, false);
            if (aborted) {
                return 0;
            }
            if (s >= beta) {
                return beta;
            }
        }

        int[] moves = moveBuf[ply];
        int n = pos.generateMoves(moves, jumpModeFor(pos, depth));
        if (n == 0) {
            return terminalScore(pos, ply);
        }
        int[] scores = scoreBuf[ply];
        int hint = onPv && ply < prevPv.length ? prevPv[ply] : Move.NONE;
        orderMoves(pos, moves, scores, n, hint, ttMove);

        boolean futile = useFutility && !onPv && beta - alpha == 1 && depth <= futilityMaxDepth && ply > 0
                && alpha > -(WIN - MAX_PLY) && alpha < WIN - MAX_PLY && !pos.isGameOver();
        int staticScore = futile ? evaluator.evaluate(pos) : 0;
        int originalAlpha = alphaAtEntry;
        int bestMove = Move.NONE;
        for (int i = 0; i < n; i++) {
            // Lazy selection sort: only pays for ordering as far as we get before a cutoff.
            swap(moves, scores, i, bestIndex(scores, i, n));

            int move = moves[i];
            if (futile && i > 0 && move != Move.PASS) {
                int gain = (pos.captureCount(move) * 2 + (Move.isClone(move) ? 1 : 0)) * futilityUnitsPerPiece;
                if (staticScore + gain + futilityMargin * depth <= alpha) {
                    continue;
                }
            }
            Position child = pos.makeMove(move);
            int score;
            if (usePvs && i > 0) {
                boolean reduce;
                int reduction = 0;
                if (lmrHalfLife > 0) {
                    int moveCounter = i + 1;
                    if (moveCounter >= lmrFormulaMinMove) {
                        int k = moveCounter - lmrFormulaMinMove;
                        int lmrDepth = Math.max(0, depth * lmrHalfLife / (lmrHalfLife + k) - lmrFormulaSub);
                        reduction = depth - lmrDepth;
                    }
                    reduce = reduction > 0;
                } else {
                    reduce = useLmr && i >= lmrFullMoves && depth >= lmrMinDepth;
                    if (reduce) {
                        reduction = lmrDeepMoves > 0 && i >= lmrDeepMoves && depth >= lmrMinDepth + 1 ? 2 : 1;
                    }
                }
                if (futilityReductionDiv > 0 && move != Move.PASS && alpha > -(WIN - MAX_PLY) && alpha < WIN - MAX_PLY
                        && !child.isGameOver()) {
                    int ours = -evaluator.evaluate(child, -alpha - 1, -alpha);
                    if (ours < alpha) {
                        int extra = (alpha - ours) / futilityReductionDiv;
                        if (extra > 0) {
                            if (futilityKeepZero ? depth - reduction - extra <= 0 : depth - 1 - reduction - extra <= 0) {
                                continue;
                            }
                            reduction += extra;
                            reduce = true;
                        }
                    }
                }
                int searchDepth = Math.max(0, depth - 1 - reduction);
                score = -negamax(child, searchDepth, -alpha - 1, -alpha, ply + 1, false, true);
                if (!aborted && reduce && score > alpha) {
                    score = -negamax(child, depth - 1, -alpha - 1, -alpha, ply + 1, false, true);
                }
                if (!aborted && score > alpha && score < beta) {
                    score = -negamax(child, depth - 1, -beta, -alpha, ply + 1, false, true);
                }
            } else {
                score = -negamax(child, depth - 1, -beta, -alpha, ply + 1, onPv && move == hint, true);
            }
            if (aborted) {
                return 0;
            }
            if (score >= beta) {
                if (historyWeight > 0 && move != Move.PASS) {
                    historyAdd(pos.sideToMove(), move, (float) depth * depth);
                }
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
            } else if (historyWeight > 0 && move != Move.PASS) {
                historyAdd(pos.sideToMove(), move, -(float) depth * depth / historyPenaltyDiv);
            }
        }
        if (historyWeight > 0 && bestMove != Move.NONE && bestMove != Move.PASS) {
            historyAdd(pos.sideToMove(), bestMove, (float) depth * depth);
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
        int k = pos.generateScoredCaptureMoves(moves, scores, qMinCaptures,
                qply >= qJumpCutFromPly ? qJumpMode : Position.JUMPS_ALL);
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
        int side = pos.sideToMove();
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
                if (orderEdge && (EDGE_MASK >>> to & 1) != 0) {
                    s += EDGE_BONUS;
                }
                if (historyWeight > 0) {
                    s += (int) (historyWeight * 0.1f * SWING_STEP * historyOf(side, m) / HISTORY_LIMIT);
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
