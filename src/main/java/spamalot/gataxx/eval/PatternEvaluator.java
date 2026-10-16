package spamalot.gataxx.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    /** The same weights indexed {@code square * 243 + state * 81 + mine * 9 + theirs}, for {@link #sumLanes}. */
    private final int[] bySquare = new int[Bitboards.SQUARES * 3 * CELL];
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
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            for (int state = 0; state < 3; state++) {
                for (int c = 0; c < CELL; c++) {
                    bySquare[sq * 3 * CELL + state * CELL + c] = flat[state * STATE_STRIDE + CLASS_OFFSET[sq] + c];
                }
            }
        }
    }

    /** Resource holding the table that ships with the engine. */
    static final String BUILT_IN = "pattern-table.txt";

    /** Loads a table written by {@code PatternFit}. */
    public static PatternEvaluator load(Path file, Evaluator base, double blend) throws IOException {
        return parse(Files.readAllLines(file), file.toString(), base, blend);
    }

    /** Loads the table that ships inside the jar. */
    public static PatternEvaluator builtIn(Evaluator base, double blend) throws IOException {
        try (InputStream in = PatternEvaluator.class.getResourceAsStream(BUILT_IN)) {
            if (in == null) {
                throw new IOException("built-in pattern table is missing from the jar");
            }
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
            return parse(lines, "built-in table", base, blend);
        }
    }

    private static PatternEvaluator parse(List<String> lines, String source, Evaluator base, double blend) throws IOException {
        double[] w = new double[LocalPatterns.SIZE];
        boolean[] seen = new boolean[LocalPatterns.SIZE];
        double intercept = 0;
        double a = 0;
        double b = 1;
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
                throw new IOException("table is incomplete: " + source);
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
        int sum = pos.walls() == 0 ? sumLanes(mine, theirs)
                : sumSquares(pos.empty(), mine, theirs);
        return (int) Math.round((1 - blend) * b + perUnit * sum + offset);
    }

    /** The table sum by walking the squares one at a time (the general case, with walls). */
    int sumSquares(long empty, long mine, long theirs) {
        return sumOver(empty, mine, theirs, 0) + sumOver(mine, mine, theirs, STATE_STRIDE)
                + sumOver(theirs, mine, theirs, 2 * STATE_STRIDE);
    }

    /**
     * The same sum for a board without walls, a rank at a time: the neighbour counts of all seven squares of a rank are
     * added up in byte lanes, together with the square's state, into the table index of each lane.
     */
    int sumLanes(long mine, long theirs) {
        long m0 = Lanes.rank(mine, 0);
        long m1 = Lanes.rank(mine, 1);
        long m2 = Lanes.rank(mine, 2);
        long m3 = Lanes.rank(mine, 3);
        long m4 = Lanes.rank(mine, 4);
        long m5 = Lanes.rank(mine, 5);
        long m6 = Lanes.rank(mine, 6);
        long t0 = Lanes.rank(theirs, 0);
        long t1 = Lanes.rank(theirs, 1);
        long t2 = Lanes.rank(theirs, 2);
        long t3 = Lanes.rank(theirs, 3);
        long t4 = Lanes.rank(theirs, 4);
        long t5 = Lanes.rank(theirs, 5);
        long t6 = Lanes.rank(theirs, 6);
        return rankSum(0, 0, m0, m1, 0, t0, t1) + rankSum(1, m0, m1, m2, t0, t1, t2) + rankSum(2, m1, m2, m3, t1, t2, t3)
                + rankSum(3, m2, m3, m4, t2, t3, t4) + rankSum(4, m3, m4, m5, t3, t4, t5)
                + rankSum(5, m4, m5, m6, t4, t5, t6) + rankSum(6, m5, m6, 0, t5, t6, 0);
    }

    private int rankSum(int rank, long mAbove, long mHere, long mBelow, long tAbove, long tHere, long tBelow) {
        long vm = mAbove + mHere + mBelow;
        long nm = (vm << 8) + vm + (vm >>> 8) - mHere;
        long vt = tAbove + tHere + tBelow;
        long nt = (vt << 8) + vt + (vt >>> 8) - tHere;
        long index = mHere * CELL + tHere * (2 * CELL) + nm * 9 + nt;
        int at = rank * 7 * 3 * CELL;
        int sum = 0;
        for (int j = 0; j < 7; j++) {
            sum += bySquare[at + j * 3 * CELL + (int) (index >>> (8 * j) & 0xFF)];
        }
        return sum;
    }

    /** Sum of the weights of the squares in {@code squares}, all of which are in the state whose weights start at {@code base}. */
    private int sumOver(long squares, long mine, long theirs, int base) {
        int sum = 0;
        for (long rest = squares; rest != 0; rest &= rest - 1) {
            int sq = Long.numberOfTrailingZeros(rest);
            long around = AROUND[sq];
            sum += flat[base + CLASS_OFFSET[sq] + 9 * Long.bitCount(around & mine) + Long.bitCount(around & theirs)];
        }
        return sum;
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
