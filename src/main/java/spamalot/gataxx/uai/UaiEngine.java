package spamalot.gataxx.uai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Perft;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.Evaluator;
import spamalot.gataxx.eval.MaterialEvaluator;
import spamalot.gataxx.search.SearchLimits;
import spamalot.gataxx.search.SearchResult;
import spamalot.gataxx.search.Searcher;

/**
 * A Universal Ataxx Interface (UAI) engine front end: reads commands from a reader, writes replies
 * to a writer, and searches on a worker thread so {@code stop} and {@code isready} stay responsive.
 */
public final class UaiEngine {
    public static final String NAME = "gataxx";
    public static final String AUTHOR = "spamalot";

    private final BufferedReader in;
    private final PrintWriter out;
    private final Object outLock = new Object();
    private final Evaluator evaluator = new MaterialEvaluator();

    // Touched only by the command-reading thread.
    private Position position = Position.startPos();
    private Searcher searcher;
    private Thread searchThread;
    private boolean searchIsInfinite;

    public UaiEngine(BufferedReader in, PrintWriter out) {
        this.in = in;
        this.out = out;
    }

    /** Runs the command loop until {@code quit} or end of input. */
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (!handle(line)) {
                    return;
                }
            }
        } catch (IOException e) {
            send("info string input error: " + e.getMessage());
        }
        // End of input (e.g. piped scripts): let a bounded search finish and report; cut off an infinite one.
        if (searchIsInfinite) {
            stopSearch();
        }
        awaitSearch();
    }

    private void send(String line) {
        synchronized (outLock) {
            out.println(line);
            out.flush();
        }
    }

    /** Handles one command line; returns false when the engine should exit. */
    private boolean handle(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens[0].isEmpty()) {
            return true;
        }
        try {
            switch (tokens[0]) {
                case "uai" -> {
                    send("id name " + NAME + " " + spamalot.gataxx.Main.version());
                    send("id author " + AUTHOR);
                    send("uaiok");
                }
                case "isready" -> send("readyok");
                case "uainewgame" -> {
                    stopAndAwaitSearch();
                    position = Position.startPos();
                }
                case "setoption" -> { /* no options yet */ }
                case "position" -> {
                    stopAndAwaitSearch();
                    setPosition(tokens);
                }
                case "go" -> {
                    stopAndAwaitSearch();
                    go(tokens);
                }
                case "stop" -> stopSearch();
                case "quit" -> {
                    stopAndAwaitSearch();
                    return false;
                }
                case "d" -> {
                    stopAndAwaitSearch();
                    send(position.toString());
                }
                case "perft" -> {
                    stopAndAwaitSearch();
                    perft(tokens);
                }
                default -> send("info string unknown command: " + tokens[0]);
            }
        } catch (RuntimeException e) {
            send("info string error: " + e.getMessage());
        }
        return true;
    }

    /** {@code position startpos|fen <fen> [moves <m1> <m2> ...]}; leaves the position unchanged on error. */
    private void setPosition(String[] tokens) {
        if (tokens.length < 2) {
            throw new IllegalArgumentException("position needs 'startpos' or 'fen <fen>'");
        }
        int i = 2;
        Position pos;
        if (tokens[1].equals("startpos")) {
            pos = Position.startPos();
        } else if (tokens[1].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            while (i < tokens.length && !tokens[i].equals("moves")) {
                fen.append(tokens[i++]).append(' ');
            }
            pos = Position.fromFen(fen.toString());
        } else {
            throw new IllegalArgumentException("position needs 'startpos' or 'fen <fen>'");
        }
        if (i < tokens.length) {
            if (!tokens[i].equals("moves")) {
                throw new IllegalArgumentException("expected 'moves', got '" + tokens[i] + "'");
            }
            for (i++; i < tokens.length; i++) {
                int move = Move.parse(tokens[i]);
                if (!pos.isLegal(move)) {
                    throw new IllegalArgumentException("illegal move " + tokens[i]);
                }
                pos = pos.makeMove(move);
            }
        }
        position = pos;
    }

    private void go(String[] tokens) {
        SearchLimits limits = GoParameters.parse(tokens).toLimits(position);
        Position root = position;
        Searcher s = new Searcher(evaluator);
        searcher = s;
        searchIsInfinite = limits.equals(SearchLimits.infinite());
        searchThread = new Thread(() -> {
            SearchResult r;
            try {
                r = s.search(root, limits, this::sendInfo);
            } catch (RuntimeException e) {
                send("info string search error: " + e);
                r = null;
            }
            int best = r == null ? Move.NONE : r.bestMove();
            if (best == Move.NONE) {
                send("info string no legal moves");
                best = Move.PASS;
            }
            send("bestmove " + Move.toString(best));
        }, "search");
        searchThread.setDaemon(true);
        searchThread.start();
    }

    private void sendInfo(SearchResult r) {
        StringBuilder sb = new StringBuilder("info depth ").append(r.depth())
                .append(" score cp ").append(r.score())
                .append(" nodes ").append(r.nodes())
                .append(" time ").append(r.timeMs())
                .append(" nps ").append(r.nodes() * 1000 / Math.max(1, r.timeMs()));
        if (r.pv().length > 0) {
            sb.append(" pv");
            for (int m : r.pv()) {
                sb.append(' ').append(Move.toString(m));
            }
        }
        send(sb.toString());
    }

    private void perft(String[] tokens) {
        int depth = tokens.length > 1 ? Integer.parseInt(tokens[1]) : 1;
        List<String> lines = new ArrayList<>();
        int[] moves = new int[Position.MAX_MOVES];
        int n = position.generateMoves(moves);
        long total = 0;
        long start = System.nanoTime();
        for (int i = 0; i < n && depth > 0; i++) {
            long c = Perft.perft(position.makeMove(moves[i]), depth - 1);
            total += c;
            lines.add(Move.toString(moves[i]) + ": " + c);
        }
        if (depth == 0) {
            total = 1;
        }
        lines.forEach(this::send);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        send("nodes " + total + " time " + ms);
    }

    private void stopSearch() {
        if (searcher != null) {
            searcher.stop();
        }
    }

    private void awaitSearch() {
        if (searchThread != null) {
            try {
                searchThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            searchThread = null;
        }
    }

    private void stopAndAwaitSearch() {
        stopSearch();
        awaitSearch();
    }
}
