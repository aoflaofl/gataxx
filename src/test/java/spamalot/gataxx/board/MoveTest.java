package spamalot.gataxx.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoveTest {
    @Test
    void cloneRoundTrip() {
        int m = Move.parse("c3");
        assertTrue(Move.isClone(m));
        assertEquals(2 * 7 + 2, Move.to(m));
        assertEquals("c3", Move.toString(m));
    }

    @Test
    void jumpRoundTrip() {
        int m = Move.parse("a1c3");
        assertTrue(Move.isJump(m));
        assertEquals(0, Move.from(m));
        assertEquals(2 * 7 + 2, Move.to(m));
        assertEquals("a1c3", Move.toString(m));
    }

    @Test
    void passRoundTrip() {
        assertEquals(Move.PASS, Move.parse("0000"));
        assertEquals("0000", Move.toString(Move.PASS));
    }

    @Test
    void cornersParse() {
        assertEquals(0, Move.to(Move.parse("a1")));
        assertEquals(48, Move.to(Move.parse("g7")));
    }

    @Test
    void badMovesRejected() {
        for (String s : new String[] {"", "h1", "a8", "a1a1", "a1c", "abc", "a1c3e5", "A1"}) {
            assertThrows(IllegalArgumentException.class, () -> Move.parse(s), s);
        }
    }
}
