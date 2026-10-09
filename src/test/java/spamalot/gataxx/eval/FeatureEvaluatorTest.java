package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Bitboards;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.FeatureEvaluator.Weights;

class FeatureEvaluatorTest {
    private static int eval(String fen, Weights w) {
        return new FeatureEvaluator(w).evaluate(Position.fromFen(fen));
    }

    private static Weights only(int material, int safe, int mobility, int exposure) {
        return new Weights(material, safe, mobility, exposure, 0);
    }

    @Test
    void baselineIsMaterialPlusTempoInSubPieceUnits() {
        Random rnd = new Random(1);
        int[] buf = new int[Position.MAX_MOVES];
        FeatureEvaluator f = new FeatureEvaluator(Weights.baseline());
        MaterialEvaluator tempo2 = new MaterialEvaluator(2);
        Position p = Position.startPos();
        for (int i = 0; i < 40; i++) {
            if (p.isGameOver()) {
                break;
            }
            assertEquals(tempo2.evaluate(p) * FeatureEvaluator.SCALE, f.evaluate(p));
            int n = p.generateMoves(buf);
            p = p.makeMove(buf[rnd.nextInt(n)]);
        }
    }

    @Test
    void materialAndTempo() {
        // x has 3 pieces, o has 1; x to move.
        assertEquals(2 * 16, eval("xxx4/7/7/7/7/7/6o x 0 1", only(16, 0, 0, 0)));
        assertEquals(2 * 16 + 5, eval("xxx4/7/7/7/7/7/6o x 0 1", new Weights(16, 0, 0, 0, 5)));
        assertEquals(-2 * 16, eval("xxx4/7/7/7/7/7/6o o 0 1", only(16, 0, 0, 0)));
    }

    @Test
    void safePiecesAreThoseWithoutEmptyNeighbours() {
        // x: a1, b1, b2 with a wall on a2. a1's neighbours (a2 wall, b1, b2) are all occupied: safe.
        // b1 and b2 touch the empty c1: not safe. The lone o on g7 is not safe either.
        assertEquals(1, eval("6o/7/7/7/7/-x5/xx5 x 0 1", only(0, 1, 0, 0)));
        assertEquals(-1, eval("6o/7/7/7/7/-x5/xx5 o 0 1", only(0, 1, 0, 0)));
    }

    @Test
    void mobilityCountsDistinctCloneTargets() {
        // Lone x at a1: targets a2,b1,b2 = 3. Lone o at g7: f7,f6,g6 = 3. Equal -> 0.
        assertEquals(0, eval("6o/7/7/7/7/7/x6 x 0 1", only(0, 0, 1, 0)));
        // x at a1 and b1: targets a2,b2,c1,c2 = 4 vs 3.
        assertEquals(1, eval("6o/7/7/7/7/7/xx5 x 0 1", only(0, 0, 1, 0)));
        // walls remove targets: a2 and b2 walls -> only c1,c2? x a1,b1: targets c1,c2 = 2 vs 3.
        assertEquals(-1, eval("6o/7/7/7/7/--5/xx5 x 0 1", only(0, 0, 1, 0)));
    }

    @Test
    void exposureCountsPieceEmptyAdjacencies() {
        // Lone x in a corner touches 3 empties; lone o in the centre touches 8.
        assertEquals(3 - 8, eval("7/7/7/3o3/7/7/x6 x 0 1", only(0, 0, 0, 1)));
        // Corner piece next to a wall touches fewer empties.
        assertEquals(2 - 8, eval("7/7/7/3o3/7/7/x-5 x 0 1", only(0, 0, 0, 1)));
    }

    @Test
    void everythingIsSafeOnAFullBoard() {
        // 46 x and 3 o, no empty squares: safe difference equals material difference.
        String full = "xxxxxxx/xxxxxxx/xxxxxxx/xxxooox/xxxxxxx/xxxxxxx/xxxxxxx x 0 1";
        assertEquals(46 - 3, eval(full, only(0, 1, 0, 0)));
    }

    @Test
    void antisymmetricWithoutTempoAndColourBlind() {
        Weights w = new Weights(16, 5, 3, 2, 0);
        Random rnd = new Random(8);
        int[] buf = new int[Position.MAX_MOVES];
        Position p = Position.startPos();
        for (int i = 0; i < 60 && !p.isGameOver(); i++) {
            String fen = p.toFen();
            String[] t = fen.split(" ");
            String flippedSide = t[0] + " " + (t[1].equals("x") ? "o" : "x") + " 0 1";
            String swapped = (t[0].replace('x', 'T').replace('o', 'x').replace('T', 'o')) + " " + (t[1].equals("x") ? "o" : "x") + " 0 1";
            int v = eval(fen, w);
            assertEquals(-v, eval(flippedSide, w), "same board, other mover: " + fen);
            assertEquals(v, eval(swapped, w), "colours swapped: " + fen);
            int n = p.generateMoves(buf);
            p = p.makeMove(buf[rnd.nextInt(n)]);
        }
    }

    @Test
    void shiftsDoNotWrapAcrossFiles() {
        long gFile = 0;
        long aFile = 0;
        for (int r = 0; r < 7; r++) {
            gFile |= 1L << (r * 7 + 6);
            aFile |= 1L << (r * 7);
        }
        assertEquals(0, Bitboards.shiftEast(gFile));
        assertEquals(0, Bitboards.shiftWest(aFile));
        assertEquals(0, Bitboards.shiftNorth(0b1111111L << 42));
        assertEquals(0, Bitboards.shiftSouth(0b1111111L));
        assertEquals(1L << 8, Bitboards.shiftEast(1L << 7));
    }

    @Test
    void adjacentPairsMatchesNaiveCount() {
        Random rnd = new Random(5);
        for (int i = 0; i < 300; i++) {
            long a = rnd.nextLong() & Bitboards.ALL;
            long b = rnd.nextLong() & Bitboards.ALL & ~a;
            int naive = 0;
            for (int p = 0; p < 49; p++) {
                if ((a >>> p & 1) == 0) {
                    continue;
                }
                for (int q = 0; q < 49; q++) {
                    if ((b >>> q & 1) == 0) {
                        continue;
                    }
                    int df = Math.abs(p % 7 - q % 7);
                    int dr = Math.abs(p / 7 - q / 7);
                    if (Math.max(df, dr) == 1) {
                        naive++;
                    }
                }
            }
            assertEquals(naive, Bitboards.adjacentPairs(a, b));
        }
    }
}
