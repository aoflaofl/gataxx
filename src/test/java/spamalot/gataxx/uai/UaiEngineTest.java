package spamalot.gataxx.uai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.search.Searcher;

class UaiEngineTest {
    /** Feeds {@code script} to a fresh engine and returns its output lines. */
    private static List<String> run(String script) {
        StringWriter sw = new StringWriter();
        new UaiEngine(new BufferedReader(new StringReader(script)), new PrintWriter(sw)).run();
        return sw.toString().lines().toList();
    }

    private static String bestmove(List<String> lines) {
        List<String> bm = lines.stream().filter(l -> l.startsWith("bestmove ")).toList();
        assertEquals(1, bm.size(), "expected exactly one bestmove in " + lines);
        return bm.get(0).substring("bestmove ".length());
    }

    /** The options the engine announces, with their full announcement lines (key = option name). */
    private static java.util.Map<String, String> announcedOptions(List<String> out) {
        java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
        for (String line : out) {
            if (line.startsWith("option name ")) {
                String rest = line.substring("option name ".length());
                options.put(rest.substring(0, rest.indexOf(" type ")), line);
            }
        }
        return options;
    }

    @Test
    void handshake() {
        List<String> out = run("uai\n");
        assertTrue(out.get(0).startsWith("id name "), out.toString());
        assertEquals("id author " + UaiEngine.AUTHOR, out.get(1), out.toString());
        assertEquals("uaiok", out.get(out.size() - 1), out.toString());
        java.util.Map<String, String> options = announcedOptions(out);
        // Every other line between the id lines and uaiok is an option announcement.
        assertEquals(out.size() - 3, options.size(), out.toString());
        assertEquals(EXPECTED_OPTIONS, options.keySet(), "the set of supported options");
        for (java.util.Map.Entry<String, String> e : EXPECTED_DEFAULTS.entrySet()) {
            assertEquals("option name " + e.getKey() + " " + e.getValue(), options.get(e.getKey()));
        }
    }

    /** The supported options: a change here is a change of the engine's interface. */
    private static final java.util.Set<String> EXPECTED_OPTIONS = new java.util.LinkedHashSet<>(List.of(
            "Hash", "Tempo", "EvalSafe", "EvalReach", "EvalEdge", "EvalCohesion", "Pvs", "Threads", "PairFile", "PairBlend",
            "IirMinDepth", "FutilityReduction", "PartialIterations", "Lmr", "LmrMoves", "LmrMinDepth", "LmrDeepMoves",
            "QuiesceMinCaptures", "QuiesceMaxPly", "QuiesceJumpCut", "JumpCut", "JumpCutMinEmpties", "TimeModel", "TimeSoft",
            "TimeHard", "PairMargin", "DeferMinDepth"));

    private static final java.util.Map<String, String> EXPECTED_DEFAULTS = java.util.Map.ofEntries(
            java.util.Map.entry("Hash", "type spin default 16 min 0 max 1024"),
            java.util.Map.entry("Threads", "type spin default 1 min 1 max 64"),
            java.util.Map.entry("PairBlend", "type spin default 100 min 0 max 300"),
            java.util.Map.entry("IirMinDepth", "type spin default 3 min 0 max 20"),
            java.util.Map.entry("FutilityReduction", "type spin default 24 min 0 max 500"),
            java.util.Map.entry("QuiesceMinCaptures", "type spin default 3 min 0 max 8"),
            java.util.Map.entry("QuiesceMaxPly", "type spin default 5 min 0 max 16"),
            java.util.Map.entry("JumpCut", "type spin default 3 min 0 max 3"),
            java.util.Map.entry("TimeModel", "type spin default 1 min 0 max 1"),
            java.util.Map.entry("DeferMinDepth", "type spin default 4 min 0 max 30"));

    private static int lastHashfull(List<String> out) {
        String last = out.stream().filter(l -> l.startsWith("info depth ")).reduce((x, y) -> y).orElseThrow();
        String[] t = last.split(" ");
        return Integer.parseInt(t[Arrays.asList(t).indexOf("hashfull") + 1]);
    }

    @Test
    void tableIsUsedAndReportedInInfo() {
        List<String> out = run("position startpos\ngo depth 12\n");
        assertTrue(lastHashfull(out) > 0, out.toString());
    }

    @Test
    void uainewgameClearsTable() {
        List<String> out = run("position startpos\ngo depth 6\nuainewgame\nposition startpos\ngo depth 1\n");
        assertEquals(0, lastHashfull(out), out.toString());
    }

    @Test
    void hashZeroDisablesTable() {
        List<String> out = run("setoption name Hash value 0\nposition startpos\ngo depth 4\n");
        assertTrue(out.stream().filter(l -> l.startsWith("info depth ")).noneMatch(l -> l.contains("hashfull")), out.toString());
        assertTrue(Position.startPos().isLegal(Move.parse(bestmove(out))));
    }

    @Test
    void hashCanBeResizedAndStillSearches() {
        List<String> out = run("setoption name Hash value 1\nposition startpos\ngo depth 5\n"
                + "setoption name hash value 64\ngo depth 5\n");
        assertEquals(2, out.stream().filter(l -> l.startsWith("bestmove ")).count(), out.toString());
        assertFalse(out.stream().anyMatch(l -> l.contains("error")), out.toString());
    }

    @Test
    void badHashValuesReported() {
        List<String> out = run("setoption name Hash value abc\nsetoption name Hash value -1\n"
                + "setoption name Hash value 99999\nsetoption name Hash\nisready\n");
        assertEquals(4, out.stream().filter(l -> l.startsWith("info string error")).count(), out.toString());
        assertEquals("readyok", out.get(out.size() - 1));
    }

    @Test
    void quiescenceOptionsChangeTheSearch() {
        String pos = "position fen o6/o6/o3x2/6o/2x1x2/3x3/7 x 0 9\n";
        String plain = bestmove(run("setoption name PairBlend value 0\nsetoption name QuiesceMinCaptures value 0\n" + pos + "go depth 1\n"));
        String withQ = bestmove(run("setoption name PairBlend value 0\nsetoption name QuiesceMinCaptures value 2\nsetoption name QuiesceMaxPly value 6\n"
                + pos + "go depth 1\n"));
        assertEquals("c3b5", plain);
        assertEquals("f3", withQ);
    }

    @Test
    void tempoOptionShiftsReportedScore() {
        // At depth 1 the root score is minus the opponent's static score, so tempo lowers it by exactly that bonus.
        // 32 units = 2 pieces = 200 centipieces.
        String script = "setoption name PairBlend value 0\nposition startpos\ngo depth 1\n";
        int base = firstScore(run("setoption name Tempo value 0\n" + script));
        assertEquals(base - 200, firstScore(run("setoption name Tempo value 32\n" + script)));
        assertEquals(base - 100, firstScore(run("setoption name Tempo value 16\n" + script)));
    }

    private static int firstScore(List<String> out) {
        String line = out.stream().filter(l -> l.startsWith("info depth 1 ")).findFirst().orElseThrow();
        String[] t = line.split(" ");
        return Integer.parseInt(t[Arrays.asList(t).indexOf("cp") + 1]);
    }

    @Test
    void evalWeightOptionsAcceptNegativesAndChangeScores() {
        String script = "position fen 6o/7/7/7/7/-x5/xx5 x 0 1\ngo depth 1\n";
        String base = "setoption name PairBlend value 0\nsetoption name Tempo value 0\nsetoption name QuiesceMinCaptures value 0\n";
        int plain = firstScore(run(base + script));
        int safe = firstScore(run(base + "setoption name EvalSafe value 16\n" + script));
        int unsafe = firstScore(run(base + "setoption name EvalSafe value -16\n" + script));
        assertTrue(safe != plain && unsafe != plain && safe != unsafe, plain + " " + safe + " " + unsafe);
        List<String> out = run("setoption name EvalReach value -64\nsetoption name EvalEdge value 64\nisready\n");
        assertEquals(List.of("readyok"), out);
        out = run("setoption name EvalSafe value 65\nsetoption name EvalReach value -65\nisready\n");
        assertEquals(2, out.stream().filter(l -> l.startsWith("info string error")).count(), out.toString());
    }

    @Test
    void newEvalWeightOptionsChangeScores() {
        // A busy mid-game position searched to depth 3: every feature has something to measure there.
        String script = "position startpos moves b6 a1a3 f2 g7e5 b5 e5c5 f3\ngo depth 3\n";
        String base = "setoption name PairBlend value 0\nsetoption name Tempo value 0\nsetoption name QuiesceMinCaptures value 0\n"
                + "setoption name EvalSafe value 0\nsetoption name EvalReach value 0\nsetoption name EvalEdge value 0\n";
        int plain = scoreAtDepth(run(base + script), 3);
        for (String opt : new String[] {"EvalReach", "EvalEdge", "EvalCohesion", "EvalSafe"}) {
            int changed = scoreAtDepth(run(base + "setoption name " + opt + " value 32\n" + script), 3);
            assertTrue(changed != plain, opt + ": " + plain + " vs " + changed);
        }
    }

    private static int scoreAtDepth(List<String> out, int depth) {
        String line = out.stream().filter(l -> l.startsWith("info depth " + depth + " ")).findFirst().orElseThrow();
        String[] t = line.split(" ");
        return Integer.parseInt(t[Arrays.asList(t).indexOf("cp") + 1]);
    }

    @Test
    void scoresAreReportedInCentipieces() {
        assertEquals(100, UaiEngine.toCentipieces(16));
        assertEquals(-100, UaiEngine.toCentipieces(-16));
        assertEquals(0, UaiEngine.toCentipieces(0));
        assertEquals(Searcher.WIN - 3, UaiEngine.toCentipieces(Searcher.WIN - 3), "mate scores pass through");
        assertEquals(-(Searcher.WIN - 3), UaiEngine.toCentipieces(-(Searcher.WIN - 3)));
    }

    @Test
    void badQuiescenceValuesReported() {
        List<String> out = run("setoption name QuiesceMaxPly value 99\nsetoption name QuiesceMinCaptures value x\nisready\n");
        assertEquals(2, out.stream().filter(l -> l.startsWith("info string error")).count(), out.toString());
    }

    @Test
    void unknownOptionIgnoredWithNote() {
        List<String> out = run("setoption name Frobnicate value 3\nisready\n");
        assertEquals("info string unknown option: Frobnicate", out.get(0));
        assertEquals("readyok", out.get(1));
    }

    @Test
    void isready() {
        assertEquals(List.of("readyok"), run("isready\n"));
    }

    @Test
    void quitStopsReading() {
        assertEquals(List.of("readyok"), run("isready\nquit\nisready\n"));
    }

    @Test
    void goDepthFromStartpos() {
        List<String> out = run("position startpos\ngo depth 3\n");
        assertTrue(Position.startPos().isLegal(Move.parse(bestmove(out))));
        List<String> infos = out.stream().filter(l -> l.startsWith("info depth ")).toList();
        assertEquals(3, infos.size(), out.toString());
        assertTrue(infos.get(0).startsWith("info depth 1 score cp "), infos.get(0));
        for (String info : infos) {
            assertTrue(info.contains(" nodes ") && info.contains(" time ") && info.contains(" nps "), info);
            assertTrue(info.contains(" pv "), info);
        }
        assertTrue(out.get(out.size() - 1).startsWith("bestmove "), "bestmove comes last");
    }

    @Test
    void positionWithMoves() {
        // x b6 (clone), o a1a3 (jump): x to move, o has moved off a1.
        String script = "position startpos moves b6 a1a3\ngo depth 2\n";
        Position p = Position.startPos().makeMove(Move.parse("b6")).makeMove(Move.parse("a1a3"));
        assertTrue(p.isLegal(Move.parse(bestmove(run(script)))));
    }

    @Test
    void positionFenWithMoves() {
        String fen = "x5o/7/2-1-2/7/2-1-2/7/o5x x 0 1";
        Position p = Position.fromFen(fen).makeMove(Move.parse("b6"));
        String script = "position fen " + fen + " moves b6\ngo depth 2\n";
        assertTrue(p.isLegal(Move.parse(bestmove(run(script)))));
    }

    @Test
    void positionFenWithoutMoves() {
        String fen = "7/7/7/7/7/oo5/x5o x 0 1";
        List<String> out = run("position fen " + fen + "\ngo depth 1\n");
        assertEquals("b1", bestmove(out)); // clone converting a2 and b2
    }

    @Test
    void illegalMoveKeepsPreviousPosition() {
        List<String> out = run("position startpos moves b6 a1a3 zz\ngo depth 1\n");
        assertTrue(out.stream().anyMatch(l -> l.startsWith("info string error")), out.toString());
        assertTrue(Position.startPos().isLegal(Move.parse(bestmove(out))));

        out = run("position startpos moves a7d7\ngo depth 1\n"); // illegal: distance 3
        assertTrue(out.stream().anyMatch(l -> l.startsWith("info string error")), out.toString());
        assertTrue(Position.startPos().isLegal(Move.parse(bestmove(out))));
    }

    @Test
    void badFenReportedNotFatal() {
        List<String> out = run("position fen garbage\nisready\n");
        assertTrue(out.get(0).startsWith("info string error"), out.toString());
        assertEquals("readyok", out.get(1));
    }

    @Test
    void unknownCommandIgnoredGracefully() {
        List<String> out = run("frobnicate now\n\n   \nisready\n");
        assertEquals("readyok", out.get(out.size() - 1));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("bestmove")));
    }

    @Test
    void gameOverPositionGivesPass() {
        List<String> out = run("position fen xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxxx/xxxxxoo o 0 1\ngo depth 3\n");
        assertEquals("0000", bestmove(out));
    }

    @Test
    void stuckSideBestmoveIsPass() {
        List<String> out = run("position fen x--4/---4/---4/7/7/7/6o x 0 1\ngo depth 3\n");
        assertEquals("0000", bestmove(out));
    }

    @Test
    void goMovetimeReturnsPromptly() {
        long t0 = System.nanoTime();
        List<String> out = run("position startpos\ngo movetime 150\n");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 700, "took " + ms + "ms");
        assertTrue(Position.startPos().isLegal(Move.parse(bestmove(out))));
    }

    @Test
    void xUsesBtimeAndOUsesWtime() {
        // x to move with a tiny btime must return fast even though wtime is huge...
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                run("position startpos\ngo wtime 10000000 btime 200 winc 0 binc 0\n"));
        // ...and with o to move (after x's b6), tiny wtime / huge btime likewise.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                run("position startpos moves b6\ngo wtime 200 btime 10000000\n"));
    }

    @Test
    void uainewgameResetsPosition() {
        List<String> out = run("position startpos moves b6\nuainewgame\ngo depth 1\n");
        assertTrue(Position.startPos().isLegal(Move.parse(bestmove(out))));
    }

    @Test
    void goWhileSearchingReplacesSearch() {
        List<String> out = run("position startpos\ngo infinite\ngo depth 2\n");
        // First search is stopped (reports a move), second runs to depth 2.
        assertEquals(2, out.stream().filter(l -> l.startsWith("bestmove ")).count(), out.toString());
    }

    @Test
    void badGoParametersReported() {
        List<String> out = run("go depth\ngo movetime abc\nisready\n");
        assertEquals(2, out.stream().filter(l -> l.startsWith("info string error")).count(), out.toString());
        assertEquals("readyok", out.get(out.size() - 1));
    }

    @Test
    void benchIsDeterministicAndCoversAllPositions() {
        List<String> a = run("bench 4\n");
        List<String> b = run("bench 4\n");
        assertEquals(1, a.size(), a.toString());
        String[] ta = a.get(0).split(" ");
        String[] tb = b.get(0).split(" ");
        assertEquals("bench", ta[0]);
        assertEquals("16", ta[Arrays.asList(ta).indexOf("positions") + 1]);
        assertEquals(ta[Arrays.asList(ta).indexOf("nodes") + 1], tb[Arrays.asList(tb).indexOf("nodes") + 1]);
        assertEquals(ta[Arrays.asList(ta).indexOf("scoresum") + 1], tb[Arrays.asList(tb).indexOf("scoresum") + 1]);
    }

    @Test
    void benchPositionsAreLegalAndLive() {
        for (String fen : spamalot.gataxx.search.BenchPositions.FENS) {
            Position p = Position.fromFen(fen);
            assertFalse(p.isGameOver(), fen);
            assertEquals(Position.X, p.sideToMove(), fen);
        }
    }

    @Test
    void perftCommand() {
        List<String> out = run("position startpos\nperft 3\n");
        String last = out.get(out.size() - 1);
        assertTrue(last.startsWith("nodes 6460 "), last);
        assertEquals(16, out.stream().filter(l -> l.matches("[a-g][1-7]([a-g][1-7])?: \\d+")).count());
    }

    @Test
    void dCommandPrintsBoard() {
        List<String> out = run("d\n");
        assertTrue(out.get(out.size() - 1).endsWith(Position.START_FEN), out.toString());
    }

    @Test
    void infiniteSearchStoppedByStopCommand() throws Exception {
        PipedWriter toEngine = new PipedWriter();
        StringWriter sw = new StringWriter();
        UaiEngine engine = new UaiEngine(new BufferedReader(new PipedReader(toEngine)), new PrintWriter(sw));
        Thread t = new Thread(engine::run);
        t.start();
        toEngine.write("position startpos\ngo infinite\n");
        toEngine.flush();
        waitFor(sw, "info depth 3 ");
        assertFalse(sw.toString().contains("bestmove"), "infinite search must not finish by itself");

        toEngine.write("isready\n");
        toEngine.flush();
        waitFor(sw, "readyok"); // answered while the search is running

        toEngine.write("stop\n");
        toEngine.flush();
        waitFor(sw, "bestmove ");
        toEngine.write("quit\n");
        toEngine.flush();
        t.join(2000);
        assertFalse(t.isAlive());
        String[] words = Arrays.stream(sw.toString().split("\n")).filter(l -> l.startsWith("bestmove ")).toArray(String[]::new);
        assertEquals(1, words.length);
        assertTrue(Position.startPos().isLegal(Move.parse(words[0].substring(9))));
    }

    private static void waitFor(StringWriter sw, String text) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!sw.toString().contains(text)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for '" + text + "' in:\n" + sw);
            }
            Thread.sleep(5);
        }
    }
}
