package spamalot.gataxx.search;

/** Receives a result after every completed iterative-deepening iteration. */
@FunctionalInterface
public interface SearchListener {
    void onIteration(SearchResult result);
}
