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
