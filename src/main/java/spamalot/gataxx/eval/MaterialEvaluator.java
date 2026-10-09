package spamalot.gataxx.eval;

import spamalot.gataxx.board.Position;

/**
 * Piece-count difference, the baseline evaluation in Ataxx, where material is the objective.
 *
 * <p>An optional tempo bonus is added for the side to move. In Ataxx the mover can nearly always
 * clone for at least one extra piece, so a bare piece count undervalues the side to move and the
 * search score swings with the parity of the depth reached.
 */
public final class MaterialEvaluator implements Evaluator {
    private final int tempo;

    public MaterialEvaluator() {
        this(0);
    }

    public MaterialEvaluator(int tempo) {
        this.tempo = tempo;
    }

    @Override
    public int evaluate(Position pos) {
        int me = pos.sideToMove();
        return pos.count(me) - pos.count(1 - me) + tempo;
    }
}
