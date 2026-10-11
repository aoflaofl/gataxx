package spamalot.gataxx.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.LocalPatterns;

/**
 * Fits the {@link LocalPatterns} table to the scores another engine gave to positions ({@link Teacher}), by ridge regression
 * solved with preconditioned conjugate gradients on the sparse design (49 active features per position). Reports held-out
 * R-squared next to the plain feature-count model.
 *
 * <pre>
 * java -cp gataxx.jar spamalot.gataxx.tools.PatternFit --scores scores.tsv [--quiet 3] [--max-score 3000]
 *     [--ridge 1,10,100] [--blend 0.5] [--compare table.txt] [--out table.txt]
 * </pre>
 */
public final class PatternFit {
    /** A fitted table: one weight per {@link LocalPatterns} feature plus an intercept. */
    public record Model(double[] weights, double intercept, double ridge) {
        public double predict(int[] featureIndices) {
            double s = intercept;
            for (int idx : featureIndices) {
                if (idx >= 0) {
                    s += weights[idx];
                }
            }
            return s;
        }
    }

    /** The sparse design: for each position the 49 feature indices (-1 for walls), and its target score. */
    public record Data(int[][] idx, double[] y, int[] game) {
        public int size() {
            return y.length;
        }
    }

    private PatternFit() {}

    public static Data toData(List<Distill.Row> rows) {
        int n = rows.size();
        int[][] idx = new int[n][49];
        double[] y = new double[n];
        int[] game = new int[n];
        for (int i = 0; i < n; i++) {
            LocalPatterns.features(Position.fromFen(rows.get(i).fen()), idx[i]);
            y[i] = rows.get(i).score();
            game[i] = rows.get(i).game();
        }
        return new Data(idx, y, game);
    }

    public static Data subset(Data d, boolean even) {
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            if ((d.game()[i] % 2 == 0) == even) {
                keep.add(i);
            }
        }
        int[][] idx = new int[keep.size()][];
        double[] y = new double[keep.size()];
        int[] game = new int[keep.size()];
        for (int k = 0; k < keep.size(); k++) {
            idx[k] = d.idx()[keep.get(k)];
            y[k] = d.y()[keep.get(k)];
            game[k] = d.game()[keep.get(k)];
        }
        return new Data(idx, y, game);
    }

    /** Ridge regression (the intercept is not penalised) by preconditioned conjugate gradients. */
    public static Model fit(Data d, double ridge, int maxIterations) {
        int n = d.size();
        int k = LocalPatterns.SIZE;
        int cols = k + 1; // last column is the intercept
        double[] diag = new double[cols];
        double[] b = new double[cols];
        for (int i = 0; i < n; i++) {
            for (int j : d.idx()[i]) {
                if (j >= 0) {
                    diag[j] += 1;
                    b[j] += d.y()[i];
                }
            }
            diag[k] += 1;
            b[k] += d.y()[i];
        }
        for (int j = 0; j < k; j++) {
            diag[j] += ridge;
            if (diag[j] == 0) {
                diag[j] = 1;
            }
        }
        double[] w = new double[cols];
        double[] r = b.clone();
        double[] z = new double[cols];
        double[] p = new double[cols];
        for (int j = 0; j < cols; j++) {
            z[j] = r[j] / diag[j];
            p[j] = z[j];
        }
        double rz = dot(r, z);
        double[] ap = new double[cols];
        double[] xp = new double[n];
        double stop = 1e-10 * Math.max(1e-30, dot(b, b));
        for (int it = 0; it < maxIterations && dot(r, r) > stop; it++) {
            for (int i = 0; i < n; i++) {
                double s = p[k];
                for (int j : d.idx()[i]) {
                    if (j >= 0) {
                        s += p[j];
                    }
                }
                xp[i] = s;
            }
            java.util.Arrays.fill(ap, 0);
            for (int i = 0; i < n; i++) {
                for (int j : d.idx()[i]) {
                    if (j >= 0) {
                        ap[j] += xp[i];
                    }
                }
                ap[k] += xp[i];
            }
            for (int j = 0; j < k; j++) {
                ap[j] += ridge * p[j];
            }
            double alpha = rz / dot(p, ap);
            for (int j = 0; j < cols; j++) {
                w[j] += alpha * p[j];
                r[j] -= alpha * ap[j];
                z[j] = r[j] / diag[j];
            }
            double rzNew = dot(r, z);
            double beta = rzNew / rz;
            rz = rzNew;
            for (int j = 0; j < cols; j++) {
                p[j] = z[j] + beta * p[j];
            }
        }
        double[] weights = java.util.Arrays.copyOf(w, k);
        return new Model(weights, w[k], ridge);
    }

    /** Coefficient of determination of a model on data (with the data's own mean as reference). */
    public static double rSquared(Model m, Data d) {
        double mean = 0;
        for (double v : d.y()) {
            mean += v;
        }
        mean /= d.size();
        double sse = 0;
        double sst = 0;
        for (int i = 0; i < d.size(); i++) {
            double e = d.y()[i] - m.predict(d.idx()[i]);
            sse += e * e;
            sst += (d.y()[i] - mean) * (d.y()[i] - mean);
        }
        return sst == 0 ? 0 : 1 - sse / sst;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static LinearRegression unitsFit(List<Distill.Row> rows) {
        double[][] xe = new double[rows.size()][];
        for (int i = 0; i < xe.length; i++) {
            xe[i] = new double[] {1, rows.get(i).currentEval()};
        }
        return LinearRegression.fit(xe, rows.stream().mapToDouble(Distill.Row::score).toArray(), 1e-9);
    }

    /** The evaluation the table is blended with: the engine's default hand-weighted features. */
    static spamalot.gataxx.eval.Evaluator baseEvaluator() {
        return new spamalot.gataxx.eval.FeatureEvaluator(new spamalot.gataxx.eval.FeatureEvaluator.Weights(
                spamalot.gataxx.eval.FeatureEvaluator.SCALE, 4, 0, 0, 4, 0, 8, 32, 0, 0, 0, -3, 0));
    }

    /**
     * Targets for fitting the table as a residual of a blend: the evaluator plays {@code (1 - w) * base + w * (T - a) / b}, so
     * for it to predict the teacher the table must predict {@code a + (y - a) / w - b * (1 - w) / w * base}.
     */
    static Data retarget(Data d, List<Distill.Row> rows, double a, double b, double w) {
        double[] y = new double[d.size()];
        for (int i = 0; i < y.length; i++) {
            y[i] = a + (d.y()[i] - a) / w - b * (1 - w) / w * rows.get(i).currentEval();
        }
        return new Data(d.idx(), y, d.game());
    }

    private static double r2(double[] y, double[] pred) {
        double mean = 0;
        for (double v : y) {
            mean += v;
        }
        mean /= y.length;
        double sse = 0;
        double sst = 0;
        for (int i = 0; i < y.length; i++) {
            sse += (y[i] - pred[i]) * (y[i] - pred[i]);
            sst += (y[i] - mean) * (y[i] - mean);
        }
        return sst == 0 ? 0 : 1 - sse / sst;
    }

    public static void main(String[] args) throws IOException {
        List<Path> files = new ArrayList<>();
        int quiet = 3;
        double maxAbs = 3000;
        String ridges = "1,10,100";
        Path out = null;
        double blend = 0;
        Path compare = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--blend" -> blend = Double.parseDouble(args[++i]);
                case "--compare" -> compare = Path.of(args[++i]);
                case "--scores" -> files.add(Path.of(args[++i]));
                case "--quiet" -> quiet = Integer.parseInt(args[++i]);
                case "--max-score" -> maxAbs = Double.parseDouble(args[++i]);
                case "--ridge" -> ridges = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (files.isEmpty()) {
            System.err.println("usage: PatternFit --scores FILE [--scores FILE...] [--quiet N] [--max-score X] [--ridge a,b] [--out FILE]");
            System.exit(2);
        }
        List<String> lines = new ArrayList<>();
        for (Path f : files) {
            lines.addAll(Files.readAllLines(f));
        }
        List<Distill.Row> rows = Distill.load(lines, quiet, maxAbs);
        List<Distill.Row> train = new ArrayList<>();
        List<Distill.Row> test = new ArrayList<>();
        for (Distill.Row r : rows) {
            (r.game() % 2 == 0 ? train : test).add(r);
        }
        System.out.printf("%d positions (train %d, held-out %d)%n", rows.size(), train.size(), test.size());
        double[] ys = rows.stream().mapToDouble(Distill.Row::score).toArray();
        double mean = java.util.Arrays.stream(ys).average().orElse(0);
        System.out.printf("teacher score sd %.1f%n", Math.sqrt(java.util.Arrays.stream(ys).map(v -> (v - mean) * (v - mean)).sum() / ys.length));

        // Reference models on the same split.
        List<Integer> base = new ArrayList<>(List.of(0, 1, 4, 6, 9)); // material, safe, reach, edge, cohesion
        System.out.printf("%nheld-out R^2 of the plain feature-count model (material, safe, reach, edge, cohesion): %.4f%n",
                Distill.heldOut(train, test, base));

        Data dTrain = toData(train);
        Data dTest = toData(test);

        // Units: teacher = ua + ub * (our base evaluation), fitted on the training split.
        LinearRegression unitsTrain = unitsFit(train);
        double ua = unitsTrain.coefficients()[0];
        double ub = unitsTrain.coefficients()[1];
        double[] yTest = test.stream().mapToDouble(Distill.Row::score).toArray();
        double[] baseTest = test.stream().mapToDouble(Distill.Row::currentEval).toArray();
        double[] basePred = new double[yTest.length];
        for (int i = 0; i < basePred.length; i++) {
            basePred[i] = ua + ub * baseTest[i];
        }
        System.out.printf("held-out R^2 of the base evaluation alone (teacher = %.1f + %.2f * base): %.4f%n", ua, ub, r2(yTest, basePred));
        if (compare != null) {
            double a = blend > 0 ? blend : 0.5;
            try {
                spamalot.gataxx.eval.Evaluator handWeighted = baseEvaluator();
                spamalot.gataxx.eval.PatternEvaluator pe = spamalot.gataxx.eval.PatternEvaluator.load(compare, handWeighted, a);
                double[] pred = new double[yTest.length];
                for (int i = 0; i < pred.length; i++) {
                    pred[i] = ua + ub * pe.evaluate(Position.fromFen(test.get(i).fen()));
                }
                System.out.printf("held-out R^2 of %s blended at %.2f: %.4f%n", compare, a, r2(yTest, pred));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        Data fitTrain = blend > 0 ? retarget(dTrain, train, ua, ub, blend) : dTrain;
        Model best = null;
        double bestR2 = -1e9;
        System.out.printf("local-environment table (%d weights)%s:%n", LocalPatterns.SIZE,
                blend > 0 ? ", fitted as a residual for blend " + blend : "");
        for (String s : ridges.split(",")) {
            double lambda = Double.parseDouble(s);
            Model m = fit(fitTrain, lambda, 600);
            double score;
            if (blend > 0) {
                double[] pred = new double[yTest.length];
                for (int i = 0; i < pred.length; i++) {
                    pred[i] = ua + ub * (1 - blend) * baseTest[i] + blend * (m.predict(dTest.idx()[i]) - ua);
                }
                score = r2(yTest, pred);
                System.out.printf("  ridge %-6s blended held-out R^2 %.4f%n", s, score);
            } else {
                score = rSquared(m, dTest);
                System.out.printf("  ridge %-6s train R^2 %.4f   held-out R^2 %.4f%n", s, rSquared(m, dTrain), score);
            }
            if (score > bestR2) {
                bestR2 = score;
                best = m;
            }
        }
        if (out != null && best != null) {
            // How the teacher's units relate to ours: teacher = a + b * (our current evaluation).
            LinearRegression units = unitsFit(rows);
            Data all = toData(rows);
            Model full = fit(blend > 0 ? retarget(all, rows, units.coefficients()[0], units.coefficients()[1], blend) : all,
                    best.ridge(), 600);
            List<String> text = new ArrayList<>();
            text.add("# local-environment table fitted to teacher scores; ridge " + best.ridge() + "; positions " + rows.size()
                    + (blend > 0 ? "; residual fit for blend " + blend : ""));
            text.add("# intercept " + full.intercept());
            text.add("# units " + units.coefficients()[0] + " " + units.coefficients()[1]);
            for (int state = 0; state < 3; state++) {
                for (int cls = 0; cls < 10; cls++) {
                    for (int m = 0; m <= 8; m++) {
                        for (int t = 0; t <= 8; t++) {
                            int idx = LocalPatterns.index(state, cls, m, t);
                            if (idx >= 0) {
                                text.add(state + " " + cls + " " + m + " " + t + " " + full.weights()[idx]);
                            }
                        }
                    }
                }
            }
            Files.write(out, text);
            System.out.println("wrote " + out);
        }
    }
}
