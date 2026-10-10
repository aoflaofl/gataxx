package spamalot.gataxx.eval;

import spamalot.gataxx.board.Bitboards;
import spamalot.gataxx.board.Position;

/**
 * Feature indices for a table-driven "local environment" evaluation. Every square is described, from the side to move's point
 * of view, by:
 *
 * <ul>
 *   <li>its state: empty, a friendly stone or an enemy stone;
 *   <li>its square type among the ten kinds under the board's symmetries (corner, then by distance from the nearest edges);
 *   <li>how many of its (up to eight) neighbours are friendly and how many are enemy stones (the rest are empty).
 * </ul>
 *
 * Summing a learned weight per square gives a score. It contains the earlier counting features as special cases: safe stones
 * are stones with no empty neighbour, holes are empty squares with no empty neighbour, cohesion and exposure are neighbour
 * counts, and regions are the square types. Walls (not used in real games) carry no feature.
 */
public final class LocalPatterns {
    public static final int EMPTY = 0;
    public static final int MINE = 1;
    public static final int THEIRS = 2;

    /** Number of distinct features. */
    public static final int SIZE;

    private static final int[][][][] INDEX = new int[3][10][9][9];

    static {
        int k = 0;
        for (int state = 0; state < 3; state++) {
            for (int cls = 0; cls < 10; cls++) {
                int n = neighbourCount(cls);
                for (int m = 0; m <= 8; m++) {
                    for (int t = 0; t <= 8; t++) {
                        INDEX[state][cls][m][t] = m + t <= n ? k++ : -1;
                    }
                }
            }
        }
        SIZE = k;
    }

    private LocalPatterns() {}

    /** On-board neighbours of a square of the given type: 3 for a corner, 5 along an edge, 8 elsewhere. */
    static int neighbourCount(int squareClass) {
        return squareClass == 0 ? 3 : squareClass <= 3 ? 5 : 8;
    }

    /** The feature index for a state, square type and neighbour counts, or -1 if that combination is impossible. */
    public static int index(int state, int squareClass, int mine, int theirs) {
        return INDEX[state][squareClass][mine][theirs];
    }

    /**
     * Fills {@code out[0..48]} with the feature index of every square for the side to move, or -1 for a wall.
     *
     * @return the number of squares that carry a feature
     */
    public static int features(Position pos, int[] out) {
        long mine = pos.pieces(pos.sideToMove());
        long theirs = pos.pieces(1 - pos.sideToMove());
        long walls = pos.walls();
        int n = 0;
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            long bit = 1L << sq;
            if ((walls & bit) != 0) {
                out[sq] = -1;
                continue;
            }
            long around = Bitboards.neighbours(sq);
            int state = (mine & bit) != 0 ? MINE : (theirs & bit) != 0 ? THEIRS : EMPTY;
            out[sq] = INDEX[state][FeatureEvaluator.SQUARE_CLASS[sq]][Long.bitCount(around & mine)][Long.bitCount(around & theirs)];
            n++;
        }
        return n;
    }
}
