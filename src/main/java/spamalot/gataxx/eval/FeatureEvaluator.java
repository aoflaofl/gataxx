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
 *   <li><b>reach</b>: pieces that no enemy can threaten next move, i.e. with no empty neighbour that
 *       an enemy piece can land on by cloning or jumping (a looser, more precise form of "safe");
 *   <li><b>territory</b>: empty squares only this side can reach in one move; they tend to be
 *       filled by that side before the game ends;
 *   <li><b>edge</b>: pieces on the outer ring, which have fewer neighbours to be captured through;
 *   <li><b>tempo</b>: a flat bonus for the side to move.
 * </ul>
 *
 * <p>With {@code fade > 0} every positional feature (everything except material and tempo) is scaled
 * by {@code min(empty squares, fade) / fade}, so near the end of the game, when only the final piece
 * count matters, the score becomes plain material.
 */
public final class FeatureEvaluator implements Evaluator {
    /** Score units per piece. */
    public static final int SCALE = 16;

    /** The outer ring of the board. */
    static final long EDGE;

    static {
        long edge = 0;
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            int f = sq % Bitboards.SIZE;
            int r = sq / Bitboards.SIZE;
            if (f == 0 || f == Bitboards.SIZE - 1 || r == 0 || r == Bitboards.SIZE - 1) {
                edge |= 1L << sq;
            }
        }
        EDGE = edge;
    }

    /** Feature weights in score units. {@code material} is normally {@link #SCALE}. */
    public record Weights(int material, int safe, int mobility, int exposure, int reach, int territory,
                          int edge, int tempo, int fade) {
        /** Without fading: the positional weights apply in full all game. */
        public Weights(int material, int safe, int mobility, int exposure, int reach, int territory,
                       int edge, int tempo) {
            this(material, safe, mobility, exposure, reach, territory, edge, tempo, 0);
        }

        /** The original five-feature form; the newer features are off. */
        public Weights(int material, int safe, int mobility, int exposure, int tempo) {
            this(material, safe, mobility, exposure, 0, 0, 0, tempo);
        }

        /** Plain material plus a two-piece tempo bonus. */
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
        int positional = 0;
        long empty = pos.empty();
        if (w.safe() != 0) {
            long exposedZone = Bitboards.expand1(empty);
            positional += w.safe() * (Long.bitCount(mine & ~exposedZone) - Long.bitCount(theirs & ~exposedZone));
        }
        if (w.mobility() != 0) {
            positional += w.mobility() * (Long.bitCount(Bitboards.expand1(mine) & empty)
                    - Long.bitCount(Bitboards.expand1(theirs) & empty));
        }
        if (w.exposure() != 0) {
            positional += w.exposure() * (Bitboards.adjacentPairs(mine, empty) - Bitboards.adjacentPairs(theirs, empty));
        }
        if (w.reach() != 0 || w.territory() != 0) {
            long myReach = Bitboards.expand2(mine) & empty;
            long theirReach = Bitboards.expand2(theirs) & empty;
            if (w.reach() != 0) {
                // A piece is threatened if an empty square next to it can be reached by the other side.
                long myThreatened = mine & Bitboards.expand1(theirReach);
                long theirThreatened = theirs & Bitboards.expand1(myReach);
                positional += w.reach() * (Long.bitCount(mine) - Long.bitCount(myThreatened)
                        - Long.bitCount(theirs) + Long.bitCount(theirThreatened));
            }
            if (w.territory() != 0) {
                positional += w.territory() * (Long.bitCount(myReach & ~theirReach) - Long.bitCount(theirReach & ~myReach));
            }
        }
        if (w.edge() != 0) {
            positional += w.edge() * (Long.bitCount(mine & EDGE) - Long.bitCount(theirs & EDGE));
        }
        if (w.fade() > 0) {
            positional = positional * Math.min(Long.bitCount(empty), w.fade()) / w.fade();
        }
        return score + positional;
    }
}
