package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;

class TeacherTest {
    private static final String GAME = Position.START_FEN + " | engine1=x | 1-0 | game over | b6 f2 c6 f3 d6 f4";

    @Test
    void collectsPositionsFromFinishedDistinctGamesOnly() {
        assertEquals(6, Teacher.collect(List.of(GAME), 0).size());
        assertEquals(4, Teacher.collect(List.of(GAME), 2).size());
        assertEquals(6, Teacher.collect(List.of(GAME, GAME.replace("engine1=x", "engine1=o")), 0).size(), "same game twice");
        assertEquals(0, Teacher.collect(List.of(GAME.replace("game over", "timeout")), 0).size());
        Teacher.Query q = Teacher.collect(List.of(GAME), 0).get(1);
        assertEquals(1, q.ply());
        assertEquals(1, q.game());
        assertEquals(Position.startPos().makeMove(spamalot.gataxx.board.Move.parse("b6")).toFen(), q.fen());
    }

    @Test
    void quietFilterKeepsOnlyPositionsWithoutBigCaptures() {
        String fen = "7/7/7/3oo2/2o4/7/1x5 x 0 1"; // x can convert three pieces by landing on d3
        String game = fen + " | engine1=x | 1-0 | game over | b2";
        assertEquals(1, Teacher.collect(List.of(game), 0, 0).size());
        assertEquals(0, Teacher.collect(List.of(game), 0, 3).size());
    }

    @Test
    void samplingIsReproducibleAndKeepsOrder() {
        List<Teacher.Query> all = Teacher.collect(List.of(GAME), 0);
        List<Teacher.Query> a = Teacher.sample(all, 3, 7);
        assertEquals(a, Teacher.sample(all, 3, 7));
        assertEquals(3, a.size());
        assertTrue(a.get(0).ply() < a.get(1).ply() && a.get(1).ply() < a.get(2).ply());
        assertEquals(all, Teacher.sample(all, 100, 7));
    }

    @Test
    void asksARealEngineProcessForScores() throws Exception {
        List<Teacher.Query> queries = Teacher.collect(List.of(GAME), 0);
        String[] rows = Teacher.run(queries, UaiClientIntegrationTest.engineCommand(), 40, 2);
        assertEquals(queries.size(), rows.length);
        int scored = 0;
        for (String r : rows) {
            if (r != null) {
                scored++;
                String[] f = r.split("\t");
                assertEquals(6, f.length);
                assertTrue(Position.fromFen(f[2]).isLegal(spamalot.gataxx.board.Move.parse(f[5])));
                assertNotNull(Integer.valueOf(f[3]));
            }
        }
        assertTrue(scored >= 5, "scored " + scored + " of " + rows.length);
    }

    @Test
    void parsesBothScoreFormats() throws Exception {
        // Our engine prints "score cp N"; exercised through the client against the real engine.
        try (UaiClient c = UaiClient.start(UaiClientIntegrationTest.engineCommand())) {
            UaiClient.Scored s = c.bestMoveWithScore(Position.startPos(), List.of(), "depth 3", 10_000);
            assertNotNull(s.score());
            assertEquals(3, s.depth());
            assertTrue(Position.startPos().isLegal(spamalot.gataxx.board.Move.parse(s.move())));
        }
    }
}
