package spamalot.gataxx.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PositionTest {
    private static int[] moves(Position p) {
        int[] buf = new int[Position.MAX_MOVES];
        int n = p.generateMoves(buf);
        return java.util.Arrays.copyOf(buf, n);
    }

    @Test
    void startPosition() {
        Position p = Position.startPos();
        assertEquals(Position.X, p.sideToMove());
        assertEquals(2, p.count(Position.X));
        assertEquals(2, p.count(Position.O));
        assertEquals(45, Long.bitCount(p.empty()));
        assertEquals(16, moves(p).length);
        assertEquals(Outcome.ONGOING, p.outcome());
        // x on a7 and g1, o on a1 and g7
        assertTrue((p.pieces(Position.X) & (1L << 42)) != 0);
        assertTrue((p.pieces(Position.X) & (1L << 6)) != 0);
        assertTrue((p.pieces(Position.O) & 1L) != 0);
        assertTrue((p.pieces(Position.O) & (1L << 48)) != 0);
    }

    @Test
    void fenRoundTrip() {
        for (String fen : new String[] {
            Position.START_FEN,
            "x5o/7/2-1-2/7/2-1-2/7/o5x o 12 34",
            "7/7/7/7/7/7/7 x 0 1",
            "xxxxxxx/ooooooo/xxxxxxx/ooooooo/xxxxxxx/ooooooo/xxxxxxx o 99 50"
        }) {
            assertEquals(fen, Position.fromFen(fen).toFen());
        }
    }

    @Test
    void fenClocksOptional() {
        assertEquals(Position.START_FEN, Position.fromFen("x5o/7/7/7/7/7/o5x x").toFen());
    }

    @Test
    void badFensRejected() {
        for (String fen : new String[] {
            "", "x5o/7/7/7/7/7/o5x", "x5o/7/7/7/7/7 x", "x6o/7/7/7/7/7/o5x x",
            "x5o/7/7/7/7/7/o5x z", "x5o/7/7/7/7/7/o5q x", "x5o/7/7/7/7/7/o5x x a b"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Position.fromFen(fen), fen);
        }
    }

    @Test
    void cloneConvertsAdjacentEnemies() {
        Position p = Position.fromFen("7/7/7/7/3o3/2xoo2/7 x 0 1");
        // x at c2 (rank 2), o at d2,e2,d3. Clone to c3 is adjacent to d2 (diag), d3.
        Position q = p.makeMove(Move.parse("c3"));
        assertEquals("7/7/7/7/2xx3/2xxo2/7 o 0 1", q.toFen());
    }

    @Test
    void jumpVacatesSource() {
        Position p = Position.fromFen("7/7/7/7/7/7/x6 x 0 1");
        Position q = p.makeMove(Move.parse("a1c1"));
        assertEquals("7/7/7/7/7/7/2x4 o 1 1", q.toFen());
    }

    @Test
    void cloneResetsHalfmoveClock() {
        Position p = Position.fromFen("o6/7/7/7/7/7/x6 x 7 3");
        assertEquals(0, p.makeMove(Move.parse("a2")).halfmoveClock());
        assertEquals(8, p.makeMove(Move.parse("a1a3")).halfmoveClock());
    }

    @Test
    void fullmoveIncrementsAfterO() {
        Position p = Position.startPos();
        Position q = p.makeMove(moves(p)[0]);
        assertEquals(1, q.fullmoveNumber());
        assertEquals(2, q.makeMove(moves(q)[0]).fullmoveNumber());
    }

    @Test
    void wallsBlockMoves() {
        Position p = Position.fromFen("6o/7/7/7/7/-x-4/---4 x 0 1");
        int[] ms = moves(p);
        int clones = 0;
        for (int m : ms) {
            assertEquals(0L, (1L << Move.to(m)) & p.walls());
            if (Move.isClone(m)) {
                clones++;
            }
        }
        assertEquals(3, clones); // a3, b3, c3 only
        assertTrue(ms.length > 3);
    }

    @Test
    void passWhenStuckButOpponentCanMove() {
        // x on a7 is walled in, including all jump squares; o on g1 is free.
        Position r = Position.fromFen("x--4/---4/---4/7/7/7/6o x 0 1");
        assertFalse(r.hasMove(Position.X));
        assertTrue(r.hasMove(Position.O));
        assertFalse(r.isGameOver());
        assertEquals(1, moves(r).length);
        assertEquals(Move.PASS, moves(r)[0]);
        assertTrue(r.isLegal(Move.PASS));
        Position after = r.makeMove(Move.PASS);
        assertEquals(Position.O, after.sideToMove());
        assertEquals(1, after.halfmoveClock());
        assertEquals(r.pieces(Position.X), after.pieces(Position.X));
    }

    @Test
    void gameOverWhenNeitherCanMove() {
        Position p = Position.fromFen("x--4/---4/---4/7/4---/4---/4--o x 0 1");
        assertFalse(p.hasMove(Position.X));
        assertFalse(p.hasMove(Position.O));
        assertTrue(p.isGameOver());
        assertEquals(Outcome.DRAW, p.outcome());
        assertEquals(0, moves(p).length);
    }

    @Test
    void gameOverWhenSideWipedOut() {
        Position p = Position.fromFen("7/7/7/7/7/7/xo5 x 0 1");
        Position q = p.makeMove(Move.parse("c1")); // clone to c1 converts o at b1
        assertTrue(q.isGameOver());
        assertEquals(Outcome.X_WINS, q.outcome());
        assertEquals(0, moves(q).length);
    }

    @Test
    void gameOverWhenBoardFull() {
        Position p = Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxoo o 0 1");
        assertTrue(p.isGameOver());
        assertEquals(Outcome.X_WINS, p.outcome());
        Position d = Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/xoooooo/oooooo-/oooooo-/oooooo- x 0 1");
        assertTrue(d.isGameOver());
    }

    @Test
    void halfmoveLimitEndsGame() {
        Position p = Position.fromFen("x5o/7/7/7/7/7/o5x x 100 60");
        assertTrue(p.isGameOver());
        assertEquals(Outcome.DRAW, p.outcome());
        assertEquals(0, moves(p).length);
        assertFalse(Position.fromFen("x5o/7/7/7/7/7/o5x x 99 60").isGameOver());
    }

    @Test
    void captureMovesAreTheFilteredLegalMovesInTheSameOrder() {
        java.util.Random rnd = new java.util.Random(55);
        int[] all = new int[Position.MAX_MOVES];
        int[] caps = new int[Position.MAX_MOVES];
        int checked = 0;
        for (int game = 0; game < 150; game++) {
            Position p = game % 3 == 0 ? Position.fromFen("x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1") : Position.startPos();
            for (int ply = 0; ply < 250 && !p.isGameOver(); ply++) {
                int n = p.generateMoves(all);
                for (int min = 1; min <= 5; min++) {
                    int expected = 0;
                    int[] want = new int[Position.MAX_MOVES];
                    for (int i = 0; i < n; i++) {
                        if (all[i] != Move.PASS && p.captureCount(all[i]) >= min) {
                            want[expected++] = all[i];
                        }
                    }
                    int got = p.generateCaptureMoves(caps, min);
                    assertEquals(expected, got, "min " + min + "\n" + p);
                    for (int i = 0; i < got; i++) {
                        assertEquals(want[i], caps[i], "index " + i + " min " + min + "\n" + p);
                    }
                    checked++;
                }
                p = p.makeMove(all[rnd.nextInt(n)]);
            }
        }
        assertTrue(checked > 5000);
    }

    @Test
    void scoredCaptureMovesMatchTheUnscoredOnesAndScoreByConversions() {
        java.util.Random rnd = new java.util.Random(77);
        int[] all = new int[Position.MAX_MOVES];
        int[] caps = new int[Position.MAX_MOVES];
        int[] moves = new int[Position.MAX_MOVES];
        int[] scores = new int[Position.MAX_MOVES];
        int checked = 0;
        for (int game = 0; game < 100; game++) {
            Position p = game % 3 == 0 ? Position.fromFen("x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1") : Position.startPos();
            for (int ply = 0; ply < 250 && !p.isGameOver(); ply++) {
                for (int min = 0; min <= 5; min++) {
                    int want = p.generateCaptureMoves(caps, min);
                    int got = p.generateScoredCaptureMoves(moves, scores, min);
                    assertEquals(want, got);
                    for (int i = 0; i < got; i++) {
                        assertEquals(caps[i], moves[i]);
                        long converted = Bitboards.expand1(1L << Move.to(moves[i])) & p.pieces(1 - p.sideToMove());
                        assertEquals(2 * Long.bitCount(converted) + (Move.isClone(moves[i]) ? 1 : 0), scores[i]);
                        assertEquals(Long.bitCount(converted), p.captureCount(moves[i]));
                        checked++;
                    }
                }
                int n = p.generateMoves(all);
                p = p.makeMove(all[rnd.nextInt(n)]);
            }
        }
        assertTrue(checked > 5000);
    }

    @Test
    void captureMovesOfFinishedGameAreEmpty() {
        Position over = Position.fromFen("xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxoo o 0 1");
        assertEquals(0, over.generateCaptureMoves(new int[Position.MAX_MOVES], 1));
    }

    @Test
    void nullMoveIsAPassThatOnlyFlipsTheSideAndCountsAHalfMove() {
        Position p = Position.fromFen("x5o/1x5/7/7/7/7/o5x x 3 5");
        Position q = p.makeMove(Move.PASS);
        assertEquals(p.pieces(Position.X), q.pieces(Position.X));
        assertEquals(p.pieces(Position.O), q.pieces(Position.O));
        assertEquals(Position.O, q.sideToMove());
        assertEquals(p.halfmoveClock() + 1, q.halfmoveClock());
        assertEquals(p.fullmoveNumber(), q.fullmoveNumber());
        assertEquals(Position.fromFen(q.toFen()).hash(), q.hash());
        assertTrue(p.hash() != q.hash());
        assertEquals(p.hash(), q.makeMove(Move.PASS).hash(), "two passes restore the hash");
    }

    @Test
    void isLegal() {
        Position p = Position.startPos();
        assertTrue(p.isLegal(Move.parse("b6")));
        assertTrue(p.isLegal(Move.parse("a7c7")));
        assertFalse(p.isLegal(Move.parse("a7d7")));
        assertFalse(p.isLegal(Move.parse("a1c1"))); // not x's piece
        assertFalse(p.isLegal(Move.PASS));
    }
}
