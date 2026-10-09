package spamalot.gataxx.eval;

import spamalot.gataxx.board.Bitboards;
import spamalot.gataxx.board.Position;

/**
 * Weighted sum of simple board features, from the side to move's point of view. Scores are in
 * 1/{@link #SCALE} of a piece, so small weights are possible.
 *
 * <ul>
 *   <li><b>material</b>: own pieces minus enemy pieces;
 *   <li><b>safe</b>: pieces with no empty neighbour, which can't be converted (a conversion needs
 *       an enemy piece to land on an adjacent empty square);
 *   <li><b>mobility</b>: distinct empty squares a clone could reach;
 *   <li><b>exposure</b>: (piece, adjacent empty square) pairs, a measure of how open a group is;
 *   <li><b>tempo</b>: a flat bonus for the side to move.
 * </ul>
 */
public final class FeatureEvaluator implements Evaluator {
    /** Score units per piece. */
    public static final int SCALE = 16;

    /** Feature weights in score units. {@code material} is normally {@link #SCALE}. */
    public record Weights(int material, int safe, int mobility, int exposure, int tempo) {
        /** Plain material plus a two-piece tempo bonus: the tuned baseline before these features. */
        public static Weights baseline() {
            return new Weights(SCALE, 0, 0, 0, 2 * SCALE);
        }
    }

    private final Weights w;

    public FeatureEvaluator(Weights weights) {
        this.w = weights;
    }

    @Override
    public int evaluate(Position pos) {
        int me = pos.sideToMove();
        long mine = pos.pieces(me);
        long theirs = pos.pieces(1 - me);
        int score = w.tempo() + w.material() * (Long.bitCount(mine) - Long.bitCount(theirs));
        if (w.safe() == 0 && w.mobility() == 0 && w.exposure() == 0) {
            return score;
        }
        long empty = pos.empty();
        if (w.safe() != 0) {
            long exposedZone = Bitboards.expand1(empty);
            score += w.safe() * (Long.bitCount(mine & ~exposedZone) - Long.bitCount(theirs & ~exposedZone));
        }
        if (w.mobility() != 0) {
            score += w.mobility() * (Long.bitCount(Bitboards.expand1(mine) & empty)
                    - Long.bitCount(Bitboards.expand1(theirs) & empty));
        }
        if (w.exposure() != 0) {
            score += w.exposure() * (Bitboards.adjacentPairs(mine, empty) - Bitboards.adjacentPairs(theirs, empty));
        }
        return score;
    }
}
