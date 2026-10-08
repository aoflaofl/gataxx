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

    private static final int MAX_PLY = MAX_DEPTH + 4;
    private static final int INF = 30_000;
    private static final int NODE_CHECK_MASK = 1023;

    private final Evaluator evaluator;
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

    public Searcher(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** Asks the running (or about-to-run) search to finish as soon as possible. */
    public void stop() {
        stopRequested = true;
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

        int[] rootMoves = moveBuf[0];
        int n = root.generateMoves(rootMoves);
        if (n == 0) {
            return new SearchResult(Move.NONE, terminalScore(root, 0), 0, 0, 0, new int[0]);
        }
        orderMoves(root, rootMoves, scoreBuf[0], n, Move.NONE);
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
        int[] moves = moveBuf[ply];
        int n = pos.generateMoves(moves);
        if (n == 0) {
            return terminalScore(pos, ply);
        }
        int[] scores = scoreBuf[ply];
        int hint = onPv && ply < prevPv.length ? prevPv[ply] : Move.NONE;
        orderMoves(pos, moves, scores, n, hint);

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
                return beta;
            }
            if (score > alpha) {
                alpha = score;
                pv[ply][0] = move;
                System.arraycopy(pv[ply + 1], 0, pv[ply], 1, pvLen[ply + 1]);
                pvLen[ply] = pvLen[ply + 1] + 1;
            }
        }
        return alpha;
    }

    /** Scores moves for ordering: the hint first, then by material swing, preferring clones. */
    private static void orderMoves(Position pos, int[] moves, int[] scores, int n, int hint) {
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (m == hint) {
                scores[i] = Integer.MAX_VALUE;
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
