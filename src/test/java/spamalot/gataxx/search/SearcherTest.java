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

class SearcherTest {
    private static final Evaluator EVAL = new MaterialEvaluator();

    private static SearchResult search(Position p, SearchLimits limits) {
        return new Searcher(EVAL).search(p, limits, null);
    }

    /** Unpruned reference NegaMax, same conventions as the real search. */
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

    private static List<Position> samplePositions() {
        Random rnd = new Random(7);
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            out.add(randomWalk(rnd, Position.START_FEN, i * 3));
        }
        out.add(randomWalk(rnd, "x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1", 10));
        out.add(Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1")); // x must pass
        out.add(Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/ooooooo/ooooooo/oooo2o/ooooo1o x 0 1"));
        return out;
    }

    @Test
    void alphaBetaMatchesPlainNegamax() {
        for (Position p : samplePositions()) {
            if (p.isGameOver()) {
                continue;
            }
            for (int depth = 1; depth <= 3; depth++) {
                assertEquals(plainNegamax(p, depth, 0), search(p, SearchLimits.depth(depth)).score(),
                        "depth " + depth + "\n" + p);
            }
        }
    }

    @Test
    void alphaBetaMatchesPlainNegamaxDepth4() {
        Position p = Position.startPos();
        assertEquals(plainNegamax(p, 4, 0), search(p, SearchLimits.depth(4)).score());
        Position q = randomWalk(new Random(3), Position.START_FEN, 8);
        assertEquals(plainNegamax(q, 4, 0), search(q, SearchLimits.depth(4)).score());
    }

    @Test
    void depthOnePicksBiggestMaterialGain() {
        // x clone to b1 converts a2 and b2; nothing else comes close.
        Position p = Position.fromFen("6o/7/7/7/7/oo5/x6 x 0 1");
        SearchResult r = search(p, SearchLimits.depth(1));
        assertEquals("b1", Move.toString(r.bestMove()));
        assertEquals(3, r.score()); // x 4 v o 1 after the clone
    }

    @Test
    void findsForcedWinInOne() {
        // Cloning to c1 or a2 converts o's only piece.
        Position p = Position.fromFen("7/7/7/7/7/7/xo5 x 0 1");
        SearchResult r = search(p, SearchLimits.depth(4));
        Position after = p.makeMove(r.bestMove());
        assertTrue(after.isGameOver());
        assertEquals(spamalot.gataxx.board.Outcome.X_WINS, after.outcome());
        assertEquals(Searcher.WIN - 1, r.score());
        assertEquals(1, r.depth(), "should stop as soon as a forced win is proven");
    }

    @Test
    void seesForcedLoss() {
        // o's only move is the jump a1c1; x then clones to d1 and converts it, wiping o out.
        Position p = Position.fromFen("7/7/7/7/---4/---4/o-2x2 o 0 1");
        SearchResult r = search(p, SearchLimits.depth(4));
        assertEquals("a1c1", Move.toString(r.bestMove()));
        assertEquals(-(Searcher.WIN - 2), r.score());
    }

    @Test
    void returnsPassWhenStuck() {
        Position p = Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1");
        SearchResult r = search(p, SearchLimits.depth(3));
        assertEquals(Move.PASS, r.bestMove());
    }

    @Test
    void gameOverRootHasNoMove() {
        Position p = Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxoo o 0 1");
        SearchResult r = search(p, SearchLimits.depth(3));
        assertEquals(Move.NONE, r.bestMove());
        assertEquals(-Searcher.WIN, r.score()); // o to move, outnumbered, game over
    }

    @Test
    void pvIsLegalAndStartsWithBestMove() {
        Position p = randomWalk(new Random(11), Position.START_FEN, 6);
        SearchResult r = search(p, SearchLimits.depth(4));
        assertTrue(r.pv().length > 0);
        assertEquals(r.bestMove(), r.pv()[0]);
        Position cur = p;
        for (int m : r.pv()) {
            assertTrue(cur.isLegal(m), Move.toString(m));
            cur = cur.makeMove(m);
        }
    }

    @Test
    void iterationListenerSeesEveryDepth() {
        List<Integer> depths = new ArrayList<>();
        new Searcher(EVAL).search(Position.startPos(), SearchLimits.depth(4), r -> depths.add(r.depth()));
        assertEquals(List.of(1, 2, 3, 4), depths);
    }

    @Test
    void deterministic() {
        Position p = randomWalk(new Random(5), Position.START_FEN, 10);
        SearchResult a = search(p, SearchLimits.depth(4));
        SearchResult b = search(p, SearchLimits.depth(4));
        assertEquals(a.bestMove(), b.bestMove());
        assertEquals(a.score(), b.score());
        assertEquals(a.nodes(), b.nodes());
    }

    @Test
    void moveOrderingPrunesNodes() {
        Position p = randomWalk(new Random(5), Position.START_FEN, 10);
        long searched = search(p, SearchLimits.depth(4)).nodes();
        long full = perftNodes(p, 4); // unpruned tree size bounds the alpha-beta count
        assertTrue(searched < full, searched + " vs " + full);
    }

    private static long perftNodes(Position p, int depth) {
        long total = 1;
        if (depth > 0) {
            int[] moves = new int[Position.MAX_MOVES];
            int n = p.generateMoves(moves);
            for (int i = 0; i < n; i++) {
                total += perftNodes(p.makeMove(moves[i]), depth - 1);
            }
        }
        return total;
    }

    @Test
    void nodeLimitStopsSearchWithLegalMove() {
        Position p = randomWalk(new Random(9), Position.START_FEN, 8);
        SearchResult r = search(p, SearchLimits.nodes(5000));
        assertTrue(p.isLegal(r.bestMove()));
        assertTrue(r.nodes() < 5000 + 2048, "nodes " + r.nodes());
    }

    @Test
    void movetimeIsRespected() {
        Position p = Position.startPos();
        long t0 = System.nanoTime();
        SearchResult r = search(p, SearchLimits.movetime(200));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 600, "took " + ms + "ms");
        assertTrue(p.isLegal(r.bestMove()));
        assertTrue(r.depth() >= 1);
    }

    @Test
    void stopFromAnotherThread() throws Exception {
        Searcher s = new Searcher(EVAL);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            s.stop();
        });
        t.start();
        long t0 = System.nanoTime();
        SearchResult r = s.search(Position.startPos(), SearchLimits.infinite(), null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        t.join();
        assertTrue(ms < 1000, "took " + ms + "ms");
        assertTrue(Position.startPos().isLegal(r.bestMove()));
    }

    @Test
    void stopBeforeSearchStillGivesLegalMove() {
        Searcher s = new Searcher(EVAL);
        s.stop();
        SearchResult r = s.search(Position.startPos(), SearchLimits.infinite(), null);
        assertTrue(Position.startPos().isLegal(r.bestMove()));
        assertEquals(0, r.depth());
    }
}
