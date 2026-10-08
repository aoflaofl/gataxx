package spamalot.gataxx.eval;

import spamalot.gataxx.board.Position;

/** Piece-count difference. A strong baseline in Ataxx, where material is the objective. */
public final class MaterialEvaluator implements Evaluator {
    @Override
    public int evaluate(Position pos) {
        int me = pos.sideToMove();
        return pos.count(me) - pos.count(1 - me);
    }
}
