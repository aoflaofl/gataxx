package spamalot.gataxx.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Outcome;
import spamalot.gataxx.board.Position;

/** Plays a single game between two players, enforcing the rules. */
public final class Game {
    /** Safety net; real games end far sooner via the half-move clock. */
    public static final int MAX_PLIES = 1000;

    private Game() {}

    /**
     * @param goX {@code go} arguments for X's moves, e.g. {@code "movetime 100"}
     * @param timeoutXMs how long to wait for X before forfeiting it
     */
    public static GameResult play(Position start, Player x, String goX, long timeoutXMs,
                                  Player o, String goO, long timeoutOMs) {
        List<String> moves = new ArrayList<>();
        Position pos = start;
        // "clock BASE INC" (milliseconds) plays that side on a game clock: wtime/btime/winc/binc are sent and the time used
        // is measured here; running out of time loses.
        long[] clock = {clockBase(goX), clockBase(goO)};
        long[] inc = {clockInc(goX), clockInc(goO)};
        while (!pos.isGameOver()) {
            if (moves.size() >= MAX_PLIES) {
                return result(start, moves, pos, pos.outcomeByCount(), "ply limit");
            }
            boolean xMoves = pos.sideToMove() == Position.X;
            Player mover = xMoves ? x : o;
            Outcome moverLoses = xMoves ? Outcome.O_WINS : Outcome.X_WINS;
            String text;
            int side = xMoves ? 0 : 1;
            boolean clocked = clock[side] >= 0;
            long began = System.nanoTime();
            try {
                String go = xMoves ? goX : goO;
                long timeout = xMoves ? timeoutXMs : timeoutOMs;
                if (clocked) {
                    // x is "black" in UAI: btime/binc; o is "white": wtime/winc.
                    long xt = clock[0] >= 0 ? clock[0] : 1_000_000;
                    long ot = clock[1] >= 0 ? clock[1] : 1_000_000;
                    go = "wtime " + ot + " btime " + xt + " winc " + Math.max(0, inc[1]) + " binc " + Math.max(0, inc[0]);
                    timeout = clock[side] + 1000;
                }
                text = mover.bestMove(start, moves, go, timeout);
            } catch (TimeoutException e) {
                return result(start, moves, pos, moverLoses, "timeout");
            } catch (IOException | RuntimeException e) {
                return result(start, moves, pos, moverLoses, "engine failure");
            }
            if (clocked) {
                long used = (System.nanoTime() - began) / 1_000_000L;
                long left = clock[side] - used;
                if (left < 0) {
                    return result(start, moves, pos, moverLoses, "time forfeit");
                }
                clock[side] = left + inc[side];
            }
            int move;
            try {
                move = Move.parse(text);
            } catch (IllegalArgumentException e) {
                return result(start, moves, pos, moverLoses, "illegal move");
            }
            if (!pos.isLegal(move)) {
                return result(start, moves, pos, moverLoses, "illegal move");
            }
            moves.add(Move.toString(move));
            pos = pos.makeMove(move);
        }
        return result(start, moves, pos, pos.outcome(), "game over");
    }

    private static long clockBase(String go) {
        String[] t = go.trim().split("\\s+");
        return t.length == 3 && t[0].equals("clock") ? Long.parseLong(t[1]) : -1;
    }

    private static long clockInc(String go) {
        String[] t = go.trim().split("\\s+");
        return t.length == 3 && t[0].equals("clock") ? Long.parseLong(t[2]) : 0;
    }

    private static GameResult result(Position start, List<String> moves, Position end, Outcome outcome, String reason) {
        return new GameResult(start.toFen(), List.copyOf(moves), outcome, reason,
                end.count(Position.X), end.count(Position.O));
    }
}
