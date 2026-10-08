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
        while (!pos.isGameOver()) {
            if (moves.size() >= MAX_PLIES) {
                return result(start, moves, pos, pos.outcomeByCount(), "ply limit");
            }
            boolean xMoves = pos.sideToMove() == Position.X;
            Player mover = xMoves ? x : o;
            Outcome moverLoses = xMoves ? Outcome.O_WINS : Outcome.X_WINS;
            String text;
            try {
                text = mover.bestMove(start, moves, xMoves ? goX : goO, xMoves ? timeoutXMs : timeoutOMs);
            } catch (TimeoutException e) {
                return result(start, moves, pos, moverLoses, "timeout");
            } catch (IOException | RuntimeException e) {
                return result(start, moves, pos, moverLoses, "engine failure");
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

    private static GameResult result(Position start, List<String> moves, Position end, Outcome outcome, String reason) {
        return new GameResult(start.toFen(), List.copyOf(moves), outcome, reason,
                end.count(Position.X), end.count(Position.O));
    }
}
