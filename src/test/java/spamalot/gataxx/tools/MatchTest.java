package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;

class MatchTest {
    private static Match.Config cfg(int games, int concurrency) {
        return new Match.Config(games, "depth 1", "depth 1", 1000, concurrency, 4, 42);
    }

    @Test
    void openingsAreReproducibleAndLive() {
        List<Position> a = Match.openings(10, 4, 5);
        assertEquals(a, Match.openings(10, 4, 5));
        assertFalse(a.equals(Match.openings(10, 4, 6)));
        a.forEach(p -> assertFalse(p.isGameOver()));
    }

    @Test
    void timeoutFromGoArguments() {
        assertEquals(1100, Match.timeoutFor("movetime 100", 1000));
        assertEquals(60_000, Match.timeoutFor("depth 5", 1000));
    }

    @Test
    void coloursSwappedOnEachOpening() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Match.MatchResult m = Match.run(cfg(6, 1), () -> FakePlayers.random("A", 1), () -> FakePlayers.random("B", 2), log::add);
        assertEquals(6, m.total());
        assertEquals(6, log.size());
        for (int i = 0; i < 6; i += 2) {
            assertEquals(m.games().get(i).startFen(), m.games().get(i + 1).startFen());
            assertTrue(m.engine1IsX().get(i));
            assertFalse(m.engine1IsX().get(i + 1));
        }
    }

    @Test
    void oddGameCountRoundsUp() throws Exception {
        assertEquals(4, Match.run(cfg(3, 1), () -> FakePlayers.random("A", 1), () -> FakePlayers.random("B", 2), s -> { }).total());
    }

    @Test
    void identicalDeterministicPlayersDrawBySymmetry() throws Exception {
        // The same greedy deterministic player on both sides: swapping colours mirrors nothing,
        // but results must still add up and be attributed to the right engine.
        Match.MatchResult m = Match.run(cfg(10, 2), () -> FakePlayers.greedy("G1"), () -> FakePlayers.greedy("G2"), s -> { });
        assertEquals(10, m.wins() + m.draws() + m.losses());
    }

    @Test
    void greedyBeatsRandom() throws Exception {
        Match.MatchResult m = Match.run(cfg(20, 2), () -> FakePlayers.greedy("greedy"), () -> FakePlayers.random("rand", 9), s -> { });
        assertTrue(m.wins() > m.losses(), m.wins() + "-" + m.losses());
    }

    @Test
    void attributionFollowsEngineNotColour() throws Exception {
        // Engine 2 always forfeits with an illegal move, so engine 1 must win every game from either colour.
        Match.MatchResult m = Match.run(cfg(8, 2), () -> FakePlayers.random("A", 1),
                () -> new FakePlayers.Scripted("bad", pos -> "zz"), s -> { });
        assertEquals(8, m.wins());
        assertEquals(0, m.losses() + m.draws());
    }

    @Test
    void unhealthyPlayersAreReplaced() throws Exception {
        List<FakePlayers.Scripted> created = Collections.synchronizedList(new ArrayList<>());
        Match.PlayerFactory flaky = () -> {
            FakePlayers.Scripted p = new FakePlayers.Scripted("flaky", pos -> {
                throw new java.util.concurrent.TimeoutException("slow");
            });
            created.add(p);
            return p;
        };
        Match.MatchResult m = Match.run(cfg(4, 1), () -> FakePlayers.random("A", 1), flaky, s -> { });
        assertEquals(4, m.wins());
        assertTrue(created.size() >= 4, "a replacement per game: " + created.size());
        assertTrue(created.stream().allMatch(p -> p.closed), "all players closed at the end");
    }

    @Test
    void summaryMentionsScoreAndElo() {
        String s = Match.summary(new Match.MatchResult(6, 2, 2, List.of(), List.of()), "new", "old");
        assertTrue(s.contains("+6 =2 -2"), s);
        assertTrue(s.contains("70.0%"), s);
        assertTrue(s.contains("Elo difference"), s);
    }
}
