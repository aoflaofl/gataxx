package spamalot.gataxx.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import spamalot.gataxx.board.Position;

/**
 * Blends a base evaluation with a learned {@link LocalPatterns} table: {@code (1 - blend) * base + blend * table}, where the
 * table's output is first converted to the base evaluation's units. The table file is written by
 * {@code tools.PatternFit}: weights per feature, an intercept, and the regression {@code teacher = a + b * base} that links
 * the teacher's units to ours.
 */
public final class PatternEvaluator implements Evaluator {
    private final Evaluator base;
    private final double[] weights;
    private final double intercept;
    private final double unitsA;
    private final double unitsB;
    private final double blend;
    private final int[] idx = new int[49];

    public PatternEvaluator(Evaluator base, double[] weights, double intercept, double unitsA, double unitsB, double blend) {
        if (weights.length != LocalPatterns.SIZE) {
            throw new IllegalArgumentException("table needs " + LocalPatterns.SIZE + " weights, got " + weights.length);
        }
        if (blend < 0 || blend > 1 || unitsB == 0) {
            throw new IllegalArgumentException("blend must be in [0, 1] and units must be non-degenerate");
        }
        this.base = base;
        this.weights = weights;
        this.intercept = intercept;
        this.unitsA = unitsA;
        this.unitsB = unitsB;
        this.blend = blend;
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

    @Override
    public int evaluate(Position pos) {
        int b = base.evaluate(pos);
        if (blend == 0) {
            return b;
        }
        LocalPatterns.features(pos, idx);
        double t = intercept;
        for (int j : idx) {
            if (j >= 0) {
                t += weights[j];
            }
        }
        double inOurUnits = (t - unitsA) / unitsB;
        return (int) Math.round((1 - blend) * b + blend * inOurUnits);
    }
}
