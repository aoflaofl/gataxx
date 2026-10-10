package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.Main;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;

/** Launches the real engine as a subprocess. */
class UaiClientIntegrationTest {
    static List<String> engineCommand() throws Exception {
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", classes.toString(), "spamalot.gataxx.Main");
    }

    @Test
    void handshakeAndMove() throws Exception {
        try (UaiClient c = UaiClient.start(engineCommand())) {
            assertTrue(c.name().startsWith("gataxx"), c.name());
            c.newGame();
            String m = c.bestMove(Position.startPos(), List.of(), "depth 2", 10_000);
            assertTrue(Position.startPos().isLegal(Move.parse(m)), m);
            String m2 = c.bestMove(Position.startPos(), List.of("b6"), "movetime 50", 10_000);
            assertTrue(Position.startPos().makeMove(Move.parse("b6")).isLegal(Move.parse(m2)), m2);
            assertTrue(c.isHealthy());
        }
    }

    @Test
    void setOptionsReachTheEngine() throws Exception {
        try (UaiClient c = UaiClient.start(engineCommand())) {
            c.setOption("Hash", "1");
            c.setOption("PatternBlend", "0");
            c.setOption("QuiesceMinCaptures", "2");
            String fen = "o6/o6/o3x2/6o/2x1x2/3x3/7 x 0 9";
            assertEquals("f3", c.bestMove(Position.fromFen(fen), List.of(), "depth 1", 10_000));
            c.setOption("QuiesceMinCaptures", "0");
            assertEquals("c3b5", c.bestMove(Position.fromFen(fen), List.of(), "depth 1", 10_000));
            assertTrue(c.isHealthy());
        }
    }

    @Test
    void timeoutMarksClientUnhealthy() throws Exception {
        try (UaiClient c = UaiClient.start(engineCommand())) {
            assertThrows(TimeoutException.class,
                    () -> c.bestMove(Position.startPos(), List.of(), "infinite", 300));
            assertFalse(c.isHealthy());
        }
    }

    @Test
    void realMatchDeeperSearchBeatsShallow() throws Exception {
        List<String> cmd = engineCommand();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Match.Config cfg = new Match.Config(8, "depth 4", "depth 1", 1000, 2, 2, 7);
        Match.MatchResult m = Match.run(cfg, () -> UaiClient.start(cmd), () -> UaiClient.start(cmd), log::add);
        assertEquals(8, m.total(), log.toString());
        for (GameResult g : m.games()) {
            assertEquals("game over", g.reason(), log.toString());
        }
        assertTrue(m.wins() > m.losses(), "depth 4 vs depth 1: " + m.wins() + "-" + m.losses() + "\n" + log);
    }
}
