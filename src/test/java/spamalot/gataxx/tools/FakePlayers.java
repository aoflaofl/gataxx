package spamalot.gataxx.tools;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;

/** In-process players for testing the harness without launching engines. */
final class FakePlayers {
    private FakePlayers() {}

    /** Replays {@code moves} from {@code start} to get the current position. */
    static Position replay(Position start, List<String> moves) {
        Position p = start;
        for (String m : moves) {
            p = p.makeMove(Move.parse(m));
        }
        return p;
    }

    /** A player whose move is chosen by a function of the current position. */
    static final class Scripted implements Player {
        interface Chooser {
            String choose(Position pos) throws TimeoutException;
        }

        private final String name;
        private final Chooser chooser;
        private boolean healthy = true;
        int newGames;
        boolean closed;

        Scripted(String name, Chooser chooser) {
            this.name = name;
            this.chooser = chooser;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }

        @Override
        public void newGame() {
            newGames++;
        }

        @Override
        public String bestMove(Position start, List<String> moves, String go, long timeoutMs)
                throws TimeoutException {
            try {
                return chooser.choose(replay(start, moves));
            } catch (TimeoutException e) {
                healthy = false;
                throw e;
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static Scripted random(String name, long seed) {
        Random rnd = new Random(seed);
        int[] buf = new int[Position.MAX_MOVES];
        return new Scripted(name, pos -> {
            int n = pos.generateMoves(buf);
            return Move.toString(buf[rnd.nextInt(n)]);
        });
    }

    static Scripted greedy(String name) {
        int[] buf = new int[Position.MAX_MOVES];
        return new Scripted(name, pos -> {
            int n = pos.generateMoves(buf);
            int best = buf[0];
            int bestGain = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                int gain = pos.captureCount(buf[i]) + (Move.isClone(buf[i]) ? 1 : 0);
                if (gain > bestGain) {
                    bestGain = gain;
                    best = buf[i];
                }
            }
            return Move.toString(best);
        });
    }

    static Supplier<Scripted> randoms(String name) {
        long[] seed = {1};
        return () -> random(name, seed[0]++);
    }
}
