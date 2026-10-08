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
}
