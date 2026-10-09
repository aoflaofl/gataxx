package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;

class MaterialEvaluatorTest {
    private final Evaluator eval = new MaterialEvaluator();

    @Test
    void startIsEven() {
        assertEquals(0, eval.evaluate(Position.startPos()));
    }

    @Test
    void scoredFromSideToMove() {
        Position afterX = Position.startPos().makeMove(Move.parse("b6")); // x 3, o 2, o to move
        assertEquals(-1, eval.evaluate(afterX));
        Position afterJump = afterX.makeMove(Move.parse("a1a3")); // o jumps: still 3 v 2, x to move
        assertEquals(1, eval.evaluate(afterJump));
    }

    @Test
    void tempoBonusGoesToSideToMove() {
        Evaluator tempo = new MaterialEvaluator(2);
        assertEquals(2, tempo.evaluate(Position.startPos()));
        Position afterX = Position.startPos().makeMove(Move.parse("b6")); // o to move, down one piece
        assertEquals(-1 + 2, tempo.evaluate(afterX));
    }

    @Test
    void capturesSwingTheCount() {
        Position p = Position.fromFen("6o/7/7/7/7/oo5/x6 x 0 1");
        Position q = p.makeMove(Move.parse("b1")); // converts a2 and b2: x 4, o 1
        assertEquals(-3, eval.evaluate(q));
    }
}
