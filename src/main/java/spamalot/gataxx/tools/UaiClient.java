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
