package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.FeatureEvaluator;
import spamalot.gataxx.eval.Evaluator;

class ParallelSearchTest {
    private static final Evaluator EVAL =
            new FeatureEvaluator(new FeatureEvaluator.Weights(FeatureEvaluator.SCALE, 4, 0, 0, 4, 0, 8, 32, 0, 0, 0, -3, 0));

    private static Searcher searcher(TranspositionTable tt) {
        Searcher s = new Searcher(EVAL, tt);
        s.setQuiescence(3, 4);
        s.setPvs(true);
        s.setLmr(true, 3, 4);
        return s;
    }

    @Test
    void withoutHelpersItIsThePlainSearch() {
        Position pos = Position.startPos();
        SearchResult plain = searcher(new TranspositionTable(4)).search(pos, SearchLimits.depth(7), null);
        ParallelSearch ps = new ParallelSearch(searcher(new TranspositionTable(4)), List.of());
        SearchResult viaGroup = ps.search(pos, SearchLimits.depth(7), null);
        assertEquals(plain.bestMove(), viaGroup.bestMove());
        assertEquals(plain.score(), viaGroup.score());
        assertEquals(plain.nodes(), viaGroup.nodes());
    }

    @Test
    void helpersReturnALegalMoveAndAddTheirNodes() {
        Position pos = Position.startPos().makeMove(spamalot.gataxx.board.Move.parse("b6"));
        TranspositionTable tt = new TranspositionTable(8);
        List<Searcher> helpers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            helpers.add(searcher(tt));
        }
        ParallelSearch ps = new ParallelSearch(searcher(tt), helpers);
        SearchResult r = ps.search(pos, SearchLimits.movetime(300), null);
        assertTrue(pos.isLegal(r.bestMove()), "move " + r.bestMove());
        assertTrue(r.depth() >= 4, "depth " + r.depth());
        for (Searcher h : helpers) {
            assertTrue(h.publishedNodes() > 0, "every helper searched");
        }
    }

    @Test
    void stopEndsAnInfiniteParallelSearchPromptly() throws Exception {
        Position pos = Position.startPos();
        TranspositionTable tt = new TranspositionTable(8);
        ParallelSearch ps = new ParallelSearch(searcher(tt), List.of(searcher(tt), searcher(tt)));
        AtomicBoolean done = new AtomicBoolean();
        Thread t = new Thread(() -> {
            ps.search(pos, SearchLimits.infinite(), null);
            done.set(true);
        });
        t.start();
        Thread.sleep(200);
        assertFalse(done.get());
        ps.stop();
        t.join(5000);
        assertTrue(done.get(), "search returned after stop()");
    }

    @Test
    void concurrentTableAccessNeverReturnsATornEntry() throws Exception {
        TranspositionTable tt = new TranspositionTable(1); // few slots, so the writers collide constantly
        // Every key is stored with a score and depth derived from the key; a probe must be consistent or a miss.
        AtomicBoolean bad = new AtomicBoolean();
        Runnable work = () -> {
            java.util.SplittableRandom rnd = new java.util.SplittableRandom(Thread.currentThread().threadId());
            TranspositionTable.Entry e = new TranspositionTable.Entry();
            for (int i = 0; i < 400_000; i++) {
                long key = (rnd.nextLong() & 0xFFFFL) * 0x9E3779B97F4A7C15L;
                int score = (int) (key >>> 40) % 3000;
                int depth = (int) (key >>> 20) & 63;
                if ((i & 1) == 0) {
                    tt.store(key, depth, TranspositionTable.BOUND_EXACT, score, spamalot.gataxx.board.Move.NONE);
                } else if (tt.probe(key, e).found && (e.score != score || e.depth != depth)) {
                    bad.set(true);
                }
            }
        };
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(work);
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertFalse(bad.get(), "a probe returned data belonging to another key");
    }
}
