package spamalot.gataxx.search;

/**
 * Constraints on a search. Zero means "no limit" for every field.
 *
 * @param maxDepth deepest iteration to run
 * @param softMs do not start a new iteration once this much time has elapsed
 * @param hardMs abort the search (discarding the unfinished iteration) at this much time
 * @param maxNodes abort the search after roughly this many nodes
 */
public record SearchLimits(int maxDepth, long softMs, long hardMs, long maxNodes) {
    public SearchLimits {
        if (maxDepth < 0 || softMs < 0 || hardMs < 0 || maxNodes < 0) {
            throw new IllegalArgumentException("limits must not be negative");
        }
    }

    public static SearchLimits infinite() {
        return new SearchLimits(0, 0, 0, 0);
    }

    public static SearchLimits depth(int depth) {
        return new SearchLimits(depth, 0, 0, 0);
    }

    public static SearchLimits movetime(long ms) {
        return new SearchLimits(0, ms, ms, 0);
    }

    public static SearchLimits nodes(long nodes) {
        return new SearchLimits(0, 0, 0, nodes);
    }
}
