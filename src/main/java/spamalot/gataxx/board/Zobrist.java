package spamalot.gataxx.board;

import java.util.SplittableRandom;

/** Zobrist hash keys. Fixed seed, so hashes are reproducible between runs. */
final class Zobrist {
    /** {@code PIECE[side][square]}. */
    private static final long[][] PIECE = new long[2][Bitboards.SQUARES];
    private static final long[] WALL = new long[Bitboards.SQUARES];
    static final long SIDE_TO_MOVE;

    static {
        SplittableRandom rnd = new SplittableRandom(0x6174617878L);
        for (int side = 0; side < 2; side++) {
            for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
                PIECE[side][sq] = rnd.nextLong();
            }
        }
        for (int sq = 0; sq < Bitboards.SQUARES; sq++) {
            WALL[sq] = rnd.nextLong();
        }
        SIDE_TO_MOVE = rnd.nextLong();
    }

    private Zobrist() {}

    static long piece(int side, int sq) {
        return PIECE[side][sq];
    }

    /** Key that flips a square between the two sides, for converted pieces. */
    static long flip(int sq) {
        return PIECE[0][sq] ^ PIECE[1][sq];
    }

    /** Hash of a position computed from scratch. Equal to the incrementally maintained hash. */
    static long compute(long x, long o, long walls, int sideToMove) {
        long h = sideToMove == Position.O ? SIDE_TO_MOVE : 0;
        for (long b = x; b != 0; b &= b - 1) {
            h ^= PIECE[Position.X][Long.numberOfTrailingZeros(b)];
        }
        for (long b = o; b != 0; b &= b - 1) {
            h ^= PIECE[Position.O][Long.numberOfTrailingZeros(b)];
        }
        for (long b = walls; b != 0; b &= b - 1) {
            h ^= WALL[Long.numberOfTrailingZeros(b)];
        }
        return h;
    }
}
