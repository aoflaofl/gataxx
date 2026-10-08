package spamalot.gataxx.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;

class TranspositionTableTest {
    private final TranspositionTable.Entry e = new TranspositionTable.Entry();

    @Test
    void emptyTableFindsNothing() {
        TranspositionTable tt = new TranspositionTable(1);
        assertFalse(tt.probe(12345L, e).found);
        assertFalse(tt.probe(0L, e).found, "key 0 must not match an empty slot");
    }

    @Test
    void storeAndProbeRoundTrip() {
        TranspositionTable tt = new TranspositionTable(1);
        int move = Move.parse("a1c3");
        tt.store(0xABCDEF12345L, 7, TranspositionTable.BOUND_LOWER, -1234, move);
        tt.probe(0xABCDEF12345L, e);
        assertTrue(e.found);
        assertEquals(7, e.depth);
        assertEquals(TranspositionTable.BOUND_LOWER, e.bound);
        assertEquals(-1234, e.score);
        assertEquals(move, e.move);
    }

    @Test
    void extremeValuesSurvivePacking() {
        TranspositionTable tt = new TranspositionTable(1);
        for (int score : new int[] {Searcher.WIN, -Searcher.WIN, 0, 1, -1, 32767, -32768}) {
            tt.store(77L, 3, TranspositionTable.BOUND_EXACT, score, Move.PASS);
            tt.probe(77L, e);
            assertEquals(score, e.score);
            assertEquals(Move.PASS, e.move);
        }
        tt.store(78L, 3, TranspositionTable.BOUND_EXACT, 5, Move.parse("g7"));
        tt.probe(78L, e);
        assertEquals(Move.parse("g7"), e.move);
        tt.store(79L, 3, TranspositionTable.BOUND_EXACT, 5, Move.parse("a1"));
        tt.probe(79L, e);
        assertEquals(Move.parse("a1"), e.move, "move 0 (clone a1) must not be mistaken for none");
    }

    @Test
    void noMoveRoundTrips() {
        TranspositionTable tt = new TranspositionTable(1);
        tt.store(5L, 2, TranspositionTable.BOUND_UPPER, 9, Move.NONE);
        tt.probe(5L, e);
        assertTrue(e.found);
        assertEquals(Move.NONE, e.move);
    }

    @Test
    void indexCollisionIsDetectedByFullKey() {
        TranspositionTable tt = new TranspositionTable(1);
        long a = 1L;
        long b = 1L + ((long) tt.capacity() << 8); // same slot, different key
        tt.store(a, 4, TranspositionTable.BOUND_EXACT, 10, Move.NONE);
        assertFalse(tt.probe(b, e).found);
        assertTrue(tt.probe(a, e).found);
    }

    @Test
    void sameKeyIsAlwaysReplacedAndKeepsOldMoveWhenNewHasNone() {
        TranspositionTable tt = new TranspositionTable(1);
        tt.store(9L, 8, TranspositionTable.BOUND_EXACT, 1, Move.parse("c3"));
        tt.store(9L, 2, TranspositionTable.BOUND_UPPER, 2, Move.NONE);
        tt.probe(9L, e);
        assertEquals(2, e.depth);
        assertEquals(Move.parse("c3"), e.move, "best move is kept for ordering");
    }

    @Test
    void deeperEntryFromCurrentSearchResistsReplacement() {
        TranspositionTable tt = new TranspositionTable(1);
        long a = 3L;
        long b = 3L + ((long) tt.capacity() << 8);
        tt.store(a, 10, TranspositionTable.BOUND_EXACT, 1, Move.NONE);
        tt.store(b, 2, TranspositionTable.BOUND_EXACT, 2, Move.NONE);
        assertTrue(tt.probe(a, e).found, "shallow result must not evict a deeper one from the same search");
        assertFalse(tt.probe(b, e).found);
        tt.store(b, 10, TranspositionTable.BOUND_EXACT, 2, Move.NONE);
        assertTrue(tt.probe(b, e).found, "equal depth replaces");
    }

    @Test
    void oldSearchEntriesAreReplaceable() {
        TranspositionTable tt = new TranspositionTable(1);
        long a = 3L;
        long b = 3L + ((long) tt.capacity() << 8);
        tt.store(a, 10, TranspositionTable.BOUND_EXACT, 1, Move.NONE);
        tt.newSearch();
        tt.store(b, 1, TranspositionTable.BOUND_EXACT, 2, Move.NONE);
        assertTrue(tt.probe(b, e).found);
    }

    @Test
    void clearEmptiesTable() {
        TranspositionTable tt = new TranspositionTable(1);
        tt.store(42L, 1, TranspositionTable.BOUND_EXACT, 0, Move.NONE);
        assertTrue(tt.hashfull() >= 0);
        tt.clear();
        assertFalse(tt.probe(42L, e).found);
        assertEquals(0, tt.hashfull());
    }

    @Test
    void capacityIsPowerOfTwoAndScalesWithMegabytes() {
        int c1 = new TranspositionTable(1).capacity();
        int c4 = new TranspositionTable(4).capacity();
        assertEquals(Integer.bitCount(c1), 1);
        assertEquals(c1 * 4, c4);
        assertEquals(1024, new TranspositionTable(0).capacity());
    }
}
