package spamalot.gataxx.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import spamalot.gataxx.board.Outcome;
import spamalot.gataxx.board.Position;

/**
 * Plays a match between two UAI engines and reports the score and Elo difference.
 *
 * <p>Each random opening is played twice with colours swapped, so a match of {@code N} games uses
 * {@code N/2} openings. Run {@code java -cp gataxx.jar spamalot.gataxx.tools.Match --help}.
 */
public final class Match {
    /** Creates a fresh player; called again whenever a player has become unhealthy. */
    @FunctionalInterface
    public interface PlayerFactory {
        Player create() throws IOException, TimeoutException;
    }

    /**
     * @param games total games; rounded up to an even number
     * @param go1 {@code go} arguments for engine 1, e.g. {@code "movetime 100"}
     * @param go2 {@code go} arguments for engine 2
     * @param graceMs extra time allowed beyond a {@code movetime} before a move counts as a timeout
     * @param openingPlies number of random plies played to make each opening
     */
    public record Config(int games, String go1, String go2, long graceMs, int concurrency,
                         int openingPlies, long seed) {}

    /** Engine 1's record, plus every game for logging. */
    public record MatchResult(int wins, int draws, int losses, List<GameResult> games, List<Boolean> engine1IsX) {
        public int total() {
            return wins + draws + losses;
        }
    }

    private Match() {}

    public static MatchResult run(Config cfg, PlayerFactory factory1, PlayerFactory factory2, Consumer<String> log)
            throws InterruptedException {
        int openings = (cfg.games() + 1) / 2;
        List<Position> starts = openings(openings, cfg.openingPlies(), cfg.seed());
        List<Player> allPlayers = new ArrayList<>();
        ThreadLocal<Player[]> pair = ThreadLocal.withInitial(() -> new Player[2]);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.concurrency()));
        GameResult[][] results = new GameResult[openings][2];
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < openings; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    for (int swap = 0; swap < 2; swap++) {
                        Player[] p = pair.get();
                        p[0] = healthy(p[0], factory1, allPlayers);
                        p[1] = healthy(p[1], factory2, allPlayers);
                        boolean e1IsX = swap == 0;
                        Player x = e1IsX ? p[0] : p[1];
                        Player o = e1IsX ? p[1] : p[0];
                        String goX = e1IsX ? cfg.go1() : cfg.go2();
                        String goO = e1IsX ? cfg.go2() : cfg.go1();
                        x.newGame();
                        o.newGame();
                        GameResult r = Game.play(starts.get(idx), x, goX, timeoutFor(goX, cfg.graceMs()),
                                o, goO, timeoutFor(goO, cfg.graceMs()));
                        results[idx][swap] = r;
                        log.accept(describe(idx * 2 + swap + 1, x.name(), o.name(), r));
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("match worker failed: " + e.getCause(), e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            synchronized (allPlayers) {
                allPlayers.forEach(Player::close);
            }
        }

        int w = 0;
        int d = 0;
        int l = 0;
        List<GameResult> games = new ArrayList<>();
        List<Boolean> e1IsX = new ArrayList<>();
        for (GameResult[] pairResults : results) {
            for (int swap = 0; swap < 2; swap++) {
                GameResult r = pairResults[swap];
                boolean e1X = swap == 0;
                games.add(r);
                e1IsX.add(e1X);
                if (r.outcome() == Outcome.DRAW) {
                    d++;
                } else if ((r.outcome() == Outcome.X_WINS) == e1X) {
                    w++;
                } else {
                    l++;
                }
            }
        }
        return new MatchResult(w, d, l, games, e1IsX);
    }

    private static Player healthy(Player current, PlayerFactory factory, List<Player> registry)
            throws IOException, TimeoutException {
        if (current != null && current.isHealthy()) {
            return current;
        }
        if (current != null) {
            current.close();
        }
        Player fresh = factory.create();
        synchronized (registry) {
            registry.add(fresh);
        }
        return fresh;
    }

    /** Timeout for one move: the movetime plus grace, or a generous fixed limit for depth/node searches. */
    static long timeoutFor(String go, long graceMs) {
        String[] t = go.trim().split("\\s+");
        for (int i = 0; i + 1 < t.length; i++) {
            if (t[i].equals("movetime")) {
                return Long.parseLong(t[i + 1]) + graceMs;
            }
        }
        return 60_000;
    }

    /** Random openings, reproducible from {@code seed}; never already game-over. */
    public static List<Position> openings(int count, int plies, long seed) {
        Random rnd = new Random(seed);
        int[] buf = new int[Position.MAX_MOVES];
        List<Position> out = new ArrayList<>();
        while (out.size() < count) {
            Position p = Position.startPos();
            for (int i = 0; i < plies && !p.isGameOver(); i++) {
                int n = p.generateMoves(buf);
                p = p.makeMove(buf[rnd.nextInt(n)]);
            }
            if (!p.isGameOver()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String describe(int gameNo, String x, String o, GameResult r) {
        return String.format("Game %d: %s (x) vs %s (o): %s  x %d - o %d, %d plies [%s]",
                gameNo, x, o, r.resultText(), r.xCount(), r.oCount(), r.moves().size(), r.reason());
    }

    public static String summary(MatchResult m, String name1, String name2) {
        Elo elo = Elo.of(m.wins(), m.draws(), m.losses());
        int xWins = 0;
        int oWins = 0;
        for (GameResult g : m.games()) {
            if (g.outcome() == Outcome.X_WINS) {
                xWins++;
            } else if (g.outcome() == Outcome.O_WINS) {
                oWins++;
            }
        }
        return String.format("%nScore of %s vs %s: +%d =%d -%d  [%.1f%%] %d games%n"
                        + "Elo difference: %s +/- %s (95%%), LOS %.1f%%%n"
                        + "Wins by side: x %d, o %d",
                name1, name2, m.wins(), m.draws(), m.losses(), elo.score() * 100, m.total(),
                fmt(elo.elo()), fmt(elo.margin()), elo.los() * 100, xWins, oWins);
    }

    private static String fmt(double v) {
        return Double.isInfinite(v) ? (v > 0 ? "inf" : "-inf") : String.format("%.1f", v);
    }

    private static final String USAGE = """
            Usage: Match --engine1 "<command>" --engine2 "<command>" [options]
              --engine1/--engine2 CMD   command to launch each UAI engine, e.g. "java -jar target/gataxx.jar"
              --games N                 total games, rounded up to even (default 100)
              --movetime MS             time per move for both engines (default 100)
              --depth D | --nodes N     fixed depth / node limit for both engines instead of movetime
              --go1 ARGS / --go2 ARGS   per-engine 'go' arguments, overriding the above, e.g. "depth 4"
              --opt1 NAME=VALUE         UAI option to set on engine 1 at startup (repeatable), e.g. Hash=64
              --opt2 NAME=VALUE         likewise for engine 2
              --fen-only1, --fen-only2  send that engine each position as a bare FEN, never with a move list (Moonbird)
              --concurrency N           games played in parallel (default 1)
              --opening-plies N         random plies in each opening (default 4)
              --seed N                  opening seed (default 1)
              --grace MS                extra time beyond movetime before a timeout loss (default 1000)
              --out FILE                write every game (start FEN, moves, result) to FILE
            """;

    private static String[] parseOption(String text) {
        int eq = text.indexOf('=');
        if (eq <= 0 || eq == text.length() - 1) {
            throw new IllegalArgumentException("option must look like NAME=VALUE: " + text);
        }
        return new String[] {text.substring(0, eq), text.substring(eq + 1)};
    }

    private static UaiClient startWithOptions(List<String> command, List<String[]> options, boolean fenOnly)
            throws IOException, TimeoutException {
        UaiClient client = UaiClient.start(command);
        client.setFenOnly(fenOnly);
        try {
            for (String[] o : options) {
                client.setOption(o[0], o[1]);
            }
        } catch (IOException | TimeoutException | RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    public static void main(String[] args) throws Exception {
        String cmd1 = null;
        String cmd2 = null;
        String out = null;
        int games = 100;
        String common = "movetime 100";
        String go1 = null;
        String go2 = null;
        int concurrency = 1;
        int plies = 4;
        long seed = 1;
        long grace = 1000;
        List<String[]> opts1 = new ArrayList<>();
        List<String[]> opts2 = new ArrayList<>();
        boolean fenOnly1 = false;
        boolean fenOnly2 = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> {
                        System.out.print(USAGE);
                        return;
                    }
                    case "--engine1" -> cmd1 = args[++i];
                    case "--engine2" -> cmd2 = args[++i];
                    case "--games" -> games = Integer.parseInt(args[++i]);
                    case "--movetime" -> common = "movetime " + Long.parseLong(args[++i]);
                    case "--depth" -> common = "depth " + Integer.parseInt(args[++i]);
                    case "--nodes" -> common = "nodes " + Long.parseLong(args[++i]);
                    case "--go1" -> go1 = args[++i];
                    case "--go2" -> go2 = args[++i];
                    case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                    case "--opening-plies" -> plies = Integer.parseInt(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--grace" -> grace = Long.parseLong(args[++i]);
                    case "--opt1" -> opts1.add(parseOption(args[++i]));
                    case "--opt2" -> opts2.add(parseOption(args[++i]));
                    case "--fen-only1" -> fenOnly1 = true;
                    case "--fen-only2" -> fenOnly2 = true;
                    case "--out" -> out = args[++i];
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (cmd1 == null || cmd2 == null) {
                throw new IllegalArgumentException("--engine1 and --engine2 are required");
            }
        } catch (RuntimeException e) {
            System.err.println(e instanceof ArrayIndexOutOfBoundsException ? "missing option value" : e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }

        List<String> command1 = Arrays.asList(cmd1.trim().split("\\s+"));
        List<String> command2 = Arrays.asList(cmd2.trim().split("\\s+"));
        Config cfg = new Config(games, go1 != null ? go1 : common, go2 != null ? go2 : common,
                grace, concurrency, plies, seed);
        final boolean fen1 = fenOnly1;
        final boolean fen2 = fenOnly2;
        MatchResult m = run(cfg, () -> startWithOptions(command1, opts1, fen1), () -> startWithOptions(command2, opts2, fen2),
                line -> {
                    synchronized (System.out) {
                        System.out.println(line);
                    }
                });
        System.out.println(summary(m, "engine1", "engine2"));
        if (out != null) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < m.games().size(); i++) {
                GameResult g = m.games().get(i);
                lines.add(String.format("%s | engine1=%s | %s | %s | %s",
                        g.startFen(), m.engine1IsX().get(i) ? "x" : "o", g.resultText(), g.reason(),
                        String.join(" ", g.moves())));
            }
            Files.write(Path.of(out), lines);
        }
    }
}
