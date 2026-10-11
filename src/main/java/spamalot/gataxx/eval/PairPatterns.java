package spamalot.gataxx.eval;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import spamalot.gataxx.board.Bitboards;

/**
 * Feature definitions of the pair-pattern evaluation: the exact contents of pairs of 2x2 blocks of the board.
 *
 * <p>Every square is empty (0), the side to move's (1), the opponent's (2) or a wall (3); a 2x2 block is the base-four number of
 * its four squares in the order (x, y), (x+1, y), (x, y+1), (x+1, y+1), with a block that holds no piece at all counted as
 * 0. A <em>placement</em> is a pair of blocks: two blocks side by side (a 4x2 rectangle), one above the other (2x4), or two
 * blocks touching at a corner in either diagonal direction, 80 placements on the 7x7 board. A placement's pattern is
 * {@code first block + 256 * second block}, one of 65,536, and each (placement, pattern) has its own weight.
 *
 * <p>The board has eight symmetries and the game is invariant under them, so the weights of patterns that are images of each
 * other are tied. {@link #canonicalKey} names the representative of a pattern's class, and {@link #imageKeys} lists the whole
 * class.
 */
public final class PairPatterns {
    public static final int PLACEMENTS = 80;
    public static final int INDEX_SIZE = 1 << 16;
    /** Number of 2x2 blocks that fit on the board in each direction. */
    private static final int ORIGINS = 6;

    /** For each placement the eight squares: the four of the first block, then the four of the second. */
    private static final int[][] SQUARES = new int[PLACEMENTS][8];
    private static final int[] FIRST = new int[PLACEMENTS];
    private static final int[] SECOND = new int[PLACEMENTS];
    /** Block index for (own pieces, enemy pieces) over a block's four squares when there are no walls. */
    private static final int[][] DIGITS = new int[16][16];
    private static final Map<Long, Integer> BY_FOOTPRINT = new HashMap<>();

    static {
        int p = 0;
        for (int y = 0; y < ORIGINS; y++) {
            for (int x = 0; x <= 3; x++) {
                p = add(p, x, y, x + 2, y);
            }
        }
        for (int y = 0; y <= 3; y++) {
            for (int x = 0; x < ORIGINS; x++) {
                p = add(p, x, y, x, y + 2);
            }
        }
        for (int y = 0; y <= 3; y++) {
            for (int x = 0; x <= 3; x++) {
                p = add(p, x, y, x + 2, y + 2);
            }
        }
        for (int y = 0; y <= 3; y++) {
            for (int x = 0; x <= 3; x++) {
                p = add(p, x, y + 2, x + 2, y);
            }
        }
        if (p != PLACEMENTS) {
            throw new IllegalStateException("placements: " + p);
        }
        for (int m = 0; m < 16; m++) {
            for (int t = 0; t < 16; t++) {
                int idx = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = (m >> i & 1) != 0 ? 1 : (t >> i & 1) != 0 ? 2 : 0;
                    idx |= digit << (2 * i);
                }
                DIGITS[m][t] = (m | t) == 0 || (m & t) != 0 ? 0 : idx;
            }
        }
    }

    private PairPatterns() {}

    private static int add(int p, int ax, int ay, int bx, int by) {
        int a = ay * Bitboards.SIZE + ax;
        int b = by * Bitboards.SIZE + bx;
        FIRST[p] = a;
        SECOND[p] = b;
        int[] sq = {a, a + 1, a + Bitboards.SIZE, a + Bitboards.SIZE + 1, b, b + 1, b + Bitboards.SIZE, b + Bitboards.SIZE + 1};
        SQUARES[p] = sq;
        long mask = 0;
        for (int s : sq) {
            mask |= 1L << s;
        }
        if (BY_FOOTPRINT.put(mask, p) != null) {
            throw new IllegalStateException("two placements with the same footprint");
        }
        return p + 1;
    }

    /** Index (0..255) of the 2x2 block whose first square is {@code origin}; 0 for a block without pieces. */
    private static int blockIndex(long mine, long theirs, long walls, int origin) {
        int shift = origin;
        int m = (int) (mine >>> shift & 3) | (int) (mine >>> (shift + Bitboards.SIZE) & 3) << 2;
        int t = (int) (theirs >>> shift & 3) | (int) (theirs >>> (shift + Bitboards.SIZE) & 3) << 2;
        if ((m | t) == 0) {
            return 0;
        }
        if (walls == 0) {
            return DIGITS[m][t];
        }
        int w = (int) (walls >>> shift & 3) | (int) (walls >>> (shift + Bitboards.SIZE) & 3) << 2;
        int idx = DIGITS[m][t];
        for (int i = 0; i < 4; i++) {
            if ((w >> i & 1) != 0) {
                idx |= 3 << (2 * i);
            }
        }
        return idx;
    }

    /**
     * Fills {@code out[0..79]} with the pattern index of every placement for the side whose pieces are {@code mine}.
     *
     * @param blocks scratch space of at least 49 ints (block indices by origin square)
     */
    public static void patterns(long mine, long theirs, long walls, int[] blocks, int[] out) {
        for (int y = 0; y < ORIGINS; y++) {
            for (int x = 0; x < ORIGINS; x++) {
                int s = y * Bitboards.SIZE + x;
                blocks[s] = blockIndex(mine, theirs, walls, s);
            }
        }
        for (int p = 0; p < PLACEMENTS; p++) {
            out[p] = blocks[FIRST[p]] | blocks[SECOND[p]] << 8;
        }
    }

    /** The key {@code placement << 16 | pattern} of every symmetric image of a pattern (possibly with repeats). */
    public static int[] imageKeys(int placement, int pattern) {
        int[] states = decode(pattern);
        int[] keys = new int[8];
        for (int t = 0; t < 8; t++) {
            long footprint = 0;
            int[] mapped = new int[8];
            for (int i = 0; i < 8; i++) {
                mapped[i] = transform(SQUARES[placement][i], t);
                footprint |= 1L << mapped[i];
            }
            int image = BY_FOOTPRINT.get(footprint);
            int[] stateAt = new int[49];
            Arrays.fill(stateAt, -1);
            for (int i = 0; i < 8; i++) {
                stateAt[mapped[i]] = states[i];
            }
            keys[t] = image << 16 | encode(image, stateAt);
        }
        return keys;
    }

    /** The smallest key among the symmetric images, the representative of the tie class. */
    public static int canonicalKey(int placement, int pattern) {
        int best = Integer.MAX_VALUE;
        for (int k : imageKeys(placement, pattern)) {
            best = Math.min(best, k);
        }
        return best;
    }

    /** The eight squares of a placement: the first block's four, then the second block's four. */
    public static int[] squares(int placement) {
        return SQUARES[placement].clone();
    }

    /** Applies one of the eight symmetries of the board to a square (0 is the identity). */
    public static int transform(int square, int t) {
        int n = Bitboards.SIZE - 1;
        int x = square % Bitboards.SIZE;
        int y = square / Bitboards.SIZE;
        int nx;
        int ny;
        switch (t) {
            case 0 -> { nx = x; ny = y; }
            case 1 -> { nx = n - y; ny = x; }
            case 2 -> { nx = n - x; ny = n - y; }
            case 3 -> { nx = y; ny = n - x; }
            case 4 -> { nx = n - x; ny = y; }
            case 5 -> { nx = x; ny = n - y; }
            case 6 -> { nx = y; ny = x; }
            default -> { nx = n - y; ny = n - x; }
        }
        return ny * Bitboards.SIZE + nx;
    }

    /** The states of a pattern's eight squares in {@link #squares} order; a pieceless block decodes as all empty. */
    private static int[] decode(int pattern) {
        int[] states = new int[8];
        for (int b = 0; b < 2; b++) {
            int idx = pattern >> (8 * b) & 255;
            for (int i = 0; i < 4; i++) {
                states[4 * b + i] = idx >> (2 * i) & 3;
            }
        }
        return states;
    }

    private static int encode(int placement, int[] stateAt) {
        int pattern = 0;
        for (int b = 0; b < 2; b++) {
            int idx = 0;
            boolean pieces = false;
            for (int i = 0; i < 4; i++) {
                int s = stateAt[SQUARES[placement][4 * b + i]];
                idx |= s << (2 * i);
                pieces |= s == 1 || s == 2;
            }
            pattern |= (pieces ? idx : 0) << (8 * b);
        }
        return pattern;
    }
}
