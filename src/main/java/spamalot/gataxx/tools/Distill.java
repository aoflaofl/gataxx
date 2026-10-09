package spamalot.gataxx.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.FeatureEvaluator;

/**
 * Fits our evaluation features to the scores another engine ({@link Teacher}) gave to the same positions, by linear
 * regression, and reports held-out R-squared for the current evaluation, the current feature set, and the current set with
 * each candidate feature added. Shows how much of the teacher's judgement our features can express and which candidates
 * carry something the current set lacks.
 *
 * <pre>
 * java -cp gataxx.jar spamalot.gataxx.tools.Distill --scores scores.tsv [--quiet 3] [--max-score 3000]
 *     [--base material,safe,reach,edge,cohesion] [--candidates ring1,ring2,corner,holePure,...]
 * </pre>
 */
public final class Distill {
    /** One scored position. */
    public record Row(int game, String fen, double score, int[] raw, double currentEval) {}

    private static final String[] DEFAULT_CANDIDATES = {
        "mobility", "territory", "corner", "ring1", "ring2", "threat", "dense", "bites3", "bitesSq",
        "holePure", "holeAdj", "holeSq"
    };

    private Distill() {}

    /**
     * @param quiet if positive, drop positions where the side to move can convert at least this many pieces
     * @param maxAbsScore drop rows whose teacher score is larger in magnitude (engines report a proven win or loss as a
     *     huge constant, which would swamp a least-squares fit and says nothing about evaluation)
     */
    public static List<Row> load(List<String> lines, int quiet, double maxAbsScore) {
        FeatureEvaluator current = new FeatureEvaluator(Fit.currentWeights());
        int[] buf = new int[Position.MAX_MOVES];
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\t");
            if (f.length < 4) {
                continue;
            }
            if (Math.abs(Double.parseDouble(f[3])) > maxAbsScore) {
                continue;
            }
            Position pos = Position.fromFen(f[2]);
            if (pos.isGameOver() || (quiet > 0 && pos.generateCaptureMoves(buf, quiet) > 0)) {
                continue;
            }
            int[] raw = new int[FeatureEvaluator.FEATURE_NAMES.length];
            FeatureEvaluator.rawFeatures(pos, raw);
            rows.add(new Row(Integer.parseInt(f[0]), f[2], Double.parseDouble(f[3]), raw, current.evaluate(pos)));
        }
        return rows;
    }

    private static double[][] matrix(List<Row> rows, List<Integer> columns) {
        double[][] x = new double[rows.size()][];
        for (int i = 0; i < x.length; i++) {
            double[] r = new double[1 + columns.size()];
            r[0] = 1;
            for (int c = 0; c < columns.size(); c++) {
                r[1 + c] = value(rows.get(i), columns.get(c));
            }
            x[i] = r;
        }
        return x;
    }

    private static double[] scores(List<Row> rows) {
        return rows.stream().mapToDouble(Row::score).toArray();
    }

    /** Held-out R-squared of a model fitted on {@code train}. */
    static double heldOut(List<Row> train, List<Row> test, List<Integer> columns) {
        LinearRegression m = LinearRegression.fit(matrix(train, columns), scores(train), 1e-6);
        return m.rSquared(matrix(test, columns), scores(test));
    }

    /** Column codes at or above this mean "signed square of feature (code - OFFSET)": x * |x|, odd like the feature itself. */
    private static final int SQUARE_OFFSET = 1000;

    private static List<Integer> indices(String list) {
        List<String> names = Arrays.asList(FeatureEvaluator.FEATURE_NAMES);
        List<Integer> out = new ArrayList<>();
        for (String raw : list.split(",")) {
            String n = raw;
            boolean square = n.trim().endsWith("^2");
            if (square) {
                n = n.trim().substring(0, n.trim().length() - 2);
            }
            int i = names.indexOf(n.trim());
            if (i < 0) {
                throw new IllegalArgumentException("unknown feature " + n + "; known: " + names);
            }
            out.add(square ? SQUARE_OFFSET + i : i);
        }
        return out;
    }

    private static double value(Row row, int column) {
        if (column >= SQUARE_OFFSET) {
            double v = row.raw()[column - SQUARE_OFFSET];
            return v * Math.abs(v);
        }
        return row.raw()[column];
    }

    public static void main(String[] args) throws IOException {
        Path file = null;
        int quiet = 3;
        double maxAbsScore = 3000;
        String base = "material,safe,reach,edge,cohesion";
        String candidates = String.join(",", DEFAULT_CANDIDATES);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scores" -> file = Path.of(args[++i]);
                case "--quiet" -> quiet = Integer.parseInt(args[++i]);
                case "--max-score" -> maxAbsScore = Double.parseDouble(args[++i]);
                case "--base" -> base = args[++i];
                case "--candidates" -> candidates = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (file == null) {
            System.err.println("usage: Distill --scores FILE [--quiet N] [--base a,b] [--candidates c,d]");
            System.exit(2);
        }
        List<Row> rows = load(Files.readAllLines(file), quiet, maxAbsScore);
        List<Row> train = new ArrayList<>();
        List<Row> test = new ArrayList<>();
        for (Row r : rows) {
            (r.game() % 2 == 0 ? train : test).add(r);
        }
        double mean = rows.stream().mapToDouble(Row::score).average().orElse(0);
        double sd = Math.sqrt(rows.stream().mapToDouble(r -> (r.score() - mean) * (r.score() - mean)).sum() / rows.size());
        System.out.printf("%d positions (train %d, held-out %d), quiet>=%d; teacher score mean %.1f, sd %.1f%n",
                rows.size(), train.size(), test.size(), quiet, mean, sd);

        // The engine's current static evaluation as a one-parameter predictor.
        double[][] xe = new double[train.size()][];
        for (int i = 0; i < xe.length; i++) {
            xe[i] = new double[] {1, train.get(i).currentEval()};
        }
        LinearRegression cur = LinearRegression.fit(xe, scores(train), 1e-6);
        double[][] xet = new double[test.size()][];
        for (int i = 0; i < xet.length; i++) {
            xet[i] = new double[] {1, test.get(i).currentEval()};
        }
        System.out.printf("%nheld-out R^2:%n  our current static evaluation (1 scale + intercept)   %.4f%n", cur.rSquared(xet, scores(test)));

        List<Integer> baseCols = indices(base);
        double baseR2 = heldOut(train, test, baseCols);
        System.out.printf("  base features [%s]%n      %.4f   (this is what refitting our own features can reach)%n", base, baseR2);
        Map<String, Double> gains = new LinkedHashMap<>();
        if (candidates.equals("none")) {
            candidates = "";
        }
        for (String c : candidates.isEmpty() ? new String[0] : candidates.split(",")) {
            List<Integer> cols = new ArrayList<>(baseCols);
            cols.addAll(indices(c));
            gains.put(c.trim(), heldOut(train, test, cols) - baseR2);
        }
        System.out.printf("%nR^2 gained by adding one candidate to the base set:%n");
        gains.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %-10s %+.4f%n", e.getKey(), e.getValue()));

        List<Integer> all = new ArrayList<>(baseCols);
        for (String c : candidates.isEmpty() ? new String[0] : candidates.split(",")) {
            all.addAll(indices(c));
        }
        double allR2 = heldOut(train, test, all);
        System.out.printf("%nbase + all candidates: held-out R^2 %.4f%n", allR2);
        LinearRegression full = LinearRegression.fit(matrix(rows, all), scores(rows), 1e-6);
        double[] c = full.coefficients();
        double[] se = full.standardErrors();
        int matIdx = all.indexOf(0);
        double scale = matIdx >= 0 ? 16.0 / c[1 + matIdx] : 1;
        System.out.printf("%n%-10s %12s %8s   %s%n", "term", "coef", "z", "x16/material (1/16 piece; cohesion in 1/64: x4)");
        System.out.printf("%-10s %12.3f %8.1f%n", "intercept", c[0], c[0] / se[0]);
        for (int k = 0; k < all.size(); k++) {
            int col = all.get(k);
            String name = col >= SQUARE_OFFSET ? FeatureEvaluator.FEATURE_NAMES[col - SQUARE_OFFSET] + "^2" : FeatureEvaluator.FEATURE_NAMES[col];
            System.out.printf("%-10s %12.3f %8.1f   %8.2f%n", name, c[1 + k], c[1 + k] / se[1 + k], c[1 + k] * scale);
        }
    }
}
