package spamalot.gataxx.eval;

/**
 * Helpers for computing a whole rank of the board at once: a rank's seven squares become seven byte lanes of a {@code long}
 * (lane {@code j} holds the value of file {@code j}), so that sums over neighbouring squares are plain additions.
 */
final class Lanes {
    /** Indexed by the seven bits of a rank: bit {@code j} becomes the value 1 in lane {@code j}. */
    private static final long[] ROW = new long[128];

    static {
        for (int r = 0; r < 128; r++) {
            long v = 0;
            for (int j = 0; j < 7; j++) {
                if ((r >> j & 1) != 0) {
                    v |= 1L << (8 * j);
                }
            }
            ROW[r] = v;
        }
    }

    private Lanes() {}

    /** The pieces of {@code board} on rank {@code rank} (0..6), one per lane. */
    static long rank(long board, int rank) {
        return ROW[(int) (board >>> (7 * rank)) & 0x7F];
    }
}
