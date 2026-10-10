package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;

class LocalPatternsTest {
    @Test
    void featureCountMatchesTheCombinatorics() {
        // Per state: corner 10 (3 neighbours), three edge types 21 each, six interior types 45 each; three states.
        assertEquals(3 * (10 + 3 * 21 + 6 * 45), LocalPatterns.SIZE);
        assertEquals(1029, LocalPatterns.SIZE);
    }

    @Test
    void indicesAreUniqueAndDense() {
        Set<Integer> seen = new HashSet<>();
        for (int state = 0; state < 3; state++) {
            for (int cls = 0; cls < 10; cls++) {
                int n = LocalPatterns.neighbourCount(cls);
                for (int m = 0; m <= 8; m++) {
                    for (int t = 0; t <= 8; t++) {
                        int idx = LocalPatterns.index(state, cls, m, t);
                        if (m + t <= n) {
                            assertTrue(idx >= 0 && idx < LocalPatterns.SIZE);
                            assertTrue(seen.add(idx), "duplicate index");
                        } else {
                            assertEquals(-1, idx);
                        }
                    }
                }
            }
        }
        assertEquals(LocalPatterns.SIZE, seen.size());
    }

    @Test
    void startPositionHandCheck() {
        int[] idx = new int[49];
        // x on a7 and g1, o on a1 and g7; x to move.
        assertEquals(49, LocalPatterns.features(Position.startPos(), idx));
        // a1 (square 0) is an enemy stone in a corner with no stone neighbours.
        assertEquals(LocalPatterns.index(LocalPatterns.THEIRS, 0, 0, 0), idx[0]);
        // g1 (square 6) is ours, a corner too.
        assertEquals(LocalPatterns.index(LocalPatterns.MINE, 0, 0, 0), idx[6]);
        // b1 (square 1): empty, edge square type (0,1), next to a1 (an enemy stone) and nothing else.
        assertEquals(LocalPatterns.index(LocalPatterns.EMPTY, 1, 0, 1), idx[1]);
        // The centre d4 (square 24) is empty with all eight neighbours empty.
        assertEquals(LocalPatterns.index(LocalPatterns.EMPTY, 9, 0, 0), idx[24]);
    }

    @Test
    void perspectiveFlipsWithTheSideToMove() {
        int[] x = new int[49];
        int[] o = new int[49];
        LocalPatterns.features(Position.fromFen("x5o/1x5/7/7/7/7/o5x x 0 1"), x);
        LocalPatterns.features(Position.fromFen("x5o/1x5/7/7/7/7/o5x o 0 1"), o);
        assertNotEquals(x[42], o[42]); // a7 holds an x stone: friendly for x, enemy for o
        assertEquals(LocalPatterns.index(LocalPatterns.THEIRS, 0, 0, 1), o[42]);
    }

    @Test
    void matchesNaiveCountsOnRandomPositionsAndSkipsWalls() {
        Random rnd = new Random(5);
        int[] idx = new int[49];
        for (int i = 0; i < 300; i++) {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 7; r++) {
                int empty = 0;
                for (int c = 0; c < 7; c++) {
                    int k = rnd.nextInt(10);
                    char ch = k < 3 ? 'x' : k < 6 ? 'o' : k == 6 ? '-' : 0;
                    if (ch == 0) {
                        empty++;
                    } else {
                        if (empty > 0) {
                            sb.append(empty);
                            empty = 0;
                        }
                        sb.append(ch);
                    }
                }
                if (empty > 0) {
                    sb.append(empty);
                }
                if (r < 6) {
                    sb.append('/');
                }
            }
            Position pos = Position.fromFen(sb + (rnd.nextBoolean() ? " x 0 1" : " o 0 1"));
            int expectedFeatured = 49 - Long.bitCount(pos.walls());
            assertEquals(expectedFeatured, LocalPatterns.features(pos, idx));
            long mine = pos.pieces(pos.sideToMove());
            long theirs = pos.pieces(1 - pos.sideToMove());
            for (int sq = 0; sq < 49; sq++) {
                if ((pos.walls() >>> sq & 1) != 0) {
                    assertEquals(-1, idx[sq]);
                    continue;
                }
                int m = 0;
                int t = 0;
                for (int q = 0; q < 49; q++) {
                    if (q != sq && Math.max(Math.abs(q % 7 - sq % 7), Math.abs(q / 7 - sq / 7)) == 1) {
                        m += (mine >>> q & 1) != 0 ? 1 : 0;
                        t += (theirs >>> q & 1) != 0 ? 1 : 0;
                    }
                }
                int state = (mine >>> sq & 1) != 0 ? LocalPatterns.MINE : (theirs >>> sq & 1) != 0 ? LocalPatterns.THEIRS : LocalPatterns.EMPTY;
                assertEquals(LocalPatterns.index(state, FeatureEvaluator.SQUARE_CLASS[sq], m, t), idx[sq], "square " + sq + "\n" + pos);
            }
        }
    }
}
