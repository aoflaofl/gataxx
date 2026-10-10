package spamalot.gataxx.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitboardsTest {
    @Test
    void atLeastNeighboursMatchesCountingSquareBySquare() {
        Random rnd = new Random(12345);
        for (int trial = 0; trial < 3000; trial++) {
            long set = rnd.nextLong() & Bitboards.ALL;
            if (trial % 3 == 0) {
                set &= rnd.nextLong(); // sparse boards
            }
            if (trial % 7 == 0) {
                set = Bitboards.ALL & ~(1L << rnd.nextInt(49)); // nearly full: counts of 8
            }
            for (int k = 0; k <= 8; k++) {
                long expected = 0;
                for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
                    if (k == 0 || Long.bitCount(Bitboards.neighbours(sq) & set) >= k) {
                        expected |= 1L << sq;
                    }
                }
                assertEquals(expected, Bitboards.atLeastNeighbours(set, k), "set " + Long.toHexString(set) + " k " + k);
            }
        }
    }

    @Test
    void adjacentPairsWithinIsHalfTheOrderedCount() {
        Random rnd = new Random(99);
        for (int trial = 0; trial < 2000; trial++) {
            long a = rnd.nextLong() & Bitboards.ALL;
            assertEquals(Bitboards.adjacentPairs(a, a), 2 * Bitboards.adjacentPairsWithin(a));
        }
    }
}
