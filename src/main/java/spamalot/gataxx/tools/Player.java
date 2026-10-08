package spamalot.gataxx.tools;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import spamalot.gataxx.board.Position;

/** Something that can pick Ataxx moves for the match harness (normally a UAI engine process). */
public interface Player extends AutoCloseable {
    String name();

    /** False once the player has timed out, crashed or otherwise needs replacing. */
    boolean isHealthy();

    void newGame() throws IOException, TimeoutException;

    /**
     * Asks for a move in the position reached from {@code start} by playing {@code moves} (UAI text).
     *
     * @param go the arguments of the UAI {@code go} command, e.g. {@code "movetime 100"}
     * @return the move in UAI text, {@code 0000} for a pass
     * @throws TimeoutException if no move arrived within {@code timeoutMs}
     */
    String bestMove(Position start, List<String> moves, String go, long timeoutMs)
            throws IOException, TimeoutException;

    @Override
    void close();
}
