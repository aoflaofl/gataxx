package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.MaterialEvaluator;

class SearcherTranspositionTest {
    private static final Evaluator EVAL = new MaterialEvaluator();

    private static int plainNegamax(Position pos, int depth, int ply) {
        if (depth == 0) {
            return pos.isGameOver() ? Searcher.terminalScore(pos, ply) : EVAL.evaluate(pos);
        }
        int[] moves = new int[Position.MAX_MOVES];
        int n = pos.generateMoves(moves);
        if (n == 0) {
            return Searcher.terminalScore(pos, ply);
        }
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            best = Math.max(best, -plainNegamax(pos.makeMove(moves[i]), depth - 1, ply + 1));
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
        Random rnd = new Random(21);
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            out.add(randomWalk(rnd, Position.START_FEN, i * 3));
        }
        out.add(randomWalk(rnd, "x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1", 12));
        out.add(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1")); // pass
        out.add(Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1")); // forced loss in 2
        out.add(Position.fromFen("7/7/7/7/7/7/xo5 x 0 1")); // win in 1
        out.add(Position.fromFen("x5o/7/7/7/7/7/o5x x 96 60")); // near the half-move limit
        out.add(Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/ooooooo/ooooooo/oooo2o/ooooo1o x 0 1"));
        return out;
    }

    @Test
    void strictTableSearchEqualsPlainNegamax() {
        TranspositionTable shared = new TranspositionTable(4); // reused across positions and depths
        for (Position p : samples()) {
            if (p.isGameOver()) {
                continue;
            }
            for (int depth = 1; depth <= 4; depth++) {
                Searcher s = new Searcher(EVAL, shared);
                s.setExactDepthHitsOnly(true);
                int got = s.search(p, SearchLimits.depth(depth), null).score();
                assertEquals(plainNegamax(p, depth, 0), got, "depth " + depth + "\n" + p);
            }
        }
    }

    /**
     * Stress test against the (already verified) table-less alpha-beta, which can go deeper than plain
     * NegaMax. Wrong bound types only misbehave on rare window combinations, so this needs volume:
     * with a deliberately wrong bound it fails on one of these ~840 searches.
     */
    @Test
    void strictTableSearchEqualsTablelessAlphaBetaAtDepth() {
        TranspositionTable shared = new TranspositionTable(1);
        Random rnd = new Random(1);
        int[] buf = new int[Position.MAX_MOVES];
        for (int i = 0; i < 300; i++) {
            Position p = Position.startPos();
            int len = rnd.nextInt(50);
            for (int k = 0; k < len; k++) {
                int n = p.generateMoves(buf);
                if (n == 0) {
                    break;
                }
                p = p.makeMove(buf[rnd.nextInt(n)]);
            }
            if (p.isGameOver()) {
                continue;
            }
            for (int depth = 4; depth <= 6; depth++) {
                Searcher s = new Searcher(EVAL, shared);
                s.setExactDepthHitsOnly(true);
                assertEquals(new Searcher(EVAL).search(p, SearchLimits.depth(depth), null).score(),
                        s.search(p, SearchLimits.depth(depth), null).score(), "depth " + depth + "\n" + p);
            }
        }
    }

    @Test
    void strictTableSearchEqualsPlainWithTinyTableUnderHeavyReplacement() {
        TranspositionTable tiny = new TranspositionTable(0); // 1024 entries: constant collisions
        Position p = randomWalk(new Random(4), Position.START_FEN, 9);
        for (int depth = 1; depth <= 4; depth++) {
            Searcher s = new Searcher(EVAL, tiny);
            s.setExactDepthHitsOnly(true);
            assertEquals(plainNegamax(p, depth, 0), s.search(p, SearchLimits.depth(depth), null).score());
        }
    }

    @Test
    void mateDistanceSurvivesTheTable() {
        TranspositionTable tt = new TranspositionTable(1);
        Position loss = Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1");
        Position win = Position.fromFen("7/7/7/7/7/7/xo5 x 0 1");
        for (int round = 0; round < 3; round++) { // second and third rounds run on a warm table
            assertEquals(-(Searcher.WIN - 2), new Searcher(EVAL, tt).search(loss, SearchLimits.depth(6), null).score());
            assertEquals(Searcher.WIN - 1, new Searcher(EVAL, tt).search(win, SearchLimits.depth(6), null).score());
        }
    }

    @Test
    void tableReducesNodes() {
        Position p = randomWalk(new Random(5), Position.START_FEN, 10);
        long plain = new Searcher(EVAL).search(p, SearchLimits.depth(6), null).nodes();
        long withTt = new Searcher(EVAL, new TranspositionTable(16)).search(p, SearchLimits.depth(6), null).nodes();
        assertTrue(withTt < plain, withTt + " vs " + plain);
    }

    @Test
    void warmTableSpeedsUpRepeatSearch() {
        Position p = randomWalk(new Random(6), Position.START_FEN, 8);
        TranspositionTable tt = new TranspositionTable(16);
        long cold = new Searcher(EVAL, tt).search(p, SearchLimits.depth(6), null).nodes();
        long warm = new Searcher(EVAL, tt).search(p, SearchLimits.depth(6), null).nodes();
        assertTrue(warm < cold, warm + " vs " + cold);
    }

    @Test
    void resultsAreLegalAndDeterministicWithTable() {
        Position p = randomWalk(new Random(8), Position.START_FEN, 12);
        SearchResult a = new Searcher(EVAL, new TranspositionTable(8)).search(p, SearchLimits.depth(6), null);
        SearchResult b = new Searcher(EVAL, new TranspositionTable(8)).search(p, SearchLimits.depth(6), null);
        assertTrue(p.isLegal(a.bestMove()));
        assertEquals(a.bestMove(), b.bestMove());
        assertEquals(a.score(), b.score());
        assertEquals(a.nodes(), b.nodes());
        Position cur = p;
        for (int m : a.pv()) {
            assertTrue(cur.isLegal(m), Move.toString(m));
            cur = cur.makeMove(m);
        }
    }

    @Test
    void timedSearchWithTableStillHonoursLimitsAndReturnsLegalMove() {
        TranspositionTable tt = new TranspositionTable(16);
        Position p = Position.startPos();
        long t0 = System.nanoTime();
        SearchResult r = new Searcher(EVAL, tt).search(p, SearchLimits.movetime(200), null);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 600);
        assertTrue(p.isLegal(r.bestMove()));
    }
}
