package spamalot.gataxx.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PerftTest {
    private static final long[] START = {1, 16, 256, 6460, 155888, 4752668, 141865520};

    @Test
    void startPositionPerft() {
        Position p = Position.startPos();
        for (int d = 0; d < START.length; d++) {
            assertEquals(START[d], Perft.perft(p, d), "depth " + d);
        }
    }
}
