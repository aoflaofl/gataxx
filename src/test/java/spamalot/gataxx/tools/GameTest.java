package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Outcome;
import spamalot.gataxx.board.Position;

class GameTest {
    private static GameResult play(Player x, Player o) {
        return Game.play(Position.startPos(), x, "depth 1", 1000, o, "depth 1", 1000);
    }

    @Test
    void randomGamesFinishWithConsistentResult() {
        for (int seed = 0; seed < 20; seed++) {
            GameResult r = play(FakePlayers.random("a", seed), FakePlayers.random("b", seed + 100));
            assertEquals("game over", r.reason());
            Position end = FakePlayers.replay(Position.startPos(), r.moves());
            assertTrue(end.isGameOver());
            assertEquals(end.outcome(), r.outcome());
            assertEquals(end.count(Position.X), r.xCount());
            assertEquals(end.count(Position.O), r.oCount());
        }
    }

    @Test
    void startingPositionIsRecorded() {
        assertEquals(Position.START_FEN, play(FakePlayers.random("a", 1), FakePlayers.random("b", 2)).startFen());
    }

    @Test
    void illegalMoveForfeits() {
        Player cheat = new FakePlayers.Scripted("cheat", pos -> "a7d7"); // distance 3
        GameResult r = play(cheat, FakePlayers.random("b", 1));
        assertEquals(Outcome.O_WINS, r.outcome());
        assertEquals("illegal move", r.reason());
        assertEquals(0, r.moves().size());
    }

    @Test
    void garbageMoveForfeits() {
        Player junk = new FakePlayers.Scripted("junk", pos -> "hello");
        GameResult r = play(FakePlayers.random("a", 1), junk);
        // x moves first (legally), then o's garbage forfeits: x wins.
        assertEquals(Outcome.X_WINS, r.outcome());
        assertEquals("illegal move", r.reason());
        assertEquals(1, r.moves().size());
    }

    @Test
    void passWhenNotStuckIsIllegal() {
        Player passer = new FakePlayers.Scripted("passer", pos -> "0000");
        assertEquals("illegal move", play(passer, FakePlayers.random("b", 1)).reason());
    }

    @Test
    void timeoutForfeits() {
        Player slow = new FakePlayers.Scripted("slow", pos -> {
            throw new TimeoutException("too slow");
        });
        GameResult r = play(FakePlayers.random("a", 1), slow);
        assertEquals(Outcome.X_WINS, r.outcome());
        assertEquals("timeout", r.reason());
    }

    @Test
    void crashForfeits() {
        Player crash = new FakePlayers.Scripted("crash", pos -> {
            throw new IllegalStateException("boom");
        });
        GameResult r = play(crash, FakePlayers.random("b", 1));
        assertEquals(Outcome.O_WINS, r.outcome());
        assertEquals("engine failure", r.reason());
    }

    @Test
    void correctlyPassesWhenStuck() {
        // x is walled in and must pass; the harness must accept 0000 there.
        Position start = Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1");
        Player passer = new FakePlayers.Scripted("x", pos -> pos.sideToMove() == Position.X ? "0000" : null);
        GameResult r = Game.play(start, passer, "depth 1", 1000, FakePlayers.random("o", 3), "depth 1", 1000);
        assertTrue(r.moves().size() > 1);
        assertEquals("0000", r.moves().get(0));
    }

    @Test
    void alreadyOverGameReturnsImmediately() {
        Position over = Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxoo o 0 1");
        GameResult r = Game.play(over, FakePlayers.random("a", 1), "depth 1", 1, FakePlayers.random("b", 1), "depth 1", 1);
        assertEquals(Outcome.X_WINS, r.outcome());
        assertEquals(0, r.moves().size());
    }
}
