package spamalot.gataxx.search;

import java.util.ArrayList;
import java.util.List;
import spamalot.gataxx.board.Position;

/**
 * Lazy-SMP search: the main searcher runs as usual and decides the result, while helper searchers search the same root at the
 * same time over the same (lock-free safe) transposition table, which makes the main search faster by sharing what they find.
 * Helpers skip different iterations of the iterative deepening, so that the threads are at different depths at the same
 * moment, and run until the main searcher is done.
 *
 * <p>With no helpers this is exactly the plain single-threaded search.
 */
public final class ParallelSearch {
    private final Searcher main;
    private final List<Searcher> helpers;

    /** @param helpers searchers sharing {@code main}'s table, each used for one search only */
    public ParallelSearch(Searcher main, List<Searcher> helpers) {
        this.main = main;
        this.helpers = List.copyOf(helpers);
        for (int i = 0; i < this.helpers.size(); i++) {
            this.helpers.get(i).asHelper(i + 1);
        }
    }

    /** Asks every searcher to finish as soon as possible. */
    public void stop() {
        main.stop();
        for (Searcher h : helpers) {
            h.stop();
        }
    }

    /** Runs the search; the result is the main searcher's, with the helpers' nodes added to its node count. */
    public SearchResult search(Position root, SearchLimits limits, SearchListener listener) {
        List<Thread> threads = new ArrayList<>();
        for (Searcher h : helpers) {
            Thread t = new Thread(() -> h.search(root, SearchLimits.depth(Searcher.MAX_DEPTH), null), "search-helper");
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        try {
            SearchListener wrapped = listener == null ? null : r -> listener.onIteration(withHelperNodes(r));
            return withHelperNodes(main.search(root, limits, wrapped));
        } finally {
            for (Searcher h : helpers) {
                h.stop();
            }
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private SearchResult withHelperNodes(SearchResult r) {
        long extra = 0;
        for (Searcher h : helpers) {
            extra += h.publishedNodes();
        }
        return extra == 0 ? r : new SearchResult(r.bestMove(), r.score(), r.depth(), r.nodes() + extra, r.timeMs(), r.pv());
    }
}
