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

    /** Half-move clock value at which the game is drawn-out and ends by count. */
    public static final int HALFMOVE_LIMIT = 100;

    private final long x;
    private final long o;
    private final long walls;
    private final int sideToMove;
    private final int halfmoveClock;
    private final int fullmoveNumber;

    private Position(long x, long o, long walls, int sideToMove, int halfmoveClock, int fullmoveNumber) {
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
            return new Position(x, o, walls, side, halfmove, fullmove);
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

    /** Number of enemy pieces {@code move} would convert (0 for a pass). */
    public int captureCount(int move) {
        if (move == Move.PASS) {
            return 0;
        }
        return Long.bitCount(Bitboards.expand1(1L << Move.to(move)) & pieces(1 - sideToMove));
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
        if (move == Move.PASS) {
            halfmove = halfmoveClock + 1;
        } else {
            long toBit = 1L << Move.to(move);
            if (Move.isClone(move)) {
                halfmove = 0;
            } else {
                mine ^= 1L << Move.from(move);
                halfmove = halfmoveClock + 1;
            }
            mine |= toBit;
            long captured = Bitboards.expand1(toBit) & theirs;
            mine |= captured;
            theirs ^= captured;
        }
        int fullmove = sideToMove == O ? fullmoveNumber + 1 : fullmoveNumber;
        return sideToMove == X
                ? new Position(mine, theirs, walls, O, halfmove, fullmove)
                : new Position(theirs, mine, walls, X, halfmove, fullmove);
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
