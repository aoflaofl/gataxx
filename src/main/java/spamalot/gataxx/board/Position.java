package spamalot.gataxx.board;

import java.util.Arrays;

/**
 * An immutable Ataxx position. {@link #makeMove(int)} returns a new position (copy-make).
 *
 * <p>Rules implemented: clone to an adjacent empty square, jump to an empty square at distance 2;
 * all enemy pieces adjacent to the destination are converted. A side with no legal move passes
 * (only while the game is still going). The game ends when a side has no pieces, neither side can
 * move, or 100 half-moves pass without a clone.
 */
public final class Position {
    public static final int X = 0;
    public static final int O = 1;

    /** Upper bound on the number of moves in any position; size move buffers at least this big. */
    public static final int MAX_MOVES = 512;

    public static final String START_FEN = "x5o/7/7/7/7/7/o5x x 0 1";

    /** Layout of the ordering keys made by {@link #generateScoredCaptureMoves}: score above, reversed index in the low bits. */
    public static final int KEY_SHIFT = 10;
    public static final int KEY_INDEX_MASK = (1 << KEY_SHIFT) - 1;

    /** Half-move clock value at which the game is drawn-out and ends by count. */
    public static final int HALFMOVE_LIMIT = 100;

    private final long x;
    private final long o;
    private final long walls;
    private final int sideToMove;
    private final int halfmoveClock;
    private final int fullmoveNumber;
    private final long hash;

    private Position(long x, long o, long walls, int sideToMove, int halfmoveClock, int fullmoveNumber, long hash) {
        this.hash = hash;
        this.x = x;
        this.o = o;
        this.walls = walls;
        this.sideToMove = sideToMove;
        this.halfmoveClock = halfmoveClock;
        this.fullmoveNumber = fullmoveNumber;
    }

    public static Position startPos() {
        return fromFen(START_FEN);
    }

    /** Parses a FEN such as {@code x5o/7/7/7/7/7/o5x x 0 1}. Clocks are optional. */
    public static Position fromFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Bad FEN: " + fen);
        }
        String[] ranks = parts[0].split("/", -1);
        if (ranks.length != Bitboards.SIZE) {
            throw new IllegalArgumentException("FEN needs 7 ranks: " + fen);
        }
        long x = 0;
        long o = 0;
        long walls = 0;
        for (int i = 0; i < Bitboards.SIZE; i++) {
            int rank = Bitboards.SIZE - 1 - i;
            int file = 0;
            for (char c : ranks[i].toCharArray()) {
                if (c >= '1' && c <= '7') {
                    file += c - '0';
                    continue;
                }
                if (file >= Bitboards.SIZE) {
                    throw new IllegalArgumentException("Rank too long in FEN: " + fen);
                }
                long bit = 1L << (rank * Bitboards.SIZE + file);
                switch (c) {
                    case 'x' -> x |= bit;
                    case 'o' -> o |= bit;
                    case '-' -> walls |= bit;
                    default -> throw new IllegalArgumentException("Bad FEN character '" + c + "': " + fen);
                }
                file++;
            }
            if (file != Bitboards.SIZE) {
                throw new IllegalArgumentException("Rank does not have 7 squares in FEN: " + fen);
            }
        }
        int side;
        switch (parts[1]) {
            case "x" -> side = X;
            case "o" -> side = O;
            default -> throw new IllegalArgumentException("Bad side to move in FEN: " + fen);
        }
        try {
            int halfmove = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            int fullmove = parts.length > 3 ? Integer.parseInt(parts[3]) : 1;
            return new Position(x, o, walls, side, halfmove, fullmove, Zobrist.compute(x, o, walls, side));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad clock in FEN: " + fen, e);
        }
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int rank = Bitboards.SIZE - 1; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < Bitboards.SIZE; file++) {
                long bit = 1L << (rank * Bitboards.SIZE + file);
                char c = (x & bit) != 0 ? 'x' : (o & bit) != 0 ? 'o' : (walls & bit) != 0 ? '-' : 0;
                if (c == 0) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(c);
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        return sb.append(sideToMove == X ? " x " : " o ")
                .append(halfmoveClock)
                .append(' ')
                .append(fullmoveNumber)
                .toString();
    }

    /** Zobrist hash of the board and side to move (not the move clocks). */
    public long hash() {
        return hash;
    }

    public int sideToMove() {
        return sideToMove;
    }

    public long pieces(int side) {
        return side == X ? x : o;
    }

    public long walls() {
        return walls;
    }

    public long empty() {
        return Bitboards.ALL & ~(x | o | walls);
    }

    public int count(int side) {
        return Long.bitCount(pieces(side));
    }

    public int halfmoveClock() {
        return halfmoveClock;
    }

    public int fullmoveNumber() {
        return fullmoveNumber;
    }

    /** True if {@code side} has at least one real (non-pass) move. */
    public boolean hasMove(int side) {
        return (Bitboards.expand2(pieces(side)) & empty()) != 0;
    }

    public boolean isGameOver() {
        return x == 0 || o == 0 || halfmoveClock >= HALFMOVE_LIMIT || (!hasMove(X) && !hasMove(O));
    }

    public Outcome outcome() {
        if (!isGameOver()) {
            return Outcome.ONGOING;
        }
        return outcomeByCount();
    }

    /** Winner by piece count alone, ignoring whether the game is actually over. */
    public Outcome outcomeByCount() {
        int cx = count(X);
        int co = count(O);
        return cx > co ? Outcome.X_WINS : co > cx ? Outcome.O_WINS : Outcome.DRAW;
    }

    /**
     * Fills {@code out} with the legal moves (clones first, then jumps) and returns the count.
     * Returns a lone {@link Move#PASS} if the side to move is stuck but the game continues, and 0 if
     * the game is over. {@code out} must hold at least {@link #MAX_MOVES} entries.
     */
    public int generateMoves(int[] out) {
        if (isGameOver()) {
            return 0;
        }
        long mine = pieces(sideToMove);
        long empty = empty();
        int n = 0;

        long clones = Bitboards.expand1(mine) & empty;
        for (long t = clones; t != 0; t &= t - 1) {
            out[n++] = Move.clone(Long.numberOfTrailingZeros(t));
        }
        for (long p = mine; p != 0; p &= p - 1) {
            int from = Long.numberOfTrailingZeros(p);
            for (long t = Bitboards.ring2(from) & empty; t != 0; t &= t - 1) {
                out[n++] = Move.jump(from, Long.numberOfTrailingZeros(t));
            }
        }
        if (n == 0) {
            out[n++] = Move.PASS;
        }
        return n;
    }

    /**
     * Fills {@code out} with only the moves that would convert at least {@code minCaptures} enemy
     * pieces, in the same relative order {@link #generateMoves} would list them (clones first, then
     * jumps), and returns the count. Never includes a pass. Because the number of conversions depends
     * only on the destination square, this checks each reachable empty square once instead of
     * generating every move and counting. Returns 0 if the game is over.
     */
    public int generateCaptureMoves(int[] out, int minCaptures) {
        if (isGameOver()) {
            return 0;
        }
        long mine = pieces(sideToMove);
        long theirs = pieces(1 - sideToMove);
        long noisy = Bitboards.expand2(mine) & empty() & Bitboards.atLeastNeighbours(theirs, minCaptures);
        int n = 0;
        for (long t = noisy & Bitboards.expand1(mine); t != 0; t &= t - 1) {
            out[n++] = Move.clone(Long.numberOfTrailingZeros(t));
        }
        for (long p = mine; p != 0; p &= p - 1) {
            int from = Long.numberOfTrailingZeros(p);
            for (long t = Bitboards.ring2(from) & noisy; t != 0; t &= t - 1) {
                out[n++] = Move.jump(from, Long.numberOfTrailingZeros(t));
            }
        }
        return n;
    }

    /**
     * Like {@link #generateCaptureMoves} for a position the caller has already found not to be over, and with an ordering key
     * per move in {@code keys}: the score (twice the number of enemy pieces the move converts, plus one for a clone) shifted left
     * by {@link #KEY_SHIFT}, plus {@code KEY_INDEX_MASK - index}, so that the maximum key is the first move with the best score.
     */
    public int generateScoredCaptureMoves(int[] out, int[] keys, int minCaptures) {
        long mine = pieces(sideToMove);
        long theirs = pieces(1 - sideToMove);
        long noisy = Bitboards.expand2(mine) & empty() & Bitboards.atLeastNeighbours(theirs, minCaptures);
        if (noisy == 0) {
            return 0;
        }
        int n = 0;
        for (long t = noisy & Bitboards.expand1(mine); t != 0; t &= t - 1) {
            int to = Long.numberOfTrailingZeros(t);
            keys[n] = ((2 * Long.bitCount(Bitboards.neighbours(to) & theirs) + 1) << KEY_SHIFT) | (KEY_INDEX_MASK - n);
            out[n++] = Move.clone(to);
        }
        for (long p = mine & Bitboards.expand2(noisy); p != 0; p &= p - 1) {
            int from = Long.numberOfTrailingZeros(p);
            for (long t = Bitboards.ring2(from) & noisy; t != 0; t &= t - 1) {
                int to = Long.numberOfTrailingZeros(t);
                keys[n] = ((2 * Long.bitCount(Bitboards.neighbours(to) & theirs)) << KEY_SHIFT) | (KEY_INDEX_MASK - n);
                out[n++] = Move.jump(from, to);
            }
        }
        return n;
    }

    /** Number of enemy pieces {@code move} would convert (0 for a pass). */
    public int captureCount(int move) {
        if (move == Move.PASS) {
            return 0;
        }
        return Long.bitCount(Bitboards.neighbours(Move.to(move)) & pieces(1 - sideToMove));
    }

    public boolean isLegal(int move) {
        int[] buf = new int[MAX_MOVES];
        int n = generateMoves(buf);
        for (int i = 0; i < n; i++) {
            if (buf[i] == move) {
                return true;
            }
        }
        return false;
    }

    /** Returns the position after {@code move}, which must be legal here. */
    public Position makeMove(int move) {
        long mine = pieces(sideToMove);
        long theirs = pieces(1 - sideToMove);
        int halfmove;
        long newHash = hash ^ Zobrist.SIDE_TO_MOVE;
        if (move == Move.PASS) {
            halfmove = halfmoveClock + 1;
        } else {
            int to = Move.to(move);
            long toBit = 1L << to;
            if (Move.isClone(move)) {
                halfmove = 0;
            } else {
                int from = Move.from(move);
                mine ^= 1L << from;
                newHash ^= Zobrist.piece(sideToMove, from);
                halfmove = halfmoveClock + 1;
            }
            mine |= toBit;
            newHash ^= Zobrist.piece(sideToMove, to);
            long captured = Bitboards.neighbours(to) & theirs;
            mine |= captured;
            theirs ^= captured;
            for (long c = captured; c != 0; c &= c - 1) {
                newHash ^= Zobrist.flip(Long.numberOfTrailingZeros(c));
            }
        }
        int fullmove = sideToMove == O ? fullmoveNumber + 1 : fullmoveNumber;
        return sideToMove == X
                ? new Position(mine, theirs, walls, O, halfmove, fullmove, newHash)
                : new Position(theirs, mine, walls, X, halfmove, fullmove, newHash);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Position p
                && x == p.x
                && o == p.o
                && walls == p.walls
                && sideToMove == p.sideToMove
                && halfmoveClock == p.halfmoveClock
                && fullmoveNumber == p.fullmoveNumber;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new long[] {x, o, walls, sideToMove, halfmoveClock, fullmoveNumber});
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int rank = Bitboards.SIZE - 1; rank >= 0; rank--) {
            sb.append(rank + 1).append(' ');
            for (int file = 0; file < Bitboards.SIZE; file++) {
                long bit = 1L << (rank * Bitboards.SIZE + file);
                sb.append((x & bit) != 0 ? 'x' : (o & bit) != 0 ? 'o' : (walls & bit) != 0 ? '-' : '.');
                sb.append(' ');
            }
            sb.append('\n');
        }
        return sb.append("  a b c d e f g\n").append(toFen()).toString();
    }
}
