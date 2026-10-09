package spamalot.gataxx.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import spamalot.gataxx.board.Position;

/** Drives an external UAI engine process. */
public final class UaiClient implements Player {
    private static final String EOF = "\0EOF";
    private static final long STARTUP_TIMEOUT_MS = 15_000;
    private static final long READY_TIMEOUT_MS = 10_000;

    private final Process process;
    private final BufferedWriter stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private volatile boolean healthy = true;
    private String name = "unknown";

    private UaiClient(Process process) {
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                // fall through: treated as the engine going away
            }
            lines.add(EOF);
        }, "uai-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Launches {@code command} and performs the UAI handshake. */
    public static UaiClient start(List<String> command) throws IOException, TimeoutException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        UaiClient client = new UaiClient(pb.start());
        try {
            client.send("uai");
            client.waitFor("uaiok", STARTUP_TIMEOUT_MS);
            client.ready();
        } catch (IOException | TimeoutException | RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isHealthy() {
        return healthy && process.isAlive();
    }

    /** Sends {@code setoption name <name> value <value>} and waits until the engine has processed it. */
    public void setOption(String name, String value) throws IOException, TimeoutException {
        send("setoption name " + name + " value " + value);
        ready();
    }

    /** A move and the score its engine last reported for it ({@code null} if it reported none). */
    public record Scored(String move, Integer score, int depth) {}

    /**
     * Like {@link #bestMove} but also returns the score from the engine's last {@code info} line. Understands both
     * {@code score cp 75} and the bare {@code score 75} form; mate scores come back as {@code null}.
     */
    public Scored bestMoveWithScore(Position start, List<String> moves, String go, long timeoutMs)
            throws IOException, TimeoutException {
        StringBuilder pos = new StringBuilder("position fen ").append(start.toFen());
        if (!moves.isEmpty()) {
            pos.append(" moves ").append(String.join(" ", moves));
        }
        send(pos.toString());
        send("go " + go);
        List<String> seen = new java.util.ArrayList<>();
        try {
            String line = waitFor("bestmove ", timeoutMs, seen);
            String[] t = line.split("\\s+");
            if (t.length < 2) {
                healthy = false;
                throw new IOException("malformed bestmove: " + line);
            }
            Integer score = null;
            int depth = 0;
            for (String s : seen) {
                if (!s.startsWith("info")) {
                    continue;
                }
                String[] w = s.split("\\s+");
                for (int i = 0; i + 1 < w.length; i++) {
                    if (w[i].equals("depth")) {
                        depth = parseIntOr(w[i + 1], depth);
                    } else if (w[i].equals("score")) {
                        String v = w[i + 1].equals("cp") && i + 2 < w.length ? w[i + 2] : w[i + 1];
                        score = v.equals("mate") ? null : (Integer) parseIntOr(v, Integer.MIN_VALUE);
                        if (score != null && score == Integer.MIN_VALUE) {
                            score = null;
                        }
                    }
                }
            }
            return new Scored(t[1], score, depth);
        } catch (TimeoutException e) {
            healthy = false;
            try {
                send("stop");
            } catch (IOException ignored) {
                // engine is already gone
            }
            throw e;
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void newGame() throws IOException, TimeoutException {
        send("uainewgame");
        ready();
    }

    @Override
    public String bestMove(Position start, List<String> moves, String go, long timeoutMs)
            throws IOException, TimeoutException {
        StringBuilder pos = new StringBuilder("position fen ").append(start.toFen());
        if (!moves.isEmpty()) {
            pos.append(" moves ").append(String.join(" ", moves));
        }
        send(pos.toString());
        send("go " + go);
        try {
            String line = waitFor("bestmove ", timeoutMs);
            String[] t = line.split("\\s+");
            if (t.length < 2) {
                healthy = false;
                throw new IOException("malformed bestmove: " + line);
            }
            return t[1];
        } catch (TimeoutException e) {
            healthy = false;
            try {
                send("stop");
            } catch (IOException ignored) {
                // engine is already gone
            }
            throw e;
        }
    }

    private void ready() throws IOException, TimeoutException {
        send("isready");
        waitFor("readyok", READY_TIMEOUT_MS);
    }

    private void send(String line) throws IOException {
        try {
            stdin.write(line);
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            healthy = false;
            throw e;
        }
    }

    /** Reads engine output until a line starting with {@code prefix} arrives; skips everything else. */
    private String waitFor(String prefix, long timeoutMs) throws IOException, TimeoutException {
        return waitFor(prefix, timeoutMs, null);
    }

    /** As above, additionally appending every line skipped on the way to {@code seen} when it is not null. */
    private String waitFor(String prefix, long timeoutMs, List<String> seen) throws IOException, TimeoutException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long left = deadline - System.nanoTime();
            String line;
            try {
                line = left <= 0 ? null : lines.poll(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                healthy = false;
                throw new IOException("interrupted", e);
            }
            if (line == null) {
                throw new TimeoutException("no '" + prefix.trim() + "' within " + timeoutMs + "ms");
            }
            if (line.equals(EOF)) {
                healthy = false;
                throw new IOException("engine exited unexpectedly");
            }
            if (line.startsWith("id name ")) {
                name = line.substring("id name ".length()).trim();
            }
            if (line.startsWith(prefix)) {
                return line;
            }
            if (seen != null) {
                seen.add(line);
            }
        }
    }

    @Override
    public void close() {
        healthy = false;
        try {
            stdin.write("quit\n");
            stdin.flush();
        } catch (IOException ignored) {
            // already dead
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
