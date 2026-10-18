package spamalot.gataxx.eval;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import spamalot.gataxx.board.Position;

/**
 * Adds the pair-pattern correction ({@link PairPatterns}) to another evaluation: {@code base + blend * (sum of weights)}.
 * The weights were learned as a residual of that base evaluation by {@code tools.PairFit}. Stateless apart from its
 * thread-local scratch space, so one instance can serve every search thread.
 *
 * <p>File format (big endian): magic {@code "PAIR"}, version (int, 2), steps per unit (int), tempo bonus (short, in steps),
 * number of stored classes (int), then per class its canonical key (int) and weight (short, in steps); from version 3 on,
 * then the number of stored mobility entries (int) and per entry its index (short) and weight (short). Version 1 has no steps
 * field and 8 steps per unit; versions below 3 have no mobility table. Weights of
 * patterns that are symmetric images of each other are equal, so only one representative of each class is stored.
 */
public final class PairEvaluator implements Evaluator {
    /** Stored weights are in this many steps per evaluation unit (written by {@code PairFit}; the file header has its own). */
    public static final int WEIGHT_SCALE = 32;
    public static final int MAGIC = 0x50414952;
    public static final int VERSION = 3;

    private final Evaluator base;
    private final short[] weights;
    private final short[] mobility;
    private final int tempo;
    private final int scale;
    private final double blend;
    private int margin;
    private boolean[] active = PairPatterns.activeMask("hvda");
    private boolean useH = true;
    private boolean useV = true;
    private boolean useD = true;
    private boolean useA = true;

    private final ThreadLocal<int[]> blocks = ThreadLocal.withInitial(() -> new int[49]);
    private final ThreadLocal<int[]> patterns = ThreadLocal.withInitial(() -> new int[PairPatterns.PLACEMENTS]);

    private PairEvaluator(Evaluator base, short[] weights, short[] mobility, int tempo, int scale, double blend) {
        if (blend < 0) {
            throw new IllegalArgumentException("blend must not be negative");
        }
        this.base = base;
        this.weights = weights;
        this.mobility = mobility;
        this.tempo = tempo;
        this.scale = scale;
        this.blend = blend;
    }

    /** Resource holding the weights that ship with the engine. */
    static final String BUILT_IN = "pair-patterns.bin";

    /** Loads the weights that ship inside the jar. */
    public static PairEvaluator builtIn(Evaluator base, double blend) throws IOException {
        try (InputStream in = PairEvaluator.class.getResourceAsStream(BUILT_IN)) {
            if (in == null) {
                throw new IOException("built-in pair-pattern weights are missing from the jar");
            }
            return load(in, base, blend);
        }
    }

    /** Loads a weight file written by {@code PairFit}. */
    public static PairEvaluator load(InputStream in, Evaluator base, double blend) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            if (d.readInt() != MAGIC) {
                throw new IOException("not a pair-pattern weight file");
            }
            int version = d.readInt();
            if (version < 1 || version > VERSION) {
                throw new IOException("unsupported pair-pattern file version " + version);
            }
            int scale = version == 1 ? 8 : d.readInt();
            int tempo = d.readShort();
            int classes = d.readInt();
            short[] w = new short[PairPatterns.PLACEMENTS * PairPatterns.INDEX_SIZE];
            for (int i = 0; i < classes; i++) {
                int key = d.readInt();
                short weight = d.readShort();
                int placement = key >>> 16;
                int pattern = key & 0xFFFF;
                for (int image : PairPatterns.imageKeys(placement, pattern)) {
                    w[image] = weight;
                }
            }
            short[] mobility = new short[PairPatterns.MOBILITY_SIZE];
            if (version >= 3) {
                int entries = d.readInt();
                for (int i = 0; i < entries; i++) {
                    int index = d.readShort() & 0xFFFF;
                    mobility[index] = d.readShort();
                }
            }
            return new PairEvaluator(base, w, mobility, tempo, scale, blend);
        }
    }

    /** Skip the correction when the base score is at least {@code margin} outside the search window; 0 never skips. */
    public PairEvaluator withMargin(int margin) {
        this.margin = margin;
        return this;
    }

    /**
     * Sums only the placements of the given kinds ({@code h}, {@code v}, {@code d}, {@code a}: see {@link PairPatterns#type}), which
     * makes the evaluation faster by one table lookup per dropped placement. The weight table must have been fitted for the same
     * kinds ({@code PairFit --placements}); the default {@code hvda} uses all 80.
     */
    public PairEvaluator withPlacements(String types) {
        this.active = PairPatterns.activeMask(types);
        this.useH = types.indexOf('h') >= 0;
        this.useV = types.indexOf('v') >= 0;
        this.useD = types.indexOf('d') >= 0;
        this.useA = types.indexOf('a') >= 0;
        return this;
    }

    @Override
    public int evaluate(Position pos, int alpha, int beta) {
        int b = base.evaluate(pos, alpha, beta);
        if (margin > 0 && (b - margin >= beta || b + margin <= alpha)) {
            return b;
        }
        return b + correction(pos);
    }

    @Override
    public int evaluate(Position pos) {
        return base.evaluate(pos) + correction(pos);
    }

    private int correction(Position pos) {
        int side = pos.sideToMove();
        long mine = pos.pieces(side);
        long theirs = pos.pieces(1 - side);
        long walls = pos.walls();
        int sum = tempo + (walls == 0 ? patternSumLanes(mine, theirs) : patternSumBlocks(mine, theirs, walls));
        sum += mobility[PairPatterns.mobilityIndex(mine, theirs, walls)]
                - mobility[PairPatterns.mobilityIndex(theirs, mine, walls)];
        return (int) Math.round(blend * sum / scale);
    }

    /** Sum of the 80 placement weights, found through the block indices of {@link PairPatterns} (the general case). */
    int patternSumBlocks(long mine, long theirs, long walls) {
        int[] p = patterns.get();
        PairPatterns.patterns(mine, theirs, walls, blocks.get(), p);
        int sum = 0;
        for (int i = 0; i < PairPatterns.PLACEMENTS; i++) {
            if (active[i]) {
                sum += weights[i << 16 | p[i]];
            }
        }
        return sum;
    }

    /**
     * The same sum for a board without walls. The 2x2 block at file {@code x} of the rank pair {@code y, y+1} is byte
     * {@code x} of {@code blocks(y)}: every square is a base-four digit (1 own, 2 enemy), and one rank's digits are added
     * up in byte lanes with the next rank's, so that all six blocks of a rank pair come out of a few additions.
     */
    int patternSumLanes(long mine, long theirs) {
        long d0 = Lanes.rank(mine, 0) + 2 * Lanes.rank(theirs, 0);
        long d1 = Lanes.rank(mine, 1) + 2 * Lanes.rank(theirs, 1);
        long d2 = Lanes.rank(mine, 2) + 2 * Lanes.rank(theirs, 2);
        long d3 = Lanes.rank(mine, 3) + 2 * Lanes.rank(theirs, 3);
        long d4 = Lanes.rank(mine, 4) + 2 * Lanes.rank(theirs, 4);
        long d5 = Lanes.rank(mine, 5) + 2 * Lanes.rank(theirs, 5);
        long d6 = Lanes.rank(mine, 6) + 2 * Lanes.rank(theirs, 6);
        // Lane j of pairs(d) holds square j and 4 times square j+1 of the rank.
        long a0 = d0 + (d0 >>> 8 << 2);
        long a1 = d1 + (d1 >>> 8 << 2);
        long a2 = d2 + (d2 >>> 8 << 2);
        long a3 = d3 + (d3 >>> 8 << 2);
        long a4 = d4 + (d4 >>> 8 << 2);
        long a5 = d5 + (d5 >>> 8 << 2);
        long a6 = d6 + (d6 >>> 8 << 2);
        long b0 = a0 + (a1 << 4);
        long b1 = a1 + (a2 << 4);
        long b2 = a2 + (a3 << 4);
        long b3 = a3 + (a4 << 4);
        long b4 = a4 + (a5 << 4);
        long b5 = a5 + (a6 << 4);
        int sum = 0;
        if (useH) {
            sum += horizontal(b0, 0) + horizontal(b1, 4) + horizontal(b2, 8) + horizontal(b3, 12) + horizontal(b4, 16)
                    + horizontal(b5, 20);
        }
        if (useV) {
            sum += vertical(b0, b2, 24) + vertical(b1, b3, 30) + vertical(b2, b4, 36) + vertical(b3, b5, 42);
        }
        if (useD) {
            sum += diagonal(b0, b2, 48) + diagonal(b1, b3, 52) + diagonal(b2, b4, 56) + diagonal(b3, b5, 60);
        }
        if (useA) {
            sum += diagonal(b2, b0, 64) + diagonal(b3, b1, 68) + diagonal(b4, b2, 72) + diagonal(b5, b3, 76);
        }
        return sum;
    }

    /** Placements of two blocks two files apart on one rank pair: the first placement is {@code first}. */
    private int horizontal(long b, int first) {
        int sum = 0;
        for (int x = 0; x < 4; x++) {
            sum += weights[(first + x) << 16 | (int) (b >>> (8 * x) & 0xFF) | (int) (b >>> (8 * x + 8) & 0xFF00)];
        }
        return sum;
    }

    /** Placements of a block above a block two ranks down. */
    private int vertical(long above, long below, int first) {
        int sum = 0;
        for (int x = 0; x < 6; x++) {
            sum += weights[(first + x) << 16 | (int) (above >>> (8 * x) & 0xFF) | (int) (below >>> (8 * x) << 8 & 0xFF00)];
        }
        return sum;
    }

    /** Placements of blocks touching at a corner: the first block is byte {@code x} of {@code first}, the second byte {@code x + 2} of {@code second}. */
    private int diagonal(long first, long second, int firstPlacement) {
        int sum = 0;
        for (int x = 0; x < 4; x++) {
            sum += weights[(firstPlacement + x) << 16 | (int) (first >>> (8 * x) & 0xFF)
                    | (int) (second >>> (8 * x + 8) & 0xFF00)];
        }
        return sum;
    }
}
