package spamalot.gataxx.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.FeatureEvaluator;
import spamalot.gataxx.eval.FeatureEvaluator.Weights;
import spamalot.gataxx.uai.UaiEngine;

/**
 * Offline analysis of evaluation features: replays games saved by {@code Match --out}, samples quiet positions,
 * and fits a logistic regression of the eventual result (from the side to move's point of view) on the raw
 * feature differences. Shows which features carry signal, what weights they would get, and how the current
 * evaluation compares as a predictor. A guide for choosing what to try in matches, not a substitute for them.
 *
 * <pre>
 * java -cp gataxx.jar spamalot.gataxx.tools.Fit --games games.txt [--games more.txt] [--min-ply 10] [--quiet 3]
 *     [--features material,safe,reach,edge,cohesion] [--phase] [--every 1]
 * </pre>
 */
public final class Fit {
    /** One sampled position. */
    public record Sample(int[] raw, int empties, double label, int game, double currentEval) {}

    private Fit() {}

    /** The evaluation weights the engine plays with, for the "current evaluation" baseline predictor. */
    static Weights currentWeights() {
        return new Weights(FeatureEvaluator.SCALE, UaiEngine.DEFAULT_EVAL_SAFE, 0, 0, UaiEngine.DEFAULT_EVAL_REACH, 0,
                UaiEngine.DEFAULT_EVAL_EDGE, UaiEngine.DEFAULT_TEMPO, 0, 0, 0, UaiEngine.DEFAULT_EVAL_COHESION, 0);
    }

    /**
     * @param lines game records in the {@code Match --out} format
     * @param minPly skip positions before this ply (the random opening)
     * @param quiet if positive, skip positions where the side to move can convert at least this many pieces
     * @param every keep every n-th position of each game (1 = all)
     * @param firstGameId games are numbered from this value + 1; identical games are dropped
     */
    public static List<Sample> load(List<String> lines, int minPly, int quiet, int every, int firstGameId) {
        List<Sample> out = new ArrayList<>();
        FeatureEvaluator current = new FeatureEvaluator(currentWeights());
        int[] buf = new int[Position.MAX_MOVES];
        int gameId = firstGameId;
        // Two identical deterministic engines play the same game from both colours: keep each game once, or the
        // copies would straddle the train/held-out split.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : lines) {
            String[] f = line.split("\\|");
            if (f.length < 5 || !f[3].trim().equals("game over") || !seen.add(f[0].trim() + "|" + f[4].trim())) {
                continue;
            }
            gameId++;
            String result = f[2].trim();
            int xScore = result.equals("1-0") ? 2 : result.equals("0-1") ? 0 : 1; // doubled, to stay in ints
            Position pos = Position.fromFen(f[0].trim());
            String[] moves = f[4].trim().isEmpty() ? new String[0] : f[4].trim().split("\\s+");
            for (int ply = 0; ply < moves.length && !pos.isGameOver(); ply++) {
                if (ply >= minPly && (ply - minPly) % every == 0
                        && (quiet <= 0 || pos.generateCaptureMoves(buf, quiet) == 0)) {
                    int[] raw = new int[FeatureEvaluator.FEATURE_NAMES.length];
                    FeatureEvaluator.rawFeatures(pos, raw);
                    double label = (pos.sideToMove() == Position.X ? xScore : 2 - xScore) / 2.0;
                    out.add(new Sample(raw, Long.bitCount(pos.empty()), label, gameId, current.evaluate(pos)));
                }
                pos = pos.makeMove(Move.parse(moves[ply]));
            }
        }
        return out;
    }

    private static double[][] matrix(List<Sample> samples, int[] columns, boolean phase) {
        int extra = phase ? columns.length : 0;
        double[][] x = new double[samples.size()][];
        for (int i = 0; i < x.length; i++) {
            Sample s = samples.get(i);
            double[] row = new double[1 + columns.length + extra];
            row[0] = 1;
            for (int c = 0; c < columns.length; c++) {
                row[1 + c] = s.raw()[columns[c]];
                if (phase) {
                    row[1 + columns.length + c] = s.raw()[columns[c]] * s.empties() / 49.0;
                }
            }
            x[i] = row;
        }
        return x;
    }

    private static double[] labels(List<Sample> samples) {
        double[] y = new double[samples.size()];
        for (int i = 0; i < y.length; i++) {
            y[i] = samples.get(i).label();
        }
        return y;
    }

    public static void main(String[] args) throws IOException {
        List<Path> files = new ArrayList<>();
        int minPly = 10;
        int quiet = 3;
        int every = 1;
        boolean phase = false;
        String featureList = "material,safe,reach,edge,cohesion";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games" -> files.add(Path.of(args[++i]));
                case "--min-ply" -> minPly = Integer.parseInt(args[++i]);
                case "--quiet" -> quiet = Integer.parseInt(args[++i]);
                case "--every" -> every = Integer.parseInt(args[++i]);
                case "--features" -> featureList = args[++i];
                case "--phase" -> phase = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (files.isEmpty()) {
            System.err.println("usage: Fit --games FILE [--games FILE...] [--min-ply N] [--quiet N] [--every K]"
                    + " [--features a,b,c] [--phase]");
            System.exit(2);
        }
        List<String> names = Arrays.asList(FeatureEvaluator.FEATURE_NAMES);
        String[] chosen = featureList.split(",");
        int[] columns = new int[chosen.length];
        for (int i = 0; i < chosen.length; i++) {
            columns[i] = names.indexOf(chosen[i].trim());
            if (columns[i] < 0) {
                throw new IllegalArgumentException("unknown feature " + chosen[i] + "; known: " + names);
            }
        }

        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            lines.addAll(Files.readAllLines(file));
        }
        List<Sample> all = load(lines, minPly, quiet, every, 0);
        List<Sample> train = new ArrayList<>();
        List<Sample> test = new ArrayList<>();
        for (Sample s : all) {
            (s.game() % 2 == 0 ? train : test).add(s);
        }
        System.out.printf("%d positions from %d games (train %d, held-out %d); min-ply %d, quiet>=%d, every %d%n",
                all.size(), all.stream().mapToInt(Sample::game).max().orElse(0), train.size(), test.size(), minPly, quiet, every);

        double nullLoss = Math.log(2);
        // Baseline: the current evaluation as a one-parameter predictor.
        double[][] xe = new double[train.size()][];
        for (int i = 0; i < xe.length; i++) {
            xe[i] = new double[] {train.get(i).currentEval()};
        }
        LogisticRegression cur = LogisticRegression.fit(xe, labels(train), 1e-9);
        double[][] xet = new double[test.size()][];
        for (int i = 0; i < xet.length; i++) {
            xet[i] = new double[] {test.get(i).currentEval()};
        }
        System.out.printf("held-out log-loss: no information %.5f | current evaluation (1 scale parameter) %.5f%n",
                nullLoss, cur.logLoss(xet, labels(test)));

        LogisticRegression trainFit = LogisticRegression.fit(matrix(train, columns, phase), labels(train), 1e-9);
        System.out.printf("held-out log-loss: fitted model with [%s]%s %.5f%n", featureList, phase ? " + phase terms" : "",
                trainFit.logLoss(matrix(test, columns, phase), labels(test)));

        LogisticRegression full = LogisticRegression.fit(matrix(all, columns, phase), labels(all), 1e-9);
        double[] c = full.coefficients();
        double[] se = full.standardErrors();
        double matCoef = Arrays.asList(chosen).contains("material") ? c[1 + Arrays.asList(chosen).indexOf("material")] : Double.NaN;
        System.out.printf("%n%-12s %10s %8s %8s   %s%n", "term", "coef", "z", "x16/mat", "(1/16 piece; cohesion/threat: 1/64)");
        System.out.printf("%-12s %10.4f %8.1f%n", "intercept", c[0], c[0] / se[0]);
        for (int k = 0; k < chosen.length; k++) {
            String name = chosen[k].trim();
            double scale = name.equals("cohesion") || name.equals("threat") ? 64 : 16;
            System.out.printf("%-12s %10.4f %8.1f %8.2f%n", name, c[1 + k], c[1 + k] / se[1 + k], scale * c[1 + k] / matCoef);
        }
        if (phase) {
            for (int k = 0; k < chosen.length; k++) {
                int col = 1 + chosen.length + k;
                System.out.printf("%-12s %10.4f %8.1f   (x empties/49)%n", chosen[k].trim() + "*phase", c[col], c[col] / se[col]);
            }
        }
    }
}
