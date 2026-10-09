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
 *   <li><b>corner</b>: pieces on the four corners (three neighbours);
 *   <li><b>ring1</b>: pieces one step in from the edge;
 *   <li><b>cohesion</b>: (piece, adjacent own piece) pairs, i.e. how compact a group is (weight in
 *       1/{@link #FINE} units; a negative weight, favouring spread-out groups, is what helps);
 *   <li><b>threat</b>: (piece, adjacent empty square the enemy can land on) pairs, a graded form of
 *       <i>reach</i>: how many conversions an enemy landing could cause (weight in 1/{@link #FINE} units);
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

    /**
     * The cohesion and threat weights are in 1/FINE of a score unit (1/64 piece), because their
     * adjacency counts are large and one whole unit is already a heavy weight.
     */
    public static final int FINE = 4;

    /** The outer ring of the board. */
    static final long EDGE;
    static final long CORNERS = (1L << 0) | (1L << 6) | (1L << 42) | (1L << 48);
    /** The ring one step in from the edge. */
    static final long RING1;

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
        long ring1 = 0;
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            int f = sq % Bitboards.SIZE;
            int r = sq / Bitboards.SIZE;
            if (Math.min(Math.min(f, Bitboards.SIZE - 1 - f), Math.min(r, Bitboards.SIZE - 1 - r)) == 1) {
                ring1 |= 1L << sq;
            }
        }
        RING1 = ring1;
    }

    /** Feature weights in score units. {@code material} is normally {@link #SCALE}. */
    public record Weights(int material, int safe, int mobility, int exposure, int reach, int territory,
                          int edge, int tempo, int fade, int corner, int ring1, int cohesion, int threat) {
        /** Without the corner/ring1/cohesion/threat features. */
        public Weights(int material, int safe, int mobility, int exposure, int reach, int territory,
                       int edge, int tempo, int fade) {
            this(material, safe, mobility, exposure, reach, territory, edge, tempo, fade, 0, 0, 0, 0);
        }

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
        if (w.reach() != 0 || w.territory() != 0 || w.threat() != 0) {
            long myReach = Bitboards.expand2(mine) & empty;
            long theirReach = Bitboards.expand2(theirs) & empty;
            if (w.reach() != 0) {
                // A piece is threatened if an empty square next to it can be reached by the other side.
                long myThreatened = mine & Bitboards.expand1(theirReach);
                long theirThreatened = theirs & Bitboards.expand1(myReach);
                positional += w.reach() * (Long.bitCount(mine) - Long.bitCount(myThreatened)
                        - Long.bitCount(theirs) + Long.bitCount(theirThreatened));
            }
            if (w.threat() != 0) {
                positional += w.threat() * (Bitboards.adjacentPairs(mine, theirReach)
                        - Bitboards.adjacentPairs(theirs, myReach)) / FINE;
            }
            if (w.territory() != 0) {
                positional += w.territory() * (Long.bitCount(myReach & ~theirReach) - Long.bitCount(theirReach & ~myReach));
            }
        }
        if (w.corner() != 0) {
            positional += w.corner() * (Long.bitCount(mine & CORNERS) - Long.bitCount(theirs & CORNERS));
        }
        if (w.ring1() != 0) {
            positional += w.ring1() * (Long.bitCount(mine & RING1) - Long.bitCount(theirs & RING1));
        }
        if (w.cohesion() != 0) {
            positional += w.cohesion() * (Bitboards.adjacentPairs(mine, mine)
                    - Bitboards.adjacentPairs(theirs, theirs)) / FINE;
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
