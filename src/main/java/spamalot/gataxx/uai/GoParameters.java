package spamalot.gataxx.uai;

import spamalot.gataxx.board.Position;
import spamalot.gataxx.search.SearchLimits;
import spamalot.gataxx.search.TimeManager;

/**
 * The parsed arguments of a UAI {@code go} command.
 *
 * <p>Following libataxx, {@code x} is "black" ({@code btime}/{@code binc}) and {@code o} is "white"
 * ({@code wtime}/{@code winc}). Absent values are -1 (movestogo, depth, nodes: 0).
 */
record GoParameters(
        boolean infinite,
        int depth,
        long nodes,
        long movetime,
        long wtime,
        long btime,
        long winc,
        long binc,
        int movesToGo) {

    static GoParameters parse(String[] tokens) {
        boolean infinite = false;
        int depth = 0;
        long nodes = 0;
        long movetime = -1;
        long wtime = -1;
        long btime = -1;
        long winc = 0;
        long binc = 0;
        int movesToGo = 0;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("infinite")) {
                infinite = true;
                continue;
            }
            switch (t) {
                case "depth", "nodes", "movetime", "wtime", "btime", "winc", "binc", "movestogo" -> {
                    if (i + 1 >= tokens.length) {
                        throw new IllegalArgumentException("Missing value after '" + t + "'");
                    }
                    long v = parseNonNegative(t, tokens[++i]);
                    switch (t) {
                        case "depth" -> depth = (int) Math.min(v, Integer.MAX_VALUE);
                        case "nodes" -> nodes = v;
                        case "movetime" -> movetime = v;
                        case "wtime" -> wtime = v;
                        case "btime" -> btime = v;
                        case "winc" -> winc = v;
                        case "binc" -> binc = v;
                        default -> movesToGo = (int) Math.min(v, Integer.MAX_VALUE);
                    }
                }
                default -> { /* ignore unknown parameters, as UCI recommends */ }
            }
        }
        return new GoParameters(infinite, depth, nodes, movetime, wtime, btime, winc, binc, movesToGo);
    }

    private static long parseNonNegative(String name, String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad value for '" + name + "': " + value, e);
        }
    }

    /** Converts to search limits for the side to move in {@code pos}. */
    SearchLimits toLimits(Position pos) {
        if (infinite) {
            return SearchLimits.infinite();
        }
        long soft = 0;
        long hard = 0;
        if (movetime >= 0) {
            soft = hard = Math.max(1, movetime);
        } else {
            boolean xToMove = pos.sideToMove() == Position.X;
            long left = xToMove ? btime : wtime;
            long inc = xToMove ? binc : winc;
            if (left >= 0) {
                SearchLimits clock = TimeManager.forClock(left, inc, movesToGo, depth);
                soft = clock.softMs();
                hard = clock.hardMs();
            }
        }
        return new SearchLimits(depth, soft, hard, nodes);
    }
}
