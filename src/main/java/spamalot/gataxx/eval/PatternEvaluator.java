package spamalot.gataxx.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import spamalot.gataxx.board.Bitboards;
import spamalot.gataxx.board.Position;

/**
 * Blends a base evaluation with a learned {@link LocalPatterns} table: {@code (1 - blend) * base + blend * table}, where the
 * table's output is first converted to the base evaluation's units. The table file is written by
 * {@code tools.PatternFit}: weights per feature, an intercept, and the regression {@code teacher = a + b * base} that links
 * the teacher's units to ours.
 */
public final class PatternEvaluator implements Evaluator {
    private final Evaluator base;
    private static final int SCALE = 256;
    private static final int CELL = 81;
    private static final int STATE_STRIDE = 10 * CELL;

    /** Weights in 1/{@link #SCALE} units, laid out as {@code state * 810 + squareClass * 81 + mine * 9 + theirs}. */
    private final int[] flat = new int[3 * STATE_STRIDE];
    private final double blend;
    private int margin;
    private final double perUnit;
    private final double offset;

    public PatternEvaluator(Evaluator base, double[] weights, double intercept, double unitsA, double unitsB, double blend) {
        if (weights.length != LocalPatterns.SIZE) {
            throw new IllegalArgumentException("table needs " + LocalPatterns.SIZE + " weights, got " + weights.length);
        }
        if (blend < 0 || blend > 1 || unitsB == 0) {
            throw new IllegalArgumentException("blend must be in [0, 1] and units must be non-degenerate");
        }
        this.base = base;
        this.blend = blend;
        this.perUnit = blend / (unitsB * SCALE);
        this.offset = blend * (intercept - unitsA) / unitsB;
        for (int state = 0; state < 3; state++) {
            for (int cls = 0; cls < 10; cls++) {
                for (int m = 0; m <= 8; m++) {
                    for (int t = 0; t <= 8; t++) {
                        int j = LocalPatterns.index(state, cls, m, t);
                        if (j >= 0) {
                            flat[state * STATE_STRIDE + cls * CELL + m * 9 + t] = (int) Math.round(weights[j] * SCALE);
                        }
                    }
                }
            }
        }
    }

    /** Loads a table written by {@code PatternFit}. */
    public static PatternEvaluator load(Path file, Evaluator base, double blend) throws IOException {
        double[] w = new double[LocalPatterns.SIZE];
        boolean[] seen = new boolean[LocalPatterns.SIZE];
        double intercept = 0;
        double a = 0;
        double b = 1;
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (line.startsWith("# intercept ")) {
                intercept = Double.parseDouble(t[2]);
            } else if (line.startsWith("# units ")) {
                a = Double.parseDouble(t[2]);
                b = Double.parseDouble(t[3]);
            } else if (!line.startsWith("#") && t.length == 5) {
                int idx = LocalPatterns.index(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                if (idx < 0) {
                    throw new IOException("impossible pattern in table: " + line);
                }
                w[idx] = Double.parseDouble(t[4]);
                seen[idx] = true;
            }
        }
        for (boolean s : seen) {
            if (!s) {
                throw new IOException("table is incomplete: " + file);
            }
        }
        return new PatternEvaluator(base, w, intercept, a, b, blend);
    }

    /**
     * Lazy evaluation: when the base score is at least {@code margin} outside the search window the table is skipped and the
     * base score returned. 0 (the default) always applies the table.
     */
    public PatternEvaluator withMargin(int margin) {
        this.margin = margin;
        return this;
    }

    @Override
    public int evaluate(Position pos, int alpha, int beta) {
        int b = base.evaluate(pos);
        if (margin > 0) {
            if (b - margin >= beta || b + margin <= alpha) {
                return b;
            }
        }
        return blended(pos, b);
    }

    @Override
    public int evaluate(Position pos) {
        return blended(pos, base.evaluate(pos));
    }

    private int blended(Position pos, int b) {
        if (blend == 0) {
            return b;
        }
        long mine = pos.pieces(pos.sideToMove());
        long theirs = pos.pieces(1 - pos.sideToMove());
        long playable = ~pos.walls() & Bitboards.ALL;
        int sum = 0;
        for (long rest = playable; rest != 0; rest &= rest - 1) {
            int sq = Long.numberOfTrailingZeros(rest);
            long around = AROUND[sq];
            int state = (int) (mine >>> sq & 1) + 2 * (int) (theirs >>> sq & 1);
            sum += flat[state * STATE_STRIDE + CLASS_OFFSET[sq] + 9 * Long.bitCount(around & mine) + Long.bitCount(around & theirs)];
        }
        return (int) Math.round((1 - blend) * b + perUnit * sum + offset);
    }

    private static final long[] AROUND = new long[Bitboards.SQUARES];
    private static final int[] CLASS_OFFSET = new int[Bitboards.SQUARES];

    static {
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            AROUND[sq] = Bitboards.neighbours(sq);
            CLASS_OFFSET[sq] = FeatureEvaluator.SQUARE_CLASS[sq] * CELL;
        }
    }
}
