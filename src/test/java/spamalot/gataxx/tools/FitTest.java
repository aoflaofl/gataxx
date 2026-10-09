package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;

class FitTest {
    /** A short game: x clones, o clones, ... then the file claims x won. Replay is by moves, result by the file. */
    private static final String GAME = Position.START_FEN + " | engine1=x | 1-0 | game over | b6 f2 c6 f3 d6 f4";

    @Test
    void labelsAreFromTheSideToMovesPointOfView() {
        List<Fit.Sample> s = Fit.load(List.of(GAME), 0, 0, 1, 0);
        assertEquals(6, s.size());
        for (int i = 0; i < s.size(); i++) {
            boolean xToMove = i % 2 == 0;
            assertEquals(xToMove ? 1.0 : 0.0, s.get(i).label(), "ply " + i);
        }
        assertEquals(0.5, Fit.load(List.of(GAME.replace("1-0", "1/2-1/2")), 0, 0, 1, 0).get(0).label());
        assertEquals(0.0, Fit.load(List.of(GAME.replace("1-0", "0-1")), 0, 0, 1, 0).get(0).label());
    }

    @Test
    void rawFeaturesAreForTheSideToMoveAndEmptiesAreCounted() {
        List<Fit.Sample> s = Fit.load(List.of(GAME), 0, 0, 1, 0);
        assertEquals(0, s.get(0).raw()[0]); // start position: equal material
        assertEquals(45, s.get(0).empties());
        assertEquals(-1, s.get(1).raw()[0]); // after x's clone, o (to move) is one piece down
        assertEquals(44, s.get(1).empties());
    }

    @Test
    void minPlyAndEverySubsampleAndOnlyFinishedGamesCount() {
        assertEquals(4, Fit.load(List.of(GAME), 2, 0, 1, 0).size());
        assertEquals(2, Fit.load(List.of(GAME), 2, 0, 2, 0).size());
        assertEquals(0, Fit.load(List.of(GAME.replace("game over", "timeout")), 0, 0, 1, 0).size());
        String other = GAME.replace("b6 f2 c6 f3 d6 f4", "a6 f2 b6 f3 c6 f4");
        List<Fit.Sample> two = Fit.load(List.of(GAME, other), 0, 0, 1, 0);
        assertEquals(2, two.stream().mapToInt(Fit.Sample::game).distinct().count());
    }

    @Test
    void identicalGamesAreCountedOnce() {
        // The same game recorded from both colours (as two identical engines produce) must not appear twice.
        String swapped = GAME.replace("engine1=x", "engine1=o");
        assertEquals(6, Fit.load(List.of(GAME, swapped), 0, 0, 1, 0).size());
    }

    @Test
    void quietFilterDropsPositionsWithBigCaptures() {
        // x to move can clone to a position converting three o pieces: not quiet at threshold 3.
        String fen = "7/7/7/3oo2/2o4/7/1x5 x 0 1";
        String game = fen + " | engine1=x | 1-0 | game over | b2";
        assertEquals(1, Fit.load(List.of(game), 0, 0, 1, 0).size());
        assertEquals(0, Fit.load(List.of(game), 0, 3, 1, 0).size());
    }

    @Test
    void currentEvaluationBaselineIsTheEnginesDefaults() {
        var w = Fit.currentWeights();
        assertEquals(spamalot.gataxx.eval.FeatureEvaluator.SCALE, w.material());
        assertEquals(spamalot.gataxx.uai.UaiEngine.DEFAULT_EVAL_SAFE, w.safe());
        assertEquals(spamalot.gataxx.uai.UaiEngine.DEFAULT_EVAL_REACH, w.reach());
        assertEquals(spamalot.gataxx.uai.UaiEngine.DEFAULT_EVAL_EDGE, w.edge());
        assertEquals(spamalot.gataxx.uai.UaiEngine.DEFAULT_EVAL_COHESION, w.cohesion());
        assertEquals(spamalot.gataxx.uai.UaiEngine.DEFAULT_TEMPO, w.tempo());
        assertEquals(0, w.mobility() + w.exposure() + w.territory() + w.corner() + w.ring1() + w.threat() + w.fade());
    }

    @Test
    void distillDropsDecidedPositionsAndNonQuietOnes() {
        String quietFen = Position.START_FEN;
        String bigCapture = "7/7/7/3oo2/2o4/7/1x5 x 0 1"; // x can convert three pieces at d3
        List<String> rows = List.of(
                "1\t10\t" + quietFen + "\t120\t11\tb6",
                "1\t11\t" + quietFen + "\t100000\t11\tb6",
                "2\t12\t" + quietFen + "\t-100000\t11\tb6",
                "2\t13\t" + bigCapture + "\t80\t11\td3");
        assertEquals(1, Distill.load(rows, 3, 3000).size(), "decided and non-quiet rows dropped");
        assertEquals(2, Distill.load(rows, 0, 3000).size(), "quiet filter off keeps the capture position");
        assertEquals(4, Distill.load(rows, 0, 1_000_000).size(), "no score cutoff keeps everything");
    }
}
