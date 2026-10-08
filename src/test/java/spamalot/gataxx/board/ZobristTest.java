package spamalot.gataxx.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ZobristTest {
    private static long scratchHash(Position p) {
        return Zobrist.compute(p.pieces(Position.X), p.pieces(Position.O), p.walls(), p.sideToMove());
    }

    @Test
    void incrementalHashMatchesFromScratchOverRandomGames() {
        Random rnd = new Random(99);
        int[] buf = new int[Position.MAX_MOVES];
        for (int game = 0; game < 200; game++) {
            Position p = Position.startPos();
            if (game % 3 == 0) {
                p = Position.fromFen("x5o/7/2-1-2/3-3/2-1-2/7/o5x x 0 1");
            }
            for (int ply = 0; ply < 400; ply++) {
                assertEquals(scratchHash(p), p.hash(), "game " + game + " ply " + ply + "\n" + p);
                int n = p.generateMoves(buf);
                if (n == 0) {
                    break;
                }
                p = p.makeMove(buf[rnd.nextInt(n)]);
            }
        }
    }

    @Test
    void fenAndMovesAgree() {
        Position viaMoves = Position.startPos().makeMove(Move.parse("b6")).makeMove(Move.parse("a1a3"));
        assertEquals(viaMoves.hash(), Position.fromFen(viaMoves.toFen()).hash());
    }

    @Test
    void transpositionsShareAHash() {
        Position a = Position.startPos();
        Position one = a.makeMove(Move.parse("b6")).makeMove(Move.parse("f2"))
                .makeMove(Move.parse("b5")).makeMove(Move.parse("f3"));
        Position two = a.makeMove(Move.parse("b5")).makeMove(Move.parse("f3"))
                .makeMove(Move.parse("b6")).makeMove(Move.parse("f2"));
        assertEquals(one, two);
        assertEquals(one.hash(), two.hash());
    }

    @Test
    void sideToMoveAndWallsChangeTheHash() {
        Position x = Position.fromFen("x5o/7/7/7/7/7/o5x x 0 1");
        Position o = Position.fromFen("x5o/7/7/7/7/7/o5x o 0 1");
        Position walled = Position.fromFen("x5o/7/3-3/7/7/7/o5x x 0 1");
        assertNotEquals(x.hash(), o.hash());
        assertNotEquals(x.hash(), walled.hash());
    }

    @Test
    void fewCollisionsAcrossManyPositions() {
        Random rnd = new Random(1);
        int[] buf = new int[Position.MAX_MOVES];
        Set<Position> positions = new HashSet<>();
        Set<Long> hashes = new HashSet<>();
        for (int game = 0; game < 300; game++) {
            Position p = Position.startPos();
            for (int ply = 0; ply < 60; ply++) {
                int n = p.generateMoves(buf);
                if (n == 0) {
                    break;
                }
                p = p.makeMove(buf[rnd.nextInt(n)]);
                // Clock fields aren't hashed, so key positions by board+side only.
                String key = p.toFen().substring(0, p.toFen().indexOf(' ', p.toFen().indexOf(' ') + 1));
                if (positions.add(Position.fromFen(key))) {
                    hashes.add(p.hash());
                }
            }
        }
        assertEquals(positions.size(), hashes.size());
    }
}
