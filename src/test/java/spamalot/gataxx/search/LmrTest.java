package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.FeatureEvaluator;
import spamalot.gataxx.eval.FeatureEvaluator.Weights;
import spamalot.gataxx.eval.MaterialEvaluator;

/** Late-move reductions are selective and not exact, so these tests check the contract, as for null-move pruning. */
class LmrTest {
    private static final Evaluator EVAL = new MaterialEvaluator();

    private static Searcher searcher(boolean lmr, int fullMoves, int minDepth) {
        Searcher s = new Searcher(EVAL, new TranspositionTable(2));
        s.setPvs(true);
        s.setLmr(lmr, fullMoves, minDepth);
        return s;
    }

    /** The configuration the engine plays with: tuned evaluation, quiescence, table and PVS. */
    private static Searcher engineLike(boolean lmr) {
        Searcher s = new Searcher(new FeatureEvaluator(new Weights(16, 4, 0, 0, 4, 0, 8, 32, 0, 0, 0, -3, 0)),
                new TranspositionTable(4));
        s.setQuiescence(3, 4);
        s.setPvs(true);
        s.setLmr(lmr, 3, 4);
        s.setLmrDeep(lmr ? 6 : 0);
        return s;
    }

    @Test
    void disabledMatchesPlainPvsExactly() {
        Position p = Position.fromFen(BenchPositions.FENS[6]);
        Searcher plain = new Searcher(EVAL, new TranspositionTable(2));
        plain.setPvs(true);
        SearchResult a = plain.search(p, SearchLimits.depth(5), null);
        SearchResult b = searcher(false, 3, 4).search(p, SearchLimits.depth(5), null);
        assertEquals(a.nodes(), b.nodes());
        assertEquals(a.score(), b.score());
    }

    @Test
    void parametersValidated() {
        Searcher s = new Searcher(EVAL);
        assertThrows(IllegalArgumentException.class, () -> s.setLmr(true, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> s.setLmr(true, 3, 1));
        s.setLmr(true, 1, 2);
    }

    @Test
    void stillFindsForcedWinsAndLosses() {
        for (int full = 1; full <= 4; full++) {
            SearchResult win = searcher(true, full, 2).search(Position.fromFen("7/7/7/7/7/7/xo5 x 0 1"), SearchLimits.depth(6), null);
            assertEquals(Searcher.WIN - 1, win.score(), "full=" + full);
            SearchResult loss = searcher(true, full, 2).search(Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1"), SearchLimits.depth(6), null);
            assertEquals(-(Searcher.WIN - 2), loss.score(), "full=" + full);
        }
    }

    @Test
    void passesStillWorkWithReductions() {
        SearchResult r = searcher(true, 1, 2).search(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1"), SearchLimits.depth(6), null);
        assertEquals(Move.PASS, r.bestMove());
    }

    @Test
    void usuallyAgreesWithThePlainSearchAndSearchesFarFewerNodes() {
        // Measured on these positions at depth 5: same score in 10 of 12, 40% of the nodes (28% at depth 6).
        int total = 0;
        int sameScore = 0;
        long plainNodes = 0;
        long lmrNodes = 0;
        for (int i = 0; i < 12; i++) {
            Position p = Position.fromFen(BenchPositions.FENS[i]);
            SearchResult plain = engineLike(false).search(p, SearchLimits.depth(5), null);
            SearchResult reduced = engineLike(true).search(p, SearchLimits.depth(5), null);
            total++;
            sameScore += plain.score() == reduced.score() ? 1 : 0;
            plainNodes += plain.nodes();
            lmrNodes += reduced.nodes();
            assertTrue(p.isLegal(reduced.bestMove()));
        }
        assertTrue(sameScore * 100 >= total * 60, "same score in " + sameScore + " of " + total);
        assertTrue(lmrNodes < plainNodes * 0.6, lmrNodes + " vs " + plainNodes);
    }

    @Test
    void timedSearchWithReductionsHonoursLimits() {
        Position p = Position.fromFen(BenchPositions.FENS[8]);
        long t0 = System.nanoTime();
        SearchResult r = searcher(true, 3, 4).search(p, SearchLimits.movetime(150), null);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500);
        assertTrue(p.isLegal(r.bestMove()));
    }
}
