package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.MaterialEvaluator;

/**
 * PVS (zero-width windows after the first move) is an efficiency change only: a completed search must return
 * exactly the score of the same search without it, which other tests verify against unpruned NegaMax.
 */
class PvsTest {
    private static final Evaluator EVAL = new MaterialEvaluator();

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
        Random rnd = new Random(88);
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(randomWalk(rnd, Position.START_FEN, 3 + i * 5));
        }
        out.add(randomWalk(rnd, "x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1", 12));
        out.add(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1")); // pass
        out.add(Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1")); // forced loss in 2
        out.add(Position.fromFen("7/7/7/7/7/7/xo5 x 0 1")); // win in 1
        return out;
    }

    private static int score(Position p, int depth, TranspositionTable tt, boolean pvs, int qMin, int qPly,
                             boolean strict) {
        Searcher s = new Searcher(EVAL, tt);
        s.setQuiescence(qMin, qPly);
        s.setPvs(pvs);
        s.setExactDepthHitsOnly(strict);
        return s.search(p, SearchLimits.depth(depth), null).score();
    }

    @Test
    void scoresMatchWithoutPvs() {
        TranspositionTable tt = new TranspositionTable(2);
        for (Position p : samples()) {
            if (p.isGameOver()) {
                continue;
            }
            for (int depth = 1; depth <= 5; depth++) {
                assertEquals(score(p, depth, null, false, 0, 0, false), score(p, depth, null, true, 0, 0, false),
                        "plain, depth " + depth + "\n" + p);
                assertEquals(score(p, depth, null, false, 0, 0, false), score(p, depth, tt, true, 0, 0, true),
                        "strict table, depth " + depth + "\n" + p);
            }
            for (int depth = 1; depth <= 3; depth++) {
                assertEquals(score(p, depth, null, false, 3, 4, false), score(p, depth, null, true, 3, 4, false),
                        "quiescence, depth " + depth + "\n" + p);
            }
        }
    }

    /** Rare window combinations only show up over many searches, as for the table. */
    @Test
    void scoresMatchOverManyRandomPositions() {
        Random rnd = new Random(2);
        int[] buf = new int[Position.MAX_MOVES];
        TranspositionTable tt = new TranspositionTable(1);
        for (int i = 0; i < 80; i++) {
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
            for (int depth = 4; depth <= 5; depth++) {
                int expected = score(p, depth, null, false, 0, 0, false);
                assertEquals(expected, score(p, depth, null, true, 0, 0, false), "plain, depth " + depth + "\n" + p);
                assertEquals(expected, score(p, depth, tt, true, 0, 0, true), "strict table, depth " + depth + "\n" + p);
            }
        }
    }

    @Test
    void pvsSearchesFewerNodesAndKeepsAValidPrincipalVariation() {
        Position p = Position.fromFen(BenchPositions.FENS[7]);
        Searcher off = new Searcher(EVAL);
        Searcher on = new Searcher(EVAL);
        on.setPvs(true);
        SearchResult roff = off.search(p, SearchLimits.depth(5), null);
        SearchResult ron = on.search(p, SearchLimits.depth(5), null);
        assertEquals(roff.score(), ron.score());
        assertTrue(ron.nodes() < roff.nodes(), ron.nodes() + " vs " + roff.nodes());
        assertTrue(p.isLegal(ron.bestMove()));
        Position cur = p;
        for (int m : ron.pv()) {
            assertTrue(cur.isLegal(m));
            cur = cur.makeMove(m);
        }
    }
}
