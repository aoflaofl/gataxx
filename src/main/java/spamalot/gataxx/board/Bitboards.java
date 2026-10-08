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
        }
    }

    private Bitboards() {}

    /** The set of squares at Chebyshev distance exactly 2 from {@code sq}. */
    public static long ring2(int sq) {
        return RING2[sq];
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
