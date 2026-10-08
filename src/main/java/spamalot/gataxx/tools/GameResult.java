package spamalot.gataxx.tools;

import java.util.List;
import spamalot.gataxx.board.Outcome;

/**
 * One finished game.
 *
 * @param outcome final result; never {@code ONGOING}
 * @param reason "game over", "illegal move", "timeout", "engine failure" or "ply limit"
 */
public record GameResult(String startFen, List<String> moves, Outcome outcome, String reason, int xCount, int oCount) {
    public String resultText() {
        return switch (outcome) {
            case X_WINS -> "1-0";
            case O_WINS -> "0-1";
            default -> "1/2-1/2";
        };
    }
}
