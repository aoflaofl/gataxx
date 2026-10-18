package spamalot.gataxx.tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import spamalot.gataxx.eval.PairEvaluator;
import spamalot.gataxx.eval.PairPatterns;

/**
 * Trains the pair-pattern weights on {@link GenData} records.
 *
 * <p>The model predicts a position's value as the generating engine's static evaluation plus the sum of 80 pattern weights
 * (and a tempo term), mapped to a winning probability by {@code 1 / (1 + exp(-E / S))}; the target mixes the game's result
 * with the search score squashed by the same function. Weights of symmetric images are tied. Adagrad, run lock-free over
 * several threads; the epoch with the lowest validation loss is kept.
 *
 * <pre>
 * java -Xmx12g -cp gataxx.jar spamalot.gataxx.tools.PairFit --data a.bin,b.bin --out pair.bin
 *     [--epochs 6] [--lr 0.5] [--lambda 0.5] [--threads 16] [--scale 0] [--valid-mod 20] [--no-mobility]
 * </pre>
 */
public final class PairFit {
    private static final int KEYS = PairPatterns.PLACEMENTS * PairPatterns.INDEX_SIZE;

    private final int n;
    private final long[] mine;
    private final long[] theirs;
    private final long[] walls;
    private final int[] game;
    private final short[] score;
    private final short[] stat;
    private final byte[] result;

    private PairFit(int n) {
        this.n = n;
        mine = new long[n];
        theirs = new long[n];
        walls = new long[n];
        game = new int[n];
        score = new short[n];
        stat = new short[n];
        result = new byte[n];
    }

    private static PairFit load(List<Path> files) throws IOException {
        long total = 0;
        for (Path f : files) {
            total += Files.size(f) / GenData.RECORD_BYTES;
        }
        PairFit d = new PairFit((int) total);
        int i = 0;
        int gameBase = 0;
        for (Path f : files) {
            int maxGame = 0;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f), 1 << 20))) {
                long count = Files.size(f) / GenData.RECORD_BYTES;
                for (long r = 0; r < count; r++, i++) {
                    int g = in.readInt();
                    maxGame = Math.max(maxGame, g);
                    d.game[i] = gameBase + g;
                    d.mine[i] = in.readLong();
                    d.theirs[i] = in.readLong();
                    d.walls[i] = in.readLong();
                    in.readShort(); // ply
                    d.score[i] = in.readShort();
                    d.stat[i] = in.readShort();
                    d.result[i] = in.readByte();
                }
            }
            gameBase += maxGame + 1;
        }
        return d;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /** Mean cross-entropy of {@code sigmoid(E / s)} against the targets over the given positions. */
    private double loss(int[] idx, double s, double lambda, float[] w, int[] tie, boolean useWeights) {
        double[] sum = new double[1];
        int threads = Runtime.getRuntime().availableProcessors();
        double[] partial = new double[threads];
        IntStream.range(0, threads).parallel().forEach(t -> {
            int[] blocks = new int[49];
            int[] pat = new int[PairPatterns.PLACEMENTS];
            double acc = 0;
            for (int k = t; k < idx.length; k += threads) {
                int i = idx[k];
                double e = stat[i];
                if (useWeights) {
                    PairPatterns.patterns(mine[i], theirs[i], walls[i], blocks, pat);
                    for (int p = 0; p < PairPatterns.PLACEMENTS; p++) {
                        e += w[tie[p << 16 | pat[p]]];
                    }
                    e += w[tempoId(w)];
                    e += w[mobilityBase(w) + PairPatterns.mobilityIndex(mine[i], theirs[i], walls[i])]
                            - w[mobilityBase(w) + PairPatterns.mobilityIndex(theirs[i], mine[i], walls[i])];
                }
                double p = sigmoid(e / s);
                double target = target(i, s, lambda);
                acc += -(target * Math.log(p + 1e-12) + (1 - target) * Math.log(1 - p + 1e-12));
            }
            partial[t] = acc;
        });
        for (double v : partial) {
            sum[0] += v;
        }
        return sum[0] / idx.length;
    }

    /** Parameter layout: tied pattern classes, then the tempo bonus, then the mobility table. */
    private static int tempoId(float[] w) {
        return w.length - 1 - PairPatterns.MOBILITY_SIZE;
    }

    private static int mobilityBase(float[] w) {
        return w.length - PairPatterns.MOBILITY_SIZE;
    }

    private double target(int i, double s, double lambda) {
        double res = result[i] / 2.0;
        return lambda * res + (1 - lambda) * sigmoid(score[i] / s);
    }

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        Path out = null;
        int epochs = 6;
        double lr = 0.5;
        double lambda = 0.5;
        int threads = Runtime.getRuntime().availableProcessors();
        double scale = 0;
        int validMod = 20;
        boolean noMobility = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--no-mobility" -> noMobility = true;
                case "--data" -> {
                    for (String f : args[++i].split(",")) {
                        files.add(Path.of(f));
                    }
                }
                case "--out" -> out = Path.of(args[++i]);
                case "--epochs" -> epochs = Integer.parseInt(args[++i]);
                case "--lr" -> lr = Double.parseDouble(args[++i]);
                case "--lambda" -> lambda = Double.parseDouble(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--scale" -> scale = Double.parseDouble(args[++i]);
                case "--valid-mod" -> validMod = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (files.isEmpty() || out == null) {
            System.err.println("usage: PairFit --data FILE[,FILE...] --out FILE [--epochs N] [--lr X] [--lambda X] [--threads N]"
                    + " [--scale S] [--valid-mod N] [--no-mobility]");
            System.exit(2);
        }
        long t0 = System.nanoTime();
        PairFit d = load(files);
        System.out.printf("%d positions loaded in %.0f s%n", d.n, (System.nanoTime() - t0) / 1e9);

        List<Integer> trainList = new ArrayList<>();
        List<Integer> validList = new ArrayList<>();
        for (int i = 0; i < d.n; i++) {
            (d.game[i] % validMod == 0 ? validList : trainList).add(i);
        }
        int[] train = trainList.stream().mapToInt(Integer::intValue).toArray();
        int[] valid = validList.stream().mapToInt(Integer::intValue).toArray();
        System.out.printf("train %d, validation %d%n", train.length, valid.length);

        // The logistic scale: the S at which the generating engine's static evaluation best predicts the results.
        if (scale <= 0) {
            double bestLoss = Double.MAX_VALUE;
            int[] sample = Arrays.copyOf(train, Math.min(train.length, 1_000_000));
            for (double s = 20; s <= 400; s += 10) {
                double l = d.loss(sample, s, 1.0, new float[1], new int[0], false);
                if (l < bestLoss) {
                    bestLoss = l;
                    scale = s;
                }
            }
            System.out.printf("logistic scale S = %.0f (result cross-entropy of the static evaluation %.4f)%n", scale, bestLoss);
        }
        final double s = scale;
        final double lam = lambda;
        final boolean mobilityOn = !noMobility;

        // Tie classes of symmetric patterns.
        long t1 = System.nanoTime();
        int[] canonical = new int[KEYS];
        IntStream.range(0, KEYS).parallel().forEach(k -> canonical[k] = PairPatterns.canonicalKey(k >>> 16, k & 0xFFFF));
        int[] dense = new int[KEYS];
        Arrays.fill(dense, -1);
        int classes = 0;
        List<Integer> classKeys = new ArrayList<>();
        for (int k = 0; k < KEYS; k++) {
            if (canonical[k] == k) {
                dense[k] = classes++;
                classKeys.add(k);
            }
        }
        final int[] tie = new int[KEYS];
        for (int k = 0; k < KEYS; k++) {
            tie[k] = dense[canonical[k]];
        }
        System.out.printf("%d tie classes in %.0f s%n", classes, (System.nanoTime() - t1) / 1e9);

        final float[] w = new float[classes + 1 + PairPatterns.MOBILITY_SIZE]; // tied classes, tempo, mobility
        final float[] g2 = new float[w.length];
        Arrays.fill(g2, 1e-3f);
        float[] best = w.clone();
        double baseLoss = d.loss(valid, s, lam, w, tie, false);
        double bestLoss = baseLoss;
        System.out.printf("validation loss of the static evaluation alone: %.5f%n", baseLoss);

        int[] order = train.clone();
        Random rnd = new Random(12345);
        final int fThreads = threads;
        for (int epoch = 1; epoch <= epochs; epoch++) {
            for (int i = order.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            final double rate = lr / epoch;
            long te = System.nanoTime();
            IntStream.range(0, fThreads).parallel().forEach(t -> {
                int[] blocks = new int[49];
                int[] pat = new int[PairPatterns.PLACEMENTS];
                int[] ids = new int[PairPatterns.PLACEMENTS + 3];
                float[] sign = new float[ids.length];
                Arrays.fill(sign, 1f);
                sign[PairPatterns.PLACEMENTS + 2] = -1f;
                for (int k = t; k < order.length; k += fThreads) {
                    int i = order[k];
                    PairPatterns.patterns(d.mine[i], d.theirs[i], d.walls[i], blocks, pat);
                    double e = d.stat[i];
                    for (int p = 0; p < PairPatterns.PLACEMENTS; p++) {
                        ids[p] = tie[p << 16 | pat[p]];
                        e += w[ids[p]];
                    }
                    ids[PairPatterns.PLACEMENTS] = tempoId(w);
                    ids[PairPatterns.PLACEMENTS + 1] = mobilityBase(w)
                            + PairPatterns.mobilityIndex(d.mine[i], d.theirs[i], d.walls[i]);
                    ids[PairPatterns.PLACEMENTS + 2] = mobilityBase(w)
                            + PairPatterns.mobilityIndex(d.theirs[i], d.mine[i], d.walls[i]);
                    e += w[ids[PairPatterns.PLACEMENTS]] + w[ids[PairPatterns.PLACEMENTS + 1]]
                            - w[ids[PairPatterns.PLACEMENTS + 2]];
                    double err = (sigmoid(e / s) - d.target(i, s, lam)) / s;
                    for (int j = 0; j < ids.length; j++) {
                        if (!mobilityOn && j > PairPatterns.PLACEMENTS) {
                            break;
                        }
                        float g = (float) err * sign[j];
                        int id = ids[j];
                        g2[id] += g * g;
                        w[id] -= (float) (rate * g / (Math.sqrt(g2[id]) + 1e-8));
                    }
                }
            });
            double vl = d.loss(valid, s, lam, w, tie, true);
            System.out.printf("epoch %d: validation loss %.5f (static %.5f), %.0f s%n", epoch, vl, baseLoss,
                    (System.nanoTime() - te) / 1e9);
            if (vl < bestLoss) {
                bestLoss = vl;
                best = w.clone();
            }
        }

        // Write the best weights: one entry per class with a non-zero quantised weight.
        int written = 0;
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            List<int[]> entries = new ArrayList<>();
            for (int c = 0; c < classes; c++) {
                int q = (int) Math.round(best[c] * PairEvaluator.WEIGHT_SCALE);
                q = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, q));
                if (q != 0) {
                    entries.add(new int[] {classKeys.get(c), q});
                }
            }
            o.writeInt(PairEvaluator.MAGIC);
            o.writeInt(PairEvaluator.VERSION);
            o.writeInt(PairEvaluator.WEIGHT_SCALE);
            o.writeShort((int) Math.round(best[classes] * PairEvaluator.WEIGHT_SCALE));
            o.writeInt(entries.size());
            for (int[] e : entries) {
                o.writeInt(e[0]);
                o.writeShort(e[1]);
                written++;
            }
            List<int[]> mob = new ArrayList<>();
            for (int m = 0; m < PairPatterns.MOBILITY_SIZE; m++) {
                int q = (int) Math.round(best[classes + 1 + m] * PairEvaluator.WEIGHT_SCALE);
                q = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, q));
                if (q != 0) {
                    mob.add(new int[] {m, q});
                }
            }
            o.writeInt(mob.size());
            for (int[] e : mob) {
                o.writeShort(e[0]);
                o.writeShort(e[1]);
            }
        }
        System.out.printf("best validation loss %.5f; wrote %d classes (of %d) to %s%n", bestLoss, written, classes, out);
    }
}
