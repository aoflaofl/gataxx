package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.MaterialEvaluator;

class SearcherQuiescenceTest {
    private static final Evaluator EVAL = new MaterialEvaluator();

    /** Unpruned quiescence: best of standing pat and every noisy move. */
    private static int referenceQuiesce(Position pos, int ply, int qply, int minCaptures, int maxPly) {
        if (pos.isGameOver()) {
            return Searcher.terminalScore(pos, ply);
        }
        int best = EVAL.evaluate(pos);
        if (qply >= maxPly) {
            return best;
        }
        int[] moves = new int[Position.MAX_MOVES];
        int n = pos.generateMoves(moves);
        for (int i = 0; i < n; i++) {
            if (moves[i] != Move.PASS && pos.captureCount(moves[i]) >= minCaptures) {
                best = Math.max(best, -referenceQuiesce(pos.makeMove(moves[i]), ply + 1, qply + 1, minCaptures, maxPly));
            }
        }
        return best;
    }

    private static int referenceNegamax(Position pos, int depth, int ply, int minCaptures, int maxPly) {
        if (depth == 0) {
            return referenceQuiesce(pos, ply, 0, minCaptures, maxPly);
        }
        int[] moves = new int[Position.MAX_MOVES];
        int n = pos.generateMoves(moves);
        if (n == 0) {
            return Searcher.terminalScore(pos, ply);
        }
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            best = Math.max(best, -referenceNegamax(pos.makeMove(moves[i]), depth - 1, ply + 1, minCaptures, maxPly));
        }
        return best;
    }

    private static Position randomWalk(Random rnd, String fen, int plies) {
        Position p = Position.fromFen(fen);
        int[] buf = new int[Position.MAX_MOVES];
        for (int i = 0; i < plies; i++) {
            int n = p.generateMoves(buf);
            if (n == 0) {
                break;
            }
            p = p.makeMove(buf[rnd.nextInt(n)]);
        }
        return p;
    }

    private static List<Position> samples() {
        Random rnd = new Random(31);
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            out.add(randomWalk(rnd, Position.START_FEN, 6 + i * 5));
        }
        out.add(randomWalk(rnd, "x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1", 14));
        out.add(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1")); // pass
        out.add(Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1")); // forced loss
        out.add(Position.fromFen("7/7/7/7/7/7/xo5 x 0 1")); // win in one
        return out;
    }

    private static Searcher searcher(TranspositionTable tt, int minCaptures, int maxPly) {
        Searcher s = new Searcher(EVAL, tt);
        s.setQuiescence(minCaptures, maxPly);
        return s;
    }

    @Test
    void alphaBetaQuiescenceEqualsUnprunedReference() {
        // Small extensions only: the unpruned reference grows explosively with threshold 1 or deep qPly.
        int[][] params = {{1, 1}, {2, 2}, {3, 4}};
        for (Position p : samples()) {
            if (p.isGameOver()) {
                continue;
            }
            for (int[] q : params) {
                for (int depth = 1; depth <= 2; depth++) {
                    assertEquals(referenceNegamax(p, depth, 0, q[0], q[1]),
                            searcher(null, q[0], q[1]).search(p, SearchLimits.depth(depth), null).score(),
                            "depth " + depth + " q=" + q[0] + "/" + q[1] + "\n" + p);
                }
            }
        }
    }

    @Test
    void strictTableSearchWithQuiescenceEqualsReference() {
        TranspositionTable shared = new TranspositionTable(4);
        for (Position p : samples()) {
            if (p.isGameOver()) {
                continue;
            }
            for (int depth = 1; depth <= 2; depth++) {
                Searcher s = searcher(shared, 3, 2);
                s.setExactDepthHitsOnly(true);
                assertEquals(referenceNegamax(p, depth, 0, 3, 2), s.search(p, SearchLimits.depth(depth), null).score(),
                        "depth " + depth + "\n" + p);
            }
        }
    }

    /**
     * Positions found by scanning random games: a depth-1 search without quiescence grabs material
     * and walks into a recapture, while with quiescence it picks the same move as a depth-4 search.
     */
    @Test
    void quiescenceAvoidsHorizonBlunders() {
        for (String fen : new String[] {"o6/o6/o3x2/6o/2x1x2/3x3/7 x 0 9", "x3o1o/x3o2/1x1o3/1x5/2x4/7/7 x 1 6"}) {
            Position p = Position.fromFen(fen);
            int truth = new Searcher(EVAL).search(p, SearchLimits.depth(4), null).bestMove();
            int plain = new Searcher(EVAL).search(p, SearchLimits.depth(1), null).bestMove();
            int withQ = searcher(null, 2, 6).search(p, SearchLimits.depth(1), null).bestMove();
            assertNotEquals(truth, plain, "scenario no longer demonstrates the blunder: " + fen);
            assertEquals(truth, withQ, fen);
        }
    }

    @Test
    void forcedWinsAndLossesStillFoundWithQuiescence() {
        SearchResult win = searcher(null, 1, 4).search(Position.fromFen("7/7/7/7/7/7/xo5 x 0 1"), SearchLimits.depth(3), null);
        assertEquals(Searcher.WIN - 1, win.score());
        SearchResult loss = searcher(null, 1, 4).search(Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1"), SearchLimits.depth(4), null);
        assertEquals(-(Searcher.WIN - 2), loss.score());
    }

    @Test
    void zeroThresholdDisablesQuiescence() {
        Position p = randomWalk(new Random(2), Position.START_FEN, 10);
        SearchResult off = searcher(null, 0, 8).search(p, SearchLimits.depth(4), null);
        SearchResult plain = new Searcher(EVAL).search(p, SearchLimits.depth(4), null);
        assertEquals(plain.score(), off.score());
        assertEquals(plain.nodes(), off.nodes());
    }

    @Test
    void quiescenceSearchesExtraNodes() {
        Position p = randomWalk(new Random(2), Position.START_FEN, 10);
        long plain = new Searcher(EVAL).search(p, SearchLimits.depth(3), null).nodes();
        long withQ = searcher(null, 1, 6).search(p, SearchLimits.depth(3), null).nodes();
        assertTrue(withQ > plain, withQ + " vs " + plain);
    }

    @Test
    void parametersValidated() {
        Searcher s = new Searcher(EVAL);
        assertThrows(IllegalArgumentException.class, () -> s.setQuiescence(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> s.setQuiescence(1, -1));
        assertThrows(IllegalArgumentException.class, () -> s.setQuiescence(1, Searcher.MAX_QUIESCENCE_PLY + 1));
        s.setQuiescence(1, Searcher.MAX_QUIESCENCE_PLY); // boundary is fine
    }

    @Test
    void deepSearchWithMaximumQuiescenceStaysWithinBuffers() {
        // Depth cap + maximum extension must not overflow the per-ply buffers.
        Position p = randomWalk(new Random(12), Position.START_FEN, 20);
        Searcher s = searcher(new TranspositionTable(4), 1, Searcher.MAX_QUIESCENCE_PLY);
        SearchResult r = s.search(p, SearchLimits.movetime(300), null);
        assertTrue(p.isLegal(r.bestMove()));
        Searcher deep = searcher(null, 1, Searcher.MAX_QUIESCENCE_PLY);
        SearchResult d = deep.search(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1"),
                new SearchLimits(Searcher.MAX_DEPTH, 0, 300, 0), null);
        assertEquals(Move.PASS, d.bestMove());
    }
}
