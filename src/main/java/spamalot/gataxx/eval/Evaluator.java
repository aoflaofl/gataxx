package spamalot.gataxx.eval;

import spamalot.gataxx.board.Position;

/** Static evaluation of a position. */
@FunctionalInterface
public interface Evaluator {
    /**
     * Score of {@code pos} from the point of view of the side to move: positive is good for them.
     * Only called on positions where the game is not over.
     */
    int evaluate(Position pos);

    /**
     * Like {@link #evaluate}, but the caller only needs the exact value when it falls inside {@code (alpha, beta)}; an
     * implementation may return any cheaper estimate that is on the same side of the window when the answer is clearly
     * outside it.
     */
    default int evaluate(Position pos, int alpha, int beta) {
        return evaluate(pos);
    }
}
