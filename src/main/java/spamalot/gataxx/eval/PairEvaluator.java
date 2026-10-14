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
        int[] p = patterns.get();
        PairPatterns.patterns(pos.pieces(side), pos.pieces(1 - side), pos.walls(), blocks.get(), p);
        int sum = tempo;
        for (int i = 0; i < PairPatterns.PLACEMENTS; i++) {
            sum += weights[i << 16 | p[i]];
        }
        long mine = pos.pieces(side);
        long theirs = pos.pieces(1 - side);
        sum += mobility[PairPatterns.mobilityIndex(mine, theirs, pos.walls())]
                - mobility[PairPatterns.mobilityIndex(theirs, mine, pos.walls())];
        return (int) Math.round(blend * sum / scale);
    }
}
