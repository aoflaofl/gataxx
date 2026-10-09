package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.MaterialEvaluator;

/**
 * Null-move pruning is a heuristic, not an exact optimisation, so these tests check its contract rather than score
 * equality: off unless asked for, still finds forced results, usually agrees with the plain search, and prunes.
 */
class NullMoveTest {
    private static final Evaluator EVAL = new MaterialEvaluator();

    private static Searcher searcher(boolean nullMove, int r, int minEmpties) {
        Searcher s = new Searcher(EVAL, new TranspositionTable(2));
        s.setPvs(true);
        s.setNullMove(nullMove, r, minEmpties);
        return s;
    }

    @Test
    void offByDefaultAndDisabledMatchesPlainSearchExactly() {
        Position p = Position.fromFen(BenchPositions.FENS[6]);
        Searcher plain = new Searcher(EVAL);
        plain.setPvs(true);
        Searcher disabled = new Searcher(EVAL);
        disabled.setPvs(true);
        disabled.setNullMove(false, 3, 12);
        SearchResult a = plain.search(p, SearchLimits.depth(5), null);
        SearchResult b = disabled.search(p, SearchLimits.depth(5), null);
        assertEquals(a.nodes(), b.nodes());
        assertEquals(a.score(), b.score());
    }

    @Test
    void parametersValidated() {
        Searcher s = new Searcher(EVAL);
        assertThrows(IllegalArgumentException.class, () -> s.setNullMove(true, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> s.setNullMove(true, 2, -1));
        s.setNullMove(true, 1, 0);
    }

    @Test
    void stillFindsForcedWinsAndLosses() {
        for (int r = 1; r <= 4; r++) {
            SearchResult win = searcher(true, r, 0).search(Position.fromFen("7/7/7/7/7/7/xo5 x 0 1"), SearchLimits.depth(6), null);
            assertEquals(Searcher.WIN - 1, win.score(), "R=" + r);
            SearchResult loss = searcher(true, r, 0).search(Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1"), SearchLimits.depth(6), null);
            assertEquals(-(Searcher.WIN - 2), loss.score(), "R=" + r);
        }
    }

    @Test
    void passesAreHandledWhenTheMoverIsStuck() {
        // x has no real move: the pruning must not try to "pass" it again, and the search must still return a pass.
        SearchResult r = searcher(true, 3, 0).search(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1"), SearchLimits.depth(6), null);
        assertEquals(Move.PASS, r.bestMove());
    }

    @Test
    void usuallyAgreesWithThePlainSearchAndSearchesFewerNodes() {
        Random rnd = new Random(13);
        int[] buf = new int[Position.MAX_MOVES];
        int total = 0;
        int sameScore = 0;
        long plainNodes = 0;
        long nullNodes = 0;
        for (int i = 0; i < 40; i++) {
            Position p = Position.startPos();
            int len = 8 + rnd.nextInt(45);
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
            SearchResult plain = searcher(false, 3, 12).search(p, SearchLimits.depth(5), null);
            SearchResult pruned = searcher(true, 3, 12).search(p, SearchLimits.depth(5), null);
            total++;
            sameScore += plain.score() == pruned.score() ? 1 : 0;
            plainNodes += plain.nodes();
            nullNodes += pruned.nodes();
            assertTrue(p.isLegal(pruned.bestMove()));
        }
        assertTrue(sameScore * 100 >= total * 85, "same score in " + sameScore + " of " + total);
        assertTrue(nullNodes < plainNodes * 0.7, nullNodes + " vs " + plainNodes);
    }

    @Test
    void timedSearchWithNullMoveHonoursLimits() {
        Searcher s = searcher(true, 3, 12);
        long t0 = System.nanoTime();
        SearchResult r = s.search(Position.fromFen(BenchPositions.FENS[8]), SearchLimits.movetime(150), null);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500);
        assertTrue(Position.fromFen(BenchPositions.FENS[8]).isLegal(r.bestMove()));
    }
}
