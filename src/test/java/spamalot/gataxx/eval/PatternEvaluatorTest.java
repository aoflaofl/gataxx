package spamalot.gataxx.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spamalot.gataxx.board.Move;
import spamalot.gataxx.board.Position;

class PatternEvaluatorTest {
    @TempDir
    Path dir;

    /** A table where each friendly stone is worth +10 and each enemy stone -10, expressed as a PatternFit file. */
    private Path stoneTable(double intercept, double a, double b) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# local-environment table (test)");
        lines.add("# intercept " + intercept);
        lines.add("# units " + a + " " + b);
        for (int state = 0; state < 3; state++) {
            for (int cls = 0; cls < 10; cls++) {
                for (int m = 0; m <= 8; m++) {
                    for (int t = 0; t <= 8; t++) {
                        if (LocalPatterns.index(state, cls, m, t) >= 0) {
                            double w = state == LocalPatterns.MINE ? 10 : state == LocalPatterns.THEIRS ? -10 : 0;
                            lines.add(state + " " + cls + " " + m + " " + t + " " + w);
                        }
                    }
                }
            }
        }
        Path f = dir.resolve("table.txt");
        Files.write(f, lines);
        return f;
    }

    private static final Evaluator FIXED = pos -> 8;

    @Test
    void blendIsAWeightedMeanInTheBaseUnits() throws IOException {
        Path f = stoneTable(0, 0, 2); // table output / 2 gives our units
        Position start = Position.startPos(); // 2 v 2: table sum 0
        Position afterClone = start.makeMove(Move.parse("b6")); // o to move, o has 2 and x has 3: sum -10 -> -5
        assertEquals(8, PatternEvaluator.load(f, FIXED, 0).evaluate(afterClone), "blend 0 is the base");
        assertEquals(-5, PatternEvaluator.load(f, FIXED, 1).evaluate(afterClone), "blend 1 is the table in our units");
        assertEquals(Math.round(0.5 * 8 + 0.5 * -5), PatternEvaluator.load(f, FIXED, 0.5).evaluate(afterClone));
        assertEquals(0, PatternEvaluator.load(f, FIXED, 1).evaluate(start));
    }

    @Test
    void interceptAndUnitOffsetAreApplied() throws IOException {
        Position start = Position.startPos();
        // table sum 0 + intercept 6, then (6 - a) / b with a = 2, b = 4 -> 1
        assertEquals(1, PatternEvaluator.load(stoneTable(6, 2, 4), FIXED, 1).evaluate(start));
    }

    @Test
    void rejectsBadFilesAndParameters() throws IOException {
        assertThrows(IOException.class, () -> PatternEvaluator.load(dir.resolve("missing.txt"), FIXED, 1));
        Path truncated = dir.resolve("short.txt");
        Files.write(truncated, List.of("# intercept 0", "# units 0 1", "1 0 0 0 5"));
        assertThrows(IOException.class, () -> PatternEvaluator.load(truncated, FIXED, 1));
        Path impossible = dir.resolve("bad.txt");
        Files.write(impossible, List.of("1 0 8 8 5"));
        assertThrows(IOException.class, () -> PatternEvaluator.load(impossible, FIXED, 1));
        Path ok = stoneTable(0, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> PatternEvaluator.load(ok, FIXED, 1.5));
        assertThrows(IllegalArgumentException.class, () -> PatternEvaluator.load(stoneTable(0, 0, 0), FIXED, 1));
    }

    @Test
    void theBuiltInTableLoadsAndChangesTheBaseScore() throws IOException {
        Position pos = Position.startPos().makeMove(Move.parse("b6"));
        int base = FIXED.evaluate(pos);
        assertEquals(base, PatternEvaluator.builtIn(FIXED, 0).evaluate(pos));
        assertTrue(PatternEvaluator.builtIn(FIXED, 0.5).evaluate(pos) != base);
    }
}
