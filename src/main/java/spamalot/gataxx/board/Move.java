package spamalot.gataxx.board;

/**
 * Moves are packed into an {@code int}: bits 0-5 = destination square, bits 6-11 = source square.
 * A clone has source == destination. {@link #PASS} is a distinct sentinel.
 *
 * <p>Text form follows UAI: a clone is the destination ({@code c3}), a jump is source then
 * destination ({@code a1c3}), and a pass is {@code 0000}.
 */
public final class Move {
    public static final int PASS = 1 << 12;
    public static final int NONE = -1;

    private Move() {}

    public static int clone(int to) {
        return to | (to << 6);
    }

    public static int jump(int from, int to) {
        return to | (from << 6);
    }

    public static int to(int move) {
        return move & 63;
    }

    public static int from(int move) {
        return (move >> 6) & 63;
    }

    public static boolean isPass(int move) {
        return move == PASS;
    }

    public static boolean isClone(int move) {
        return move != PASS && from(move) == to(move);
    }

    public static boolean isJump(int move) {
        return move != PASS && from(move) != to(move);
    }

    public static String squareName(int sq) {
        return "" + (char) ('a' + sq % Bitboards.SIZE) + (char) ('1' + sq / Bitboards.SIZE);
    }

    /** Parses a square such as "c3" at {@code s[off..off+2)}; returns -1 if invalid. */
    private static int parseSquare(String s, int off) {
        int f = s.charAt(off) - 'a';
        int r = s.charAt(off + 1) - '1';
        if (f < 0 || f >= Bitboards.SIZE || r < 0 || r >= Bitboards.SIZE) {
            return -1;
        }
        return r * Bitboards.SIZE + f;
    }

    public static String toString(int move) {
        if (move == NONE) {
            return "none";
        }
        if (move == PASS) {
            return "0000";
        }
        if (isClone(move)) {
            return squareName(to(move));
        }
        return squareName(from(move)) + squareName(to(move));
    }

    /** Parses UAI move text. Does not check legality in any position. */
    public static int parse(String s) {
        if (s.equals("0000")) {
            return PASS;
        }
        if (s.length() == 2) {
            int to = parseSquare(s, 0);
            if (to >= 0) {
                return clone(to);
            }
        } else if (s.length() == 4) {
            int from = parseSquare(s, 0);
            int to = parseSquare(s, 2);
            if (from >= 0 && to >= 0 && from != to) {
                return jump(from, to);
            }
        }
        throw new IllegalArgumentException("Bad move: " + s);
    }
}
