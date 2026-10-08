package spamalot.gataxx.board;

/** Move-generation node counting, used to validate the rules implementation. */
public final class Perft {
    private Perft() {}

    /** Number of leaf positions reachable in exactly {@code depth} plies (passes count as plies). */
    public static long perft(Position pos, int depth) {
        if (depth == 0) {
            return 1;
        }
        int[] moves = new int[Position.MAX_MOVES];
        int n = pos.generateMoves(moves);
        if (depth == 1) {
            return n;
        }
        long nodes = 0;
        for (int i = 0; i < n; i++) {
            nodes += perft(pos.makeMove(moves[i]), depth - 1);
        }
        return nodes;
    }
}
