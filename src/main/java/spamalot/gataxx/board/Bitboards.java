package spamalot.gataxx.board;

/**
 * Bitboard constants and helpers for the 7x7 Ataxx board.
 *
 * <p>Square index = {@code rank * 7 + file}, with file 0..6 = a..g and rank 0..6 = 1..7, so bit 0
 * is a1 and bit 48 is g7.
 */
public final class Bitboards {
    public static final int SIZE = 7;
    public static final int SQUARES = SIZE * SIZE;

    /** All 49 on-board squares. */
    public static final long ALL = (1L << SQUARES) - 1;

    private static final long FILE_A = 0x0040810204081L; // bits 0,7,14,...,42
    private static final long FILE_G = FILE_A << 6;
    private static final long NOT_FILE_A = ALL & ~FILE_A;
    private static final long NOT_FILE_G = ALL & ~FILE_G;

    /** For each square, the squares at Chebyshev distance exactly 2 (the jump targets). */
    private static final long[] RING2 = new long[SQUARES];

    /** For each square, the squares at Chebyshev distance exactly 1 (not the square itself). */
    private static final long[] NEIGHBOURS = new long[SQUARES];

    static {
        for (int sq = 0; sq < SQUARES; sq++) {
            int f = sq % SIZE;
            int r = sq / SIZE;
            long ring = 0;
            for (int df = -2; df <= 2; df++) {
                for (int dr = -2; dr <= 2; dr++) {
                    if (Math.max(Math.abs(df), Math.abs(dr)) != 2) {
                        continue;
                    }
                    int nf = f + df;
                    int nr = r + dr;
                    if (nf >= 0 && nf < SIZE && nr >= 0 && nr < SIZE) {
                        ring |= 1L << (nr * SIZE + nf);
                    }
                }
            }
            RING2[sq] = ring;

            long near = 0;
            for (int df = -1; df <= 1; df++) {
                for (int dr = -1; dr <= 1; dr++) {
                    int nf = f + df;
                    int nr = r + dr;
                    if ((df != 0 || dr != 0) && nf >= 0 && nf < SIZE && nr >= 0 && nr < SIZE) {
                        near |= 1L << (nr * SIZE + nf);
                    }
                }
            }
            NEIGHBOURS[sq] = near;
        }
    }

    private Bitboards() {}

    /** The set of squares adjacent to {@code sq}, diagonals included. */
    public static long neighbours(int sq) {
        return NEIGHBOURS[sq];
    }

    /** The set of squares at Chebyshev distance exactly 2 from {@code sq}. */
    public static long ring2(int sq) {
        return RING2[sq];
    }

    /** Moves every square one rank up (towards rank 7); squares leaving the board are dropped. */
    public static long shiftNorth(long b) {
        return (b << SIZE) & ALL;
    }

    public static long shiftSouth(long b) {
        return b >>> SIZE;
    }

    /** Moves every square one file towards g, without wrapping onto the next rank. */
    public static long shiftEast(long b) {
        return (b << 1) & NOT_FILE_A;
    }

    public static long shiftWest(long b) {
        return (b >>> 1) & NOT_FILE_G;
    }

    /**
     * Number of (square in {@code a}, adjacent square in {@code b}) pairs, counting all eight
     * directions, i.e. how many neighbour relations hold between the two sets.
     */
    public static int adjacentPairs(long a, long b) {
        long n = shiftNorth(b);
        long s = shiftSouth(b);
        long e = shiftEast(b);
        long w = shiftWest(b);
        return Long.bitCount(a & n) + Long.bitCount(a & s) + Long.bitCount(a & e) + Long.bitCount(a & w)
                + Long.bitCount(a & shiftNorth(e)) + Long.bitCount(a & shiftNorth(w))
                + Long.bitCount(a & shiftSouth(e)) + Long.bitCount(a & shiftSouth(w));
    }

    /**
     * The squares that have at least {@code k} of their (up to eight) neighbours in {@code set}. Counts all squares at once
     * with bit-sliced adders instead of one population count per square.
     */
    public static long atLeastNeighbours(long set, int k) {
        if (k <= 0) {
            return ALL;
        }
        long up = shiftNorth(set);
        long down = shiftSouth(set);
        long right = shiftEast(set);
        long left = shiftWest(set);
        long x0 = up;
        long x1 = down;
        long x2 = right;
        long x3 = left;
        long x4 = shiftNorth(right);
        long x5 = shiftNorth(left);
        long x6 = shiftSouth(right);
        long x7 = shiftSouth(left);
        // Eight one-bit inputs per square -> a four-bit count (b3 b2 b1 b0).
        long sa = x0 ^ x1 ^ x2;
        long ca = (x0 & x1) | (x2 & (x0 ^ x1));
        long sb = x3 ^ x4 ^ x5;
        long cb = (x3 & x4) | (x5 & (x3 ^ x4));
        long sc = x6 ^ x7;
        long cc = x6 & x7;
        long b0 = sa ^ sb ^ sc;
        long c1 = (sa & sb) | (sc & (sa ^ sb));
        long t = ca ^ cb ^ cc;
        long u = (ca & cb) | (cc & (ca ^ cb));
        long b1 = t ^ c1;
        long v = t & c1;
        long b2 = u ^ v;
        long b3 = u & v;
        return switch (k) {
            case 1 -> b0 | b1 | b2 | b3;
            case 2 -> b1 | b2 | b3;
            case 3 -> (b1 & b0) | b2 | b3;
            case 4 -> b2 | b3;
            case 5 -> (b2 & (b1 | b0)) | b3;
            case 6 -> (b2 & b1) | b3;
            case 7 -> (b2 & b1 & b0) | b3;
            default -> b3;
        };
    }

    /**
     * Number of unordered pairs of adjacent squares (diagonals included) that are both in {@code a}. Half of
     * {@code adjacentPairs(a, a)}, using four directions instead of eight.
     */
    public static int adjacentPairsWithin(long a) {
        long e = shiftEast(a);
        long w = shiftWest(a);
        return Long.bitCount(a & shiftNorth(a)) + Long.bitCount(a & e) + Long.bitCount(a & shiftNorth(e))
                + Long.bitCount(a & shiftNorth(w));
    }

    /** Every square in {@code b} plus all squares adjacent (incl. diagonals) to them. */
    public static long expand1(long b) {
        long row = b | ((b << 1) & NOT_FILE_A) | ((b >>> 1) & NOT_FILE_G);
        return (row | (row << SIZE) | (row >>> SIZE)) & ALL;
    }

    /** Every square in {@code b} plus all squares within Chebyshev distance 2 of them. */
    public static long expand2(long b) {
        return expand1(expand1(b));
    }
}
