package spamalot.gataxx.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;

/**
 * Uses another UAI engine as a "teacher": samples positions from saved games, asks the engine for its search score on each,
 * and writes them to a tab-separated file for {@link Distill}. The engine is treated as a black box; nothing about its
 * internals is needed.
 *
 * <pre>
 * java -cp gataxx.jar spamalot.gataxx.tools.Teacher --games games.txt --engine "path/to/engine" --out scores.tsv
 *     [--movetime 30] [--positions 20000] [--min-ply 10] [--workers 8] [--seed 1]
 * </pre>
 *
 * Output columns: game id, ply, FEN, score, depth reached, move. Positions where the engine fails or times out are skipped.
 */
public final class Teacher {
    /** One position to ask about. */
    public record Query(int game, int ply, String fen) {}

    private Teacher() {}

    /** All positions of the finished, distinct games from ply {@code minPly} on, in file order. */
    public static List<Query> collect(List<String> gameLines, int minPly) {
        List<Query> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int gameId = 0;
        for (String line : gameLines) {
            String[] f = line.split("\\|");
            if (f.length < 5 || !f[3].trim().equals("game over") || !seen.add(f[0].trim() + "|" + f[4].trim())) {
                continue;
            }
            gameId++;
            Position pos = Position.fromFen(f[0].trim());
            String[] moves = f[4].trim().isEmpty() ? new String[0] : f[4].trim().split("\\s+");
            for (int ply = 0; ply < moves.length && !pos.isGameOver(); ply++) {
                if (ply >= minPly) {
                    out.add(new Query(gameId, ply, pos.toFen()));
                }
                pos = pos.makeMove(Move.parse(moves[ply]));
            }
        }
        return out;
    }

    /** A reproducible random sample of at most {@code n} queries, keeping file order. */
    public static List<Query> sample(List<Query> all, int n, long seed) {
        if (all.size() <= n) {
            return new ArrayList<>(all);
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            idx.add(i);
        }
        Collections.shuffle(idx, new Random(seed));
        List<Integer> keep = new ArrayList<>(idx.subList(0, n));
        Collections.sort(keep);
        List<Query> out = new ArrayList<>(n);
        for (int i : keep) {
            out.add(all.get(i));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        String engine = null;
        Path out = null;
        int movetime = 30;
        int positions = 20_000;
        int minPly = 10;
        int workers = 8;
        long seed = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games" -> files.add(Path.of(args[++i]));
                case "--engine" -> engine = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--movetime" -> movetime = Integer.parseInt(args[++i]);
                case "--positions" -> positions = Integer.parseInt(args[++i]);
                case "--min-ply" -> minPly = Integer.parseInt(args[++i]);
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (files.isEmpty() || engine == null || out == null) {
            System.err.println("usage: Teacher --games FILE [--games FILE...] --engine CMD --out FILE"
                    + " [--movetime MS] [--positions N] [--min-ply N] [--workers N] [--seed N]");
            System.exit(2);
        }
        List<String> lines = new ArrayList<>();
        for (Path f : files) {
            lines.addAll(Files.readAllLines(f));
        }
        List<Query> queries = sample(collect(lines, minPly), positions, seed);
        System.out.println(queries.size() + " positions, engine \"" + engine + "\", " + movetime + " ms each, " + workers + " workers");
        List<String> command = Arrays.asList(engine.trim().split("\\s+"));
        String[] rows = run(queries, command, movetime, workers);
        List<String> written = new ArrayList<>();
        for (String r : rows) {
            if (r != null) {
                written.add(r);
            }
        }
        Files.write(out, written);
        System.out.println(written.size() + " scored, " + (queries.size() - written.size()) + " skipped (failures/timeouts/mate scores); wrote " + out);
    }

    /** Asks the engine about every query using {@code workers} separate engine processes. */
    static String[] run(List<Query> queries, List<String> command, int movetime, int workers) throws InterruptedException {
        String[] rows = new String[queries.size()];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        for (int w = 0; w < workers; w++) {
            pool.submit(() -> {
                UaiClient client = null;
                try {
                    int i;
                    while ((i = next.getAndIncrement()) < queries.size()) {
                        Query q = queries.get(i);
                        try {
                            if (client == null || !client.isHealthy()) {
                                if (client != null) {
                                    client.close();
                                }
                                client = UaiClient.start(command);
                            }
                            UaiClient.Scored s = client.bestMoveWithScore(Position.fromFen(q.fen()), List.of(),
                                    "movetime " + movetime, movetime + 1500L);
                            if (s.score() != null) {
                                rows[i] = q.game() + "\t" + q.ply() + "\t" + q.fen() + "\t" + s.score() + "\t" + s.depth() + "\t" + s.move();
                            }
                        } catch (IOException | TimeoutException | RuntimeException e) {
                            // skip this position; a fresh engine is started for the next one
                        }
                        int d = done.incrementAndGet();
                        if (d % 2000 == 0) {
                            System.out.println("  " + d + "/" + queries.size());
                        }
                    }
                } finally {
                    if (client != null) {
                        client.close();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(7, TimeUnit.DAYS);
        return rows;
    }
}
