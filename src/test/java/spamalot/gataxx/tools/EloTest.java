package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EloTest {
    @Test
    void evenScoreIsZero() {
        Elo e = Elo.of(10, 20, 10);
        assertEquals(0.5, e.score(), 1e-9);
        assertEquals(0, e.elo(), 1e-9);
        assertEquals(0.5, e.los(), 1e-6);
    }

    @Test
    void knownScoreToEloValues() {
        assertEquals(190.8, Elo.of(75, 0, 25).elo(), 0.1);
        assertEquals(-190.8, Elo.of(25, 0, 75).elo(), 0.1);
        assertEquals(70.4, Elo.of(60, 0, 40).elo(), 0.1);
    }

    @Test
    void marginShrinksWithMoreGames() {
        assertTrue(Elo.of(100, 0, 100).margin() < Elo.of(10, 0, 10).margin());
    }

    @Test
    void marginIsFiniteForLopsidedButNotPerfectScores() {
        Elo e = Elo.of(17, 0, 3);
        assertTrue(Double.isFinite(e.margin()), "margin " + e.margin());
        assertTrue(e.margin() > 50 && e.margin() < 400, "margin " + e.margin());
        assertEquals(Double.POSITIVE_INFINITY, Elo.of(5, 0, 0).margin());
    }

    @Test
    void losReflectsDominance() {
        assertTrue(Elo.of(60, 0, 40).los() > 0.97);
        assertTrue(Elo.of(40, 0, 60).los() < 0.03);
        assertEquals(0.5, Elo.of(0, 5, 0).los(), 1e-9);
    }

    @Test
    void extremesAreInfinite() {
        assertEquals(Double.POSITIVE_INFINITY, Elo.of(5, 0, 0).elo());
        assertEquals(Double.NEGATIVE_INFINITY, Elo.of(0, 0, 5).elo());
    }

    @Test
    void noGames() {
        Elo e = Elo.of(0, 0, 0);
        assertEquals(0, e.elo());
        assertEquals(0.5, e.score());
    }

    @Test
    void erfSanity() {
        assertEquals(0.8427, Elo.erf(1), 1e-4);
        assertEquals(-0.8427, Elo.erf(-1), 1e-4);
        assertEquals(0, Elo.erf(0), 1e-7);
    }
}
