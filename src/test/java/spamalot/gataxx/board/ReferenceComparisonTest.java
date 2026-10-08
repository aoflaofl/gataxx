package spamalot.gataxx.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks the bitboard implementation against a deliberately naive char-grid implementation
 * of the rules, over random playouts from random (often cramped, walled) positions.
 */
class ReferenceComparisonTest {

    /** Naive Ataxx board: grid[rank][file] in {'x','o','-','.'}. */
    private static final class Naive {
        final char[][] grid = new char[7][7];
        char side;
        int halfmove;

        Naive(Position p) {
            String board = p.toFen().split(" ")[0];
            String[] ranks = board.split("/");
            for (int i = 0; i < 7; i++) {
                int file = 0;
                for (char c : ranks[i].toCharArray()) {
                    if (Character.isDigit(c)) {
                        for (int k = 0; k < c - '0'; k++) {
                            grid[6 - i][file++] = '.';
                        }
                    } else {
                        grid[6 - i][file++] = c;
                    }
                }
            }
            side = p.sideToMove() == Position.X ? 'x' : 'o';
            halfmove = p.halfmoveClock();
        }

        static char other(char c) {
            return c == 'x' ? 'o' : 'x';
        }

        boolean hasMove(char s) {
            for (int r = 0; r < 7; r++) {
                for (int f = 0; f < 7; f++) {
                    if (grid[r][f] != s) {
                        continue;
                    }
                    for (int dr = -2; dr <= 2; dr++) {
                        for (int df = -2; df <= 2; df++) {
                            int nr = r + dr;
                            int nf = f + df;
                            if (nr >= 0 && nr < 7 && nf >= 0 && nf < 7 && grid[nr][nf] == '.') {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        int count(char s) {
            int n = 0;
            for (char[] row : grid) {
                for (char c : row) {
                    if (c == s) {
                        n++;
                    }
                }
            }
            return n;
        }

        boolean gameOver() {
            return count('x') == 0 || count('o') == 0 || halfmove >= 100 || (!hasMove('x') && !hasMove('o'));
        }

        Set<String> moves() {
            Set<String> out = new TreeSet<>();
            if (gameOver()) {
                return out;
            }
            for (int r = 0; r < 7; r++) {
                for (int f = 0; f < 7; f++) {
                    if (grid[r][f] != side) {
                        continue;
                    }
                    for (int dr = -2; dr <= 2; dr++) {
                        for (int df = -2; df <= 2; df++) {
                            int nr = r + dr;
                            int nf = f + df;
                            if (nr < 0 || nr >= 7 || nf < 0 || nf >= 7 || grid[nr][nf] != '.') {
                                continue;
                            }
                            boolean isClone = Math.max(Math.abs(dr), Math.abs(df)) == 1;
                            String dest = "" + (char) ('a' + nf) + (char) ('1' + nr);
                            out.add(isClone ? dest : "" + (char) ('a' + f) + (char) ('1' + r) + dest);
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                out.add("0000");
            }
            return out;
        }

        void apply(String m) {
            if (m.equals("0000")) {
                halfmove++;
                side = other(side);
                return;
            }
            int to = m.length() - 2;
            int nf = m.charAt(to) - 'a';
            int nr = m.charAt(to + 1) - '1';
            if (m.length() == 4) {
                grid[m.charAt(1) - '1'][m.charAt(0) - 'a'] = '.';
                halfmove++;
            } else {
                halfmove = 0;
            }
            grid[nr][nf] = side;
            for (int dr = -1; dr <= 1; dr++) {
                for (int df = -1; df <= 1; df++) {
                    int r = nr + dr;
                    int f = nf + df;
                    if (r >= 0 && r < 7 && f >= 0 && f < 7 && grid[r][f] == other(side)) {
                        grid[r][f] = side;
                    }
                }
            }
            side = other(side);
        }

        String boardAndSide() {
            StringBuilder sb = new StringBuilder();
            for (int r = 6; r >= 0; r--) {
                int empty = 0;
                for (int f = 0; f < 7; f++) {
                    if (grid[r][f] == '.') {
                        empty++;
                    } else {
                        if (empty > 0) {
                            sb.append(empty);
                            empty = 0;
                        }
                        sb.append(grid[r][f]);
                    }
                }
                if (empty > 0) {
                    sb.append(empty);
                }
                if (r > 0) {
                    sb.append('/');
                }
            }
            return sb.append(' ').append(side).toString();
        }
    }

    private static Position randomPosition(Random rnd) {
        if (rnd.nextInt(4) == 0) {
            return Position.startPos();
        }
        double wallRate = rnd.nextDouble() * 0.4;
        double pieceRate = rnd.nextDouble() * 0.5;
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 7; r++) {
            int empty = 0;
            for (int f = 0; f < 7; f++) {
                double d = rnd.nextDouble();
                char c = d < wallRate ? '-' : d < wallRate + pieceRate ? (rnd.nextBoolean() ? 'x' : 'o') : 0;
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
            if (r < 6) {
                sb.append('/');
            }
        }
        return Position.fromFen(sb + (rnd.nextBoolean() ? " x 0 1" : " o 0 1"));
    }

    private static Set<String> bitboardMoves(Position p) {
        int[] buf = new int[Position.MAX_MOVES];
        int n = p.generateMoves(buf);
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            out.add(Move.toString(buf[i]));
        }
        return out;
    }

    @Test
    void randomPlayoutsAgreeWithNaiveRules() {
        Random rnd = new Random(12345);
        int[] buf = new int[Position.MAX_MOVES];
        long plies = 0;
        for (int game = 0; game < 400; game++) {
            Position pos = randomPosition(rnd);
            Naive naive = new Naive(pos);
            for (int ply = 0; ply < 300; ply++) {
                String ctx = "game " + game + " ply " + ply + "\n" + pos;
                assertEquals(naive.boardAndSide(), pos.toFen().substring(0, pos.toFen().indexOf(' ', pos.toFen().indexOf(' ') + 1)), ctx);
                assertEquals(naive.gameOver(), pos.isGameOver(), ctx);
                Set<String> expected = naive.moves();
                assertEquals(expected, bitboardMoves(pos), ctx);
                int n = pos.generateMoves(buf);
                assertEquals(expected.size(), n, ctx);
                if (n == 0) {
                    break;
                }
                int move = buf[rnd.nextInt(n)];
                naive.apply(Move.toString(move));
                pos = pos.makeMove(move);
                plies++;
            }
        }
        System.out.println("reference comparison: " + plies + " plies checked");
    }
}
