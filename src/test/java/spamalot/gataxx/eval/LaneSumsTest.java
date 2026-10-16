package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;

/** The rank-at-a-time sums must equal the square-at-a-time ones exactly. */
class LaneSumsTest {
    @Test
    void laneSumsEqualTheGeneralSums() throws IOException {
        Evaluator features = new FeatureEvaluator(new FeatureEvaluator.Weights(FeatureEvaluator.SCALE, 4, 0, 0, 4, 0, 8, 32, 0, 0, 0, -3, 0));
        PatternEvaluator patterns = PatternEvaluator.builtIn(features, 0.65);
        PairEvaluator pairs = PairEvaluator.builtIn(patterns, 0.9);
        Random rnd = new Random(7);
        int[] moves = new int[Position.MAX_MOVES];
        int checked = 0;
        for (int game = 0; game < 300; game++) {
            Position p = Position.startPos();
            while (!p.isGameOver()) {
                long mine = p.pieces(p.sideToMove());
                long theirs = p.pieces(1 - p.sideToMove());
                assertEquals(patterns.sumSquares(p.empty(), mine, theirs), patterns.sumLanes(mine, theirs));
                assertEquals(pairs.patternSumBlocks(mine, theirs, 0), pairs.patternSumLanes(mine, theirs));
                checked++;
                int n = p.generateMoves(moves);
                p = p.makeMove(moves[rnd.nextInt(n)]);
            }
        }
        assertEquals(true, checked > 10_000);
    }
}
