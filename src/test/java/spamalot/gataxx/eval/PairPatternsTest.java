package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Bitboards;
import spamalot.gataxx.board.Position;

class PairPatternsTest {
    private static long transformBoard(long board, int t) {
        long out = 0;
        for (long b = board; b != 0; b &= b - 1) {
            out |= 1L << PairPatterns.transform(Long.numberOfTrailingZeros(b), t);
        }
        return out;
    }

    /** A deterministic pseudo-weight of a tie class. */
    private static long weightOf(int key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (h ^ (h >>> 29)) % 1000;
    }

    private static long sumOfClassWeights(long mine, long theirs, long walls) {
        int[] blocks = new int[49];
        int[] pat = new int[PairPatterns.PLACEMENTS];
        PairPatterns.patterns(mine, theirs, walls, blocks, pat);
        long sum = 0;
        for (int p = 0; p < PairPatterns.PLACEMENTS; p++) {
            sum += weightOf(PairPatterns.canonicalKey(p, pat[p]));
        }
        return sum;
    }

    @Test
    void tiedWeightsGiveTheSameEvaluationForEverySymmetricImage() {
        Random rnd = new Random(2024);
        for (int trial = 0; trial < 300; trial++) {
            long all = Bitboards.ALL;
            long mine = rnd.nextLong() & rnd.nextLong() & all;
            long theirs = rnd.nextLong() & rnd.nextLong() & all & ~mine;
            long walls = trial % 3 == 0 ? rnd.nextLong() & rnd.nextLong() & rnd.nextLong() & all & ~mine & ~theirs : 0;
            long expected = sumOfClassWeights(mine, theirs, walls);
            for (int t = 1; t < 8; t++) {
                long got = sumOfClassWeights(transformBoard(mine, t), transformBoard(theirs, t), transformBoard(walls, t));
                assertEquals(expected, got, "trial " + trial + " transform " + t);
            }
        }
    }

    @Test
    void canonicalKeysAreRepresentativesOfTheirClass() {
        Random rnd = new Random(7);
        for (int i = 0; i < 2000; i++) {
            int placement = rnd.nextInt(PairPatterns.PLACEMENTS);
            int pattern = rnd.nextInt(PairPatterns.INDEX_SIZE);
            int c = PairPatterns.canonicalKey(placement, pattern);
            assertEquals(c, PairPatterns.canonicalKey(c >>> 16, c & 0xFFFF));
            for (int image : PairPatterns.imageKeys(placement, pattern)) {
                assertEquals(c, PairPatterns.canonicalKey(image >>> 16, image & 0xFFFF));
            }
        }
    }

    @Test
    void piecelessBlocksAreCountedAsZero() {
        int[] blocks = new int[49];
        int[] pat = new int[PairPatterns.PLACEMENTS];
        PairPatterns.patterns(0, 0, 0, blocks, pat);
        for (int p : pat) {
            assertEquals(0, p);
        }
        // A wall in an otherwise empty block does not distinguish it either.
        PairPatterns.patterns(0, 0, 1L << 10, blocks, pat);
        for (int p : pat) {
            assertEquals(0, p);
        }
        // One own piece in the corner square is seen by the placements whose first block covers it.
        PairPatterns.patterns(1L, 0, 0, blocks, pat);
        int seen = 0;
        for (int p : pat) {
            if (p != 0) {
                seen++;
                assertTrue((p & 255) == 1 || (p >> 8) == 1, "pattern " + p);
            }
        }
        assertTrue(seen > 0);
    }

    @Test
    void evaluatorReadsTempoAndClassWeights() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bytes)) {
            o.writeInt(PairEvaluator.MAGIC);
            o.writeInt(PairEvaluator.VERSION);
            o.writeInt(PairEvaluator.WEIGHT_SCALE);
            o.writeShort(2 * PairEvaluator.WEIGHT_SCALE); // tempo of two units
            o.writeInt(0);
        }
        Evaluator zero = pos -> 0;
        PairEvaluator e = PairEvaluator.load(new ByteArrayInputStream(bytes.toByteArray()), zero, 1.0);
        assertEquals(2, e.evaluate(Position.startPos()));

        // One class: the single own piece in the corner square a1, as seen by placement 0 (first block at a1).
        ByteArrayOutputStream withClass = new ByteArrayOutputStream();
        int[] blocks = new int[49];
        int[] pat = new int[PairPatterns.PLACEMENTS];
        PairPatterns.patterns(1L, 0, 0, blocks, pat);
        int key = PairPatterns.canonicalKey(0, pat[0]);
        try (DataOutputStream o = new DataOutputStream(withClass)) {
            o.writeInt(PairEvaluator.MAGIC);
            o.writeInt(PairEvaluator.VERSION);
            o.writeInt(PairEvaluator.WEIGHT_SCALE);
            o.writeShort(0);
            o.writeInt(1);
            o.writeInt(key);
            o.writeShort(5 * PairEvaluator.WEIGHT_SCALE);
        }
        PairEvaluator e2 = PairEvaluator.load(new ByteArrayInputStream(withClass.toByteArray()), zero, 1.0);
        // The start position has stones on a7, g7, a1 and g1; side to move x owns a7 and g1: symmetric images of the class.
        assertTrue(e2.evaluate(Position.startPos()) > 0);
    }
}
