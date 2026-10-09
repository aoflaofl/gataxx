package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- reach / territory / edge, checked against deliberately naive loop implementations ----

    private static int cheb(int p, int q) {
        return Math.max(Math.abs(p % 7 - q % 7), Math.abs(p / 7 - q / 7));
    }

    private static char at(Position pos, int sq) {
        long bit = 1L << sq;
        return (pos.pieces(Position.X) & bit) != 0 ? 'x' : (pos.pieces(Position.O) & bit) != 0 ? 'o'
                : (pos.walls() & bit) != 0 ? '-' : '.';
    }

    /** Can {@code side} land a piece on empty square {@code e} next move (clone or jump)? */
    private static boolean canReach(Position pos, char side, int e) {
        for (int q = 0; q < 49; q++) {
            if (at(pos, q) == side && cheb(q, e) <= 2) {
                return true;
            }
        }
        return false;
    }

    private static int naiveReachTerritoryEdge(Position pos, Weights w) {
        char me = pos.sideToMove() == Position.X ? 'x' : 'o';
        char op = me == 'x' ? 'o' : 'x';
        int protectedMe = 0;
        int protectedOp = 0;
        int terrMe = 0;
        int terrOp = 0;
        int edgeMe = 0;
        int edgeOp = 0;
        for (int sq = 0; sq < 49; sq++) {
            char c = at(pos, sq);
            if (c == '.') {
                boolean m = canReach(pos, me, sq);
                boolean o = canReach(pos, op, sq);
                terrMe += m && !o ? 1 : 0;
                terrOp += o && !m ? 1 : 0;
            } else if (c == me || c == op) {
                char enemy = c == me ? op : me;
                boolean threatened = false;
                for (int e = 0; e < 49; e++) {
                    if (at(pos, e) == '.' && cheb(sq, e) == 1 && canReach(pos, enemy, e)) {
                        threatened = true;
                    }
                }
                boolean onEdge = sq % 7 == 0 || sq % 7 == 6 || sq / 7 == 0 || sq / 7 == 6;
                if (c == me) {
                    protectedMe += threatened ? 0 : 1;
                    edgeMe += onEdge ? 1 : 0;
                } else {
                    protectedOp += threatened ? 0 : 1;
                    edgeOp += onEdge ? 1 : 0;
                }
            }
        }
        return w.reach() * (protectedMe - protectedOp) + w.territory() * (terrMe - terrOp) + w.edge() * (edgeMe - edgeOp);
    }

    /** Turns a board with '.' for empties into FEN, where runs of empties are digits. */
    private static String runLengthEncode(String board) {
        StringBuilder out = new StringBuilder();
        int run = 0;
        for (char c : board.toCharArray()) {
            if (c == '.') {
                run++;
                continue;
            }
            if (run > 0) {
                out.append(run);
                run = 0;
            }
            out.append(c);
        }
        if (run > 0) {
            out.append(run);
        }
        return out.toString();
    }

    private static int naiveCornerRing1CohesionThreat(Position pos, Weights w) {
        char me = pos.sideToMove() == Position.X ? 'x' : 'o';
        char op = me == 'x' ? 'o' : 'x';
        int[] cornerD = new int[1];
        int corner = 0;
        int ring1 = 0;
        int cohesion = 0;
        int threat = 0;
        for (int p = 0; p < 49; p++) {
            char c = at(pos, p);
            if (c != me && c != op) {
                continue;
            }
            int sign = c == me ? 1 : -1;
            char enemy = c == me ? op : me;
            int f = p % 7;
            int r = p / 7;
            int depth = Math.min(Math.min(f, 6 - f), Math.min(r, 6 - r));
            if ((f == 0 || f == 6) && (r == 0 || r == 6)) {
                corner += sign;
            }
            if (depth == 1) {
                ring1 += sign;
            }
            for (int q = 0; q < 49; q++) {
                if (q == p || cheb(p, q) != 1) {
                    continue;
                }
                if (at(pos, q) == c) {
                    cohesion += sign;
                }
                if (at(pos, q) == '.' && canReach(pos, enemy, q)) {
                    threat += sign;
                }
            }
        }
        return w.corner() * corner + w.ring1() * ring1 + w.cohesion() * cohesion / FeatureEvaluator.FINE
                + w.threat() * threat / FeatureEvaluator.FINE;
    }

    @Test
    void cornerRing1CohesionThreatMatchNaiveImplementation() {
        Random rnd = new Random(1234);
        Weights w = new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 5, 7, 11);
        FeatureEvaluator f = new FeatureEvaluator(w);
        for (int i = 0; i < 400; i++) {
            double wallRate = rnd.nextDouble() * 0.3;
            double pieceRate = rnd.nextDouble() * 0.7;
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 7; r++) {
                for (int c = 0; c < 7; c++) {
                    double d = rnd.nextDouble();
                    sb.append(d < wallRate ? '-' : d < wallRate + pieceRate ? (rnd.nextBoolean() ? 'x' : 'o') : '.');
                }
                if (r < 6) {
                    sb.append('/');
                }
            }
            Position pos = Position.fromFen(runLengthEncode(sb.toString()) + (rnd.nextBoolean() ? " x 0 1" : " o 0 1"));
            assertEquals(naiveCornerRing1CohesionThreat(pos, w), f.evaluate(pos), pos.toString());
        }
    }

    @Test
    void cornerRing1CohesionThreatOnHandBuiltPositions() {
        // x on a1 and b2 (corner + second-ring), o on g7 (corner). x to move.
        String fen = "6o/7/7/7/7/1x5/x6 x 0 1";
        assertEquals(1 - 1, eval(fen, new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0)));  // corners 1 v 1
        assertEquals(1, eval(fen, new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0)));      // ring1: b2 v none
        // cohesion: x has the pair a1-b2 (counted both ways = 2); o has none. Weight is in 1/FINE units.
        assertEquals(2, eval(fen, new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, FeatureEvaluator.FINE, 0)));
        assertEquals(-1, eval(fen, new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -2, 0))); // -2*2/4
        // threat: the enemy (g7) reaches only squares within 2 of g7, none beside x; x's reach covers
        // squares up to c3/d4.. none beside o. Both 0.
        assertEquals(0, eval(fen, new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        // Now bring o's piece within range: o on c1, x on a1: o can land on b1/b2/a2 beside x and vice versa.
        // x a1 has 3 empty neighbours, all reachable by o (c1 reaches a2? cheb((c1),(a2))=2 yes): 3.
        // o c1 has empty neighbours b1,b2,c2,d1,d2; x (a1) reaches b1,b2,c2 (cheb<=2): 3 -> 3 - 3 = 0.
        assertEquals(0, eval("7/7/7/7/7/7/x1o4 x 0 1", new Weights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
    }

    @Test
    void newFeaturesMatchNaiveImplementationOnRandomPositions() {
        Random rnd = new Random(77);
        Weights w = new Weights(0, 0, 0, 0, 3, 5, 7, 0);
        FeatureEvaluator f = new FeatureEvaluator(w);
        for (int i = 0; i < 400; i++) {
            double wallRate = rnd.nextDouble() * 0.3;
            double pieceRate = rnd.nextDouble() * 0.6;
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 7; r++) {
                for (int c = 0; c < 7; c++) {
                    double d = rnd.nextDouble();
                    sb.append(d < wallRate ? '-' : d < wallRate + pieceRate ? (rnd.nextBoolean() ? 'x' : 'o') : '.');
                }
                if (r < 6) {
                    sb.append('/');
                }
            }
            String fen = runLengthEncode(sb.toString());
            Position pos = Position.fromFen(fen + (rnd.nextBoolean() ? " x 0 1" : " o 0 1"));
            assertEquals(naiveReachTerritoryEdge(pos, w), f.evaluate(pos), pos.toString());
        }
    }

    @Test
    void reachTerritoryEdgeOnHandBuiltPositions() {
        // Opposite corners, far apart: symmetric on every new feature.
        String corners = "6o/7/7/7/7/7/x6 x 0 1";
        assertEquals(0, eval(corners, new Weights(0, 0, 0, 0, 4, 4, 5, 0)));

        // x on a1 sealed in by walls on a2, b1, b2; o alone on d4.
        String pocket = "7/7/7/3o3/7/--5/x-5 x 0 1";
        // x is protected (no empty neighbours); o is threatened (x can land on c3 beside it): 1 - 0.
        assertEquals(1, eval(pocket, new Weights(0, 0, 0, 0, 1, 0, 0, 0)));
        // x alone reaches a3 and c1 (2); o alone reaches the other 20 squares of its 5x5 area: 2 - 20.
        assertEquals(-18, eval(pocket, new Weights(0, 0, 0, 0, 0, 1, 0, 0)));
        // x is on the edge, o is not.
        assertEquals(1, eval(pocket, new Weights(0, 0, 0, 0, 0, 0, 1, 0)));
        // The same position from o's side flips every sign.
        String pocketO = "7/7/7/3o3/7/--5/x-5 o 0 1";
        assertEquals(-1, eval(pocketO, new Weights(0, 0, 0, 0, 1, 0, 0, 0)));
        assertEquals(18, eval(pocketO, new Weights(0, 0, 0, 0, 0, 1, 0, 0)));
        assertEquals(-1, eval(pocketO, new Weights(0, 0, 0, 0, 0, 0, 1, 0)));
    }

    @Test
    void fadeScalesPositionalFeaturesByEmptySquares() {
        Weights none = new Weights(16, 0, 0, 0, 0, 0, 0, 32);
        Weights noFade = new Weights(16, 4, 0, 0, 4, 0, 8, 32);
        Random rnd = new Random(12);
        int[] buf = new int[Position.MAX_MOVES];
        Position p = Position.startPos();
        for (int ply = 0; ply < 90 && !p.isGameOver(); ply++) {
            int empties = Long.bitCount(p.empty());
            int base = new FeatureEvaluator(none).evaluate(p);
            int positional = new FeatureEvaluator(noFade).evaluate(p) - base;
            for (int fade : new int[] {1, 6, 12, 30}) {
                Weights faded = new Weights(16, 4, 0, 0, 4, 0, 8, 32, fade);
                assertEquals(base + positional * Math.min(empties, fade) / fade,
                        new FeatureEvaluator(faded).evaluate(p), "fade " + fade + " empties " + empties);
            }
            int n = p.generateMoves(buf);
            p = p.makeMove(buf[rnd.nextInt(n)]);
        }
    }

    @Test
    void fadeOffAndFullBoardBehaviour() {
        Weights noFade = new Weights(16, 4, 0, 0, 4, 0, 8, 32);
        Weights fadeOff = new Weights(16, 4, 0, 0, 4, 0, 8, 32, 0);
        assertEquals(noFade, fadeOff);
        // No empty squares: positional features are scaled to zero, leaving material and tempo.
        String full = "xxxxxxx/xxxxxxx/xxxxxxx/xxxooox/xxxxxxx/xxxxxxx/xxxxxxx x 0 1";
        assertEquals(16 * (46 - 3) + 32, eval(full, new Weights(16, 4, 0, 0, 4, 0, 8, 32, 10)));
        // Unfaded: base 16*43+32 = 720, plus safe 4*43 + reach 4*43 (nothing is threatened with no empty
        // squares) + edge 8*24 (x holds all 24 edge squares, o none) = 536.
        assertEquals(720 + 536, eval(full, noFade));
    }

    @Test
    void rawFeaturesReproduceTheEvaluation() {
        // Weights are multiples of FINE so the per-term integer division in evaluate() is exact.
        int[] w = {16, 4, 2, -1, 4, 3, 8, 5, -2, -3 * FeatureEvaluator.FINE, -2 * FeatureEvaluator.FINE};
        Weights weights = new Weights(w[0], w[1], w[2], w[3], w[4], w[5], w[6], 32, 0, w[7], w[8],
                -3 * FeatureEvaluator.FINE, -2 * FeatureEvaluator.FINE);
        FeatureEvaluator f = new FeatureEvaluator(weights);
        Random rnd = new Random(3);
        int[] buf = new int[Position.MAX_MOVES];
        int[] raw = new int[FeatureEvaluator.FEATURE_NAMES.length];
        Position p = Position.startPos();
        for (int i = 0; i < 400; i++) {
            if (p.isGameOver()) {
                p = Position.startPos();
            }
            FeatureEvaluator.rawFeatures(p, raw);
            // order: material, safe, mobility, exposure, reach, territory, edge, corner, ring1, cohesion, threat
            int expected = 32 + 16 * raw[0] + 4 * raw[1] + 2 * raw[2] - 1 * raw[3] + 4 * raw[4] + 3 * raw[5]
                    + 8 * raw[6] + 5 * raw[7] - 2 * raw[8] - 3 * raw[9] - 2 * raw[10];
            assertEquals(expected, f.evaluate(p), p.toString());
            int n = p.generateMoves(buf);
            p = p.makeMove(buf[rnd.nextInt(n)]);
        }
    }

    @Test
    void rawFeaturesAreAntisymmetricInTheSideToMove() {
        Random rnd = new Random(4);
        int[] buf = new int[Position.MAX_MOVES];
        int[] a = new int[FeatureEvaluator.FEATURE_NAMES.length];
        int[] b = new int[FeatureEvaluator.FEATURE_NAMES.length];
        Position p = Position.startPos();
        for (int i = 0; i < 80 && !p.isGameOver(); i++) {
            String[] t = p.toFen().split(" ");
            Position flipped = Position.fromFen(t[0] + " " + (t[1].equals("x") ? "o" : "x") + " 0 1");
            FeatureEvaluator.rawFeatures(p, a);
            FeatureEvaluator.rawFeatures(flipped, b);
            for (int k = 0; k < a.length; k++) {
                assertEquals(-a[k], b[k], FeatureEvaluator.FEATURE_NAMES[k] + "\n" + p);
            }
            int n = p.generateMoves(buf);
            p = p.makeMove(buf[rnd.nextInt(n)]);
        }
    }

    @Test
    void denseAndBitesMatchNaiveCountsOnHandBuiltPositions() {
        int[] raw = new int[FeatureEvaluator.FEATURE_NAMES.length];
        // x block 3x3 in the middle: the centre piece has 8 own neighbours, the 4 edge-middles have 5, the
        // corners of the block have 3. So dense (>= 4 own neighbours) = 5 for x, 0 for the lone o.
        FeatureEvaluator.rawFeatures(Position.fromFen("6o/7/7/2xxx2/2xxx2/2xxx2/7 x 0 1"), raw);
        assertEquals(5, raw[11]);
        // o on g7 can land on empty squares beside the x block? o reaches only within 2 of g7: none beside x.
        // x reaches squares within 2 of the block, which covers every empty neighbour of o: no square beside o
        // has 3 o neighbours (o is alone), so bites against o are 0; o cannot reach the block: 0.
        assertEquals(0, raw[12]);
        // o on d4, e4 and c3 surround the empty d3, and x on b1 is within two squares of d3: x could land there
        // and convert all three. The feature is (bites against the mover) - (bites against the other side), so
        // from x's move it is 0 - 1 = -1 and from o's move it is 1 - 0 = +1.
        FeatureEvaluator.rawFeatures(Position.fromFen("7/7/7/3oo2/2o4/7/1x5 x 0 1"), raw);
        assertEquals(-1, raw[12]);
        assertTrue(raw[13] < 0, "same sign convention as bites3: the mover has the bite, so the difference is negative: " + raw[13]);
        FeatureEvaluator.rawFeatures(Position.fromFen("7/7/7/3oo2/2o4/7/1x5 o 0 1"), raw);
        assertEquals(1, raw[12]);
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
