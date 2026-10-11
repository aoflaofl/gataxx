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
 * <p>File format (big endian): magic {@code "PAIR"}, version (int), tempo bonus (short, 1/{@link #WEIGHT_SCALE} unit), number of
 * stored classes (int), then per class its canonical key (int) and weight (short, 1/{@link #WEIGHT_SCALE} unit). Weights of
 * patterns that are symmetric images of each other are equal, so only one representative of each class is stored.
 */
public final class PairEvaluator implements Evaluator {
    /** Stored weights are in this many steps per evaluation unit. */
    public static final int WEIGHT_SCALE = 8;
    private static final int MAGIC = 0x50414952;
    private static final int VERSION = 1;

    private final Evaluator base;
    private final short[] weights;
    private final int tempo;
    private final double blend;
    private int margin;

    private final ThreadLocal<int[]> blocks = ThreadLocal.withInitial(() -> new int[49]);
    private final ThreadLocal<int[]> patterns = ThreadLocal.withInitial(() -> new int[PairPatterns.PLACEMENTS]);

    private PairEvaluator(Evaluator base, short[] weights, int tempo, double blend) {
        if (blend < 0) {
            throw new IllegalArgumentException("blend must not be negative");
        }
        this.base = base;
        this.weights = weights;
        this.tempo = tempo;
        this.blend = blend;
    }

    /** Loads a weight file written by {@code PairFit}. */
    public static PairEvaluator load(InputStream in, Evaluator base, double blend) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            if (d.readInt() != MAGIC || d.readInt() != VERSION) {
                throw new IOException("not a pair-pattern weight file");
            }
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
            return new PairEvaluator(base, w, tempo, blend);
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
        return (int) Math.round(blend * sum / WEIGHT_SCALE);
    }
}
