package spamalot.gataxx.tools;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Outcome;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.FeatureEvaluator;
import spamalot.gataxx.eval.PatternEvaluator;
import spamalot.gataxx.search.SearchLimits;
import spamalot.gataxx.search.SearchResult;
import spamalot.gataxx.search.Searcher;
import spamalot.gataxx.search.TranspositionTable;

/**
 * Generates training positions by self-play of the engine itself, in process (no UAI): random openings, then fixed-node
 * searches. Every position after the opening is written with the search score and the final result of its game, for
 * {@link PairFit}.
 *
 * <pre>
 * java -cp gataxx.jar spamalot.gataxx.tools.GenData --out data.bin [--games 10000] [--nodes 20000] [--threads 8]
 *     [--opening-min 6] [--opening-max 12] [--seed 1] [--pair builtin|none|FILE] [--pair-blend 0.7] [--margin 128]
 *     [--record pattern|features] [--pattern on|off]
 * </pre>
 *
 * With {@code --internal} the positions are sampled from inside the search instead: each outer game is searched with an
 * evaluator that picks a few of the positions it is called on (the leaves of the search, quiet or not); from each picked
 * position a separate game is played at {@code --nodes} per move, and positions of that game are saved at its start and then
 * every 20-40 plies. Options: {@code --internal --outer-nodes 2000 --select-prob 0.0002 --max-select 100}.
 *
 * With {@code --pattern off} the search evaluator is the features plus the pair table, without the pattern table (the engine
 * as it runs with {@code PatternBlend 0}).
 *
 * Record layout (big endian): game id (int), mine (long), theirs (long), walls (long), ply (short), search score (short, side
 * to move, clipped), static evaluation of the generating engine (short, clipped), result (byte: 0 loss, 1 draw, 2 win for the
 * side to move).
 */
public final class GenData {
    public static final int RECORD_BYTES = 4 + 8 + 8 + 8 + 2 + 2 + 2 + 1;

    private GenData() {}

    /** The engine's default evaluation and search, as in {@code UaiEngine}. */
    static Searcher newSearcher(Evaluator evaluator, TranspositionTable tt) {
        Searcher s = new Searcher(evaluator, tt);
        s.setQuiescence(3, 5);
        s.setQuiescenceJumpMode(Position.JUMPS_CUT_ALL_DOMINATED);
        s.setPvs(true);
        s.setLmr(true, 3, 4);
        s.setLmrDeep(6);
        s.setJumpMode(Position.JUMPS_CUT_QUIET_DOMINATED, 12, Position.JUMPS_ALL, 0);
        s.setIir(3);
        s.setPartialIterations(true);
        return s;
    }

    static Evaluator defaultEvaluator() throws IOException {
        return defaultEvaluator(128);
    }

    /** The hand-made features alone: the base of a pair table that is used without the pattern table. */
    static Evaluator featuresOnly() {
        return new FeatureEvaluator(new FeatureEvaluator.Weights(FeatureEvaluator.SCALE, 4, 0, 0, 4, 0, 8, 32, 0, 0, 0, -3, 0));
    }

    /** Feature and pattern evaluation as the engine builds it; {@code margin} is the pattern table's lazy margin. */
    static Evaluator defaultEvaluator(int margin) throws IOException {
        Evaluator base = new FeatureEvaluator(new FeatureEvaluator.Weights(FeatureEvaluator.SCALE, 4, 0, 0, 4, 0, 8, 32, 0, 0, 0, -3, 0));
        return PatternEvaluator.builtIn(base, 0.65).withMargin(margin);
    }

    public static void main(String[] args) throws Exception {
        Path out = null;
        int games = 10_000;
        int nodes = 20_000;
        int threads = Runtime.getRuntime().availableProcessors();
        int openingMin = 6;
        int openingMax = 12;
        long seed = 1;
        int hash = 8;
        int margin = 128;
        boolean recordFeaturesOnly = false;
        boolean noPattern = false;
        String pair = "builtin";
        double pairBlend = 0.9;
        boolean internal = false;
        int outerNodes = 2000;
        double selectProb = 0.0002;
        int maxSelect = 100;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--nodes" -> nodes = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--opening-min" -> openingMin = Integer.parseInt(args[++i]);
                case "--opening-max" -> openingMax = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--hash" -> hash = Integer.parseInt(args[++i]);
                case "--internal" -> internal = true;
                case "--outer-nodes" -> outerNodes = Integer.parseInt(args[++i]);
                case "--select-prob" -> selectProb = Double.parseDouble(args[++i]);
                case "--max-select" -> maxSelect = Integer.parseInt(args[++i]);
                case "--margin" -> margin = Integer.parseInt(args[++i]);
                case "--record" -> recordFeaturesOnly = args[++i].equals("features");
                case "--pattern" -> noPattern = args[++i].equals("off");
                case "--pair" -> pair = args[++i];
                case "--pair-blend" -> pairBlend = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (out == null) {
            System.err.println("usage: GenData --out FILE [--games N] [--nodes N] [--threads N] [--opening-min N] [--opening-max N] [--seed N]");
            System.exit(2);
        }
        Evaluator staticEvaluator = noPattern ? featuresOnly() : defaultEvaluator(margin);
        Evaluator evaluator = staticEvaluator;
        if (pair.equals("builtin")) {
            evaluator = spamalot.gataxx.eval.PairEvaluator.builtIn(staticEvaluator, pairBlend).withMargin(margin);
        } else if (!pair.equals("none")) {
            try (java.io.InputStream in = Files.newInputStream(Path.of(pair))) {
                evaluator = spamalot.gataxx.eval.PairEvaluator.load(in, staticEvaluator, pairBlend).withMargin(margin);
            }
        }
        final Evaluator searchEvaluator = evaluator;
        final Evaluator recordedEvaluator = recordFeaturesOnly ? featuresOnly() : staticEvaluator;
        final int fGames = games;
        final int fNodes = nodes;
        final int fMin = openingMin;
        final int fMax = openingMax;
        final long fSeed = seed;
        final int fHash = hash;
        final boolean fInternal = internal;
        final int fOuterNodes = outerNodes;
        final double fSelectProb = selectProb;
        final int fMaxSelect = maxSelect;
        final AtomicInteger sampleIds = new AtomicInteger();
        AtomicInteger nextGame = new AtomicInteger();
        AtomicInteger positions = new AtomicInteger();
        // Games finish in any order but are written in the order of their numbers, so that a seed gives the same file for any
        // number of threads.
        final java.util.TreeMap<Integer, byte[]> pending = new java.util.TreeMap<>();
        final int[] nextToWrite = {0};
        long start = System.nanoTime();
        try (DataOutputStream file = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out), 1 << 20))) {
            List<Thread> pool = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread th = new Thread(() -> {
                    TranspositionTable tt = new TranspositionTable(fHash);
                    int g;
                    while ((g = nextGame.getAndIncrement()) < fGames) {
                        SplittableRandom rnd = new SplittableRandom(fSeed * 1_000_003L + g);
                        byte[] game = fInternal
                                ? playInternal(sampleIds, searchEvaluator, recordedEvaluator, tt, rnd, fOuterNodes, fNodes,
                                        fSelectProb, fMaxSelect, fMin, fMax)
                                : playGame(g, searchEvaluator, recordedEvaluator, tt, rnd, fNodes, fMin, fMax);
                        synchronized (file) {
                            pending.put(g, game);
                            try {
                                while (!pending.isEmpty() && pending.firstKey() == nextToWrite[0]) {
                                    file.write(pending.pollFirstEntry().getValue());
                                    nextToWrite[0]++;
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        int n = positions.addAndGet(game.length / RECORD_BYTES);
                        if ((g + 1) % 2000 == 0) {
                            System.out.printf("  %d games, %d positions, %.0f s%n", g + 1, n, (System.nanoTime() - start) / 1e9);
                        }
                    }
                }, "gen-" + t);
                pool.add(th);
                th.start();
            }
            for (Thread th : pool) {
                th.join();
            }
        }
        System.out.printf("%d games, %d positions written to %s in %.0f s%n", games, positions.get(), out,
                (System.nanoTime() - start) / 1e9);
    }

    /** Passes evaluations through and keeps a random few of the positions it is asked about. */
    private static final class SamplingEvaluator implements Evaluator {
        private final Evaluator inner;
        private final SplittableRandom rnd;
        private final double probability;
        private final int cap;
        private final List<Position> picked;

        SamplingEvaluator(Evaluator inner, SplittableRandom rnd, double probability, int cap, List<Position> picked) {
            this.inner = inner;
            this.rnd = rnd;
            this.probability = probability;
            this.cap = cap;
            this.picked = picked;
        }

        private void maybePick(Position pos) {
            if (picked.size() < cap && rnd.nextDouble() < probability) {
                picked.add(pos);
            }
        }

        @Override
        public int evaluate(Position pos) {
            maybePick(pos);
            return inner.evaluate(pos);
        }

        @Override
        public int evaluate(Position pos, int alpha, int beta) {
            maybePick(pos);
            return inner.evaluate(pos, alpha, beta);
        }
    }

    /** An outer game that picks positions from inside its search, and a sample game from each. */
    private static byte[] playInternal(AtomicInteger ids, Evaluator evaluator, Evaluator staticEval, TranspositionTable tt,
            SplittableRandom rnd, int outerNodes, int sampleNodes, double probability, int cap, int openingMin,
            int openingMax) {
        List<Position> picked = new ArrayList<>();
        Evaluator sampling = new SamplingEvaluator(evaluator, rnd, probability, cap, picked);
        tt.clear();
        Position pos = Position.startPos();
        int[] buf = new int[Position.MAX_MOVES];
        int opening = openingMin + rnd.nextInt(openingMax - openingMin + 1);
        for (int ply = 0; ply < opening && !pos.isGameOver(); ply++) {
            int n = pos.generateMoves(buf);
            pos = pos.makeMove(buf[rnd.nextInt(n)]);
        }
        int ply = opening;
        while (!pos.isGameOver() && ply < 400) {
            SearchResult r = newSearcher(sampling, tt).search(pos, SearchLimits.nodes(outerNodes), null);
            if (r.bestMove() == Move.NONE) {
                break;
            }
            pos = pos.makeMove(r.bestMove());
            ply++;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(bytes)) {
            for (Position p : picked) {
                playSample(ids.getAndIncrement(), Position.fromFen(p.toFen()), evaluator, staticEval, tt, rnd, sampleNodes, d);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Plays a game from {@code start}, writing the position at its start and then one every 20-40 plies. */
    private static void playSample(int id, Position start, Evaluator evaluator, Evaluator staticEval, TranspositionTable tt,
            SplittableRandom rnd, int nodes, DataOutputStream out) throws IOException {
        tt.clear();
        List<long[]> recorded = new ArrayList<>();
        Position pos = start;
        int move = 0;
        int nextSample = 0;
        while (!pos.isGameOver() && move < 400) {
            SearchResult r = newSearcher(evaluator, tt).search(pos, SearchLimits.nodes(nodes), null);
            if (r.bestMove() == Move.NONE) {
                break;
            }
            if (move == nextSample) {
                if (pos.halfmoveClock() < 50) {
                    int side = pos.sideToMove();
                    int score = Math.max(-3000, Math.min(3000, r.score()));
                    int stat = Math.max(-3000, Math.min(3000, staticEval.evaluate(pos)));
                    recorded.add(new long[] {pos.pieces(side), pos.pieces(1 - side), pos.walls(), move, score, side, stat});
                }
                nextSample += 20 + rnd.nextInt(21);
            }
            pos = pos.makeMove(r.bestMove());
            move++;
        }
        Outcome outcome = pos.isGameOver() ? pos.outcome() : pos.outcomeByCount();
        for (long[] r : recorded) {
            int side = (int) r[5];
            int result = outcome == Outcome.DRAW ? 1 : (outcome == Outcome.X_WINS) == (side == Position.X) ? 2 : 0;
            out.writeInt(id);
            out.writeLong(r[0]);
            out.writeLong(r[1]);
            out.writeLong(r[2]);
            out.writeShort((int) r[3]);
            out.writeShort((int) r[4]);
            out.writeShort((int) r[6]);
            out.writeByte(result);
        }
    }

    private static byte[] playGame(int id, Evaluator evaluator, Evaluator staticEval, TranspositionTable tt, SplittableRandom rnd, int nodes,
            int openingMin, int openingMax) throws IllegalStateException {
        tt.clear();
        Position pos = Position.startPos();
        int[] buf = new int[Position.MAX_MOVES];
        int opening = openingMin + rnd.nextInt(openingMax - openingMin + 1);
        for (int ply = 0; ply < opening && !pos.isGameOver(); ply++) {
            int n = pos.generateMoves(buf);
            pos = pos.makeMove(buf[rnd.nextInt(n)]);
        }
        List<long[]> recorded = new ArrayList<>(); // mine, theirs, walls, ply, score, side, static evaluation
        int ply = opening;
        while (!pos.isGameOver() && ply < 400) {
            Searcher s = newSearcher(evaluator, tt);
            SearchResult r = s.search(pos, SearchLimits.nodes(nodes), null);
            int best = r.bestMove();
            if (best == Move.NONE) {
                break;
            }
            int side = pos.sideToMove();
            int score = Math.max(-3000, Math.min(3000, r.score()));
            int stat = Math.max(-3000, Math.min(3000, staticEval.evaluate(pos)));
            recorded.add(new long[] {pos.pieces(side), pos.pieces(1 - side), pos.walls(), ply, score, side, stat});
            pos = pos.makeMove(best);
            ply++;
        }
        Outcome outcome = pos.isGameOver() ? pos.outcome() : pos.outcomeByCount();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(recorded.size() * RECORD_BYTES);
        try (DataOutputStream d = new DataOutputStream(bytes)) {
            for (long[] r : recorded) {
                int side = (int) r[5];
                int result = outcome == Outcome.DRAW ? 1
                        : (outcome == Outcome.X_WINS) == (side == Position.X) ? 2 : 0;
                d.writeInt(id);
                d.writeLong(r[0]);
                d.writeLong(r[1]);
                d.writeLong(r[2]);
                d.writeShort((int) r[3]);
                d.writeShort((int) r[4]);
                d.writeShort((int) r[6]);
                d.writeByte(result);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
