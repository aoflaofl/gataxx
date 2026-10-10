package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import spamalot.gataxx.board.Position;
import spamalot.gataxx.eval.LocalPatterns;

class PatternFitTest {
    /** Random mid-game positions from random playouts. */
    private static List<Position> positions(int n, long seed) {
        Random rnd = new Random(seed);
        int[] buf = new int[Position.MAX_MOVES];
        List<Position> out = new ArrayList<>();
        while (out.size() < n) {
            Position p = Position.startPos();
            int len = 8 + rnd.nextInt(50);
            for (int k = 0; k < len && !p.isGameOver(); k++) {
                p = p.makeMove(pick(p, buf, rnd));
            }
            if (!p.isGameOver()) {
                out.add(p);
            }
        }
        return out;
    }

    private static int pick(Position p, int[] buf, Random rnd) {
        int n = p.generateMoves(buf);
        return buf[rnd.nextInt(n)];
    }

    /** Builds rows scored by a planted table plus noise. */
    private static List<Distill.Row> plantedRows(double[] truth, double noise, int n, long seed) {
        Random rnd = new Random(seed + 1);
        int[] idx = new int[49];
        List<Distill.Row> rows = new ArrayList<>();
        int game = 0;
        for (Position p : positions(n, seed)) {
            LocalPatterns.features(p, idx);
            double s = 3.0;
            for (int j : idx) {
                if (j >= 0) {
                    s += truth[j];
                }
            }
            rows.add(new Distill.Row(game++ % 40, p.toFen(), s + rnd.nextGaussian() * noise, new int[0], 0));
        }
        return rows;
    }

    @Test
    void recoversAPlantedTableAndGeneralisesToHeldOutPositions() {
        Random rnd = new Random(3);
        double[] truth = new double[LocalPatterns.SIZE];
        for (int j = 0; j < truth.length; j++) {
            truth[j] = rnd.nextGaussian() * 2;
        }
        PatternFit.Data data = PatternFit.toData(plantedRows(truth, 1.0, 6000, 11));
        PatternFit.Data train = PatternFit.subset(data, true);
        PatternFit.Data test = PatternFit.subset(data, false);
        PatternFit.Model m = PatternFit.fit(train, 0.5, 800);
        double heldOut = PatternFit.rSquared(m, test);
        assertTrue(heldOut > 0.9, "held-out R^2 " + heldOut);
        assertTrue(PatternFit.rSquared(m, train) >= heldOut);
    }

    @Test
    void noiseOnlyDataGivesNoSkill() {
        double[] zero = new double[LocalPatterns.SIZE];
        PatternFit.Data data = PatternFit.toData(plantedRows(zero, 5.0, 3000, 21));
        PatternFit.Model m = PatternFit.fit(PatternFit.subset(data, true), 20, 400);
        assertTrue(PatternFit.rSquared(m, PatternFit.subset(data, false)) < 0.05);
    }

    @Test
    void ridgeShrinksWeightsTowardsZero() {
        Random rnd = new Random(8);
        double[] truth = new double[LocalPatterns.SIZE];
        for (int j = 0; j < truth.length; j++) {
            truth[j] = rnd.nextGaussian();
        }
        PatternFit.Data data = PatternFit.toData(plantedRows(truth, 2.0, 2000, 31));
        double small = norm(PatternFit.fit(data, 0.1, 500).weights());
        double large = norm(PatternFit.fit(data, 500, 500).weights());
        assertTrue(large < small, small + " vs " + large);
    }

    @Test
    void fitIsDeterministicAndRSquaredOfPerfectModelIsOne() {
        double[] truth = new double[LocalPatterns.SIZE];
        for (int j = 0; j < truth.length; j++) {
            truth[j] = (j % 7) - 3;
        }
        PatternFit.Data data = PatternFit.toData(plantedRows(truth, 0.0, 800, 41));
        PatternFit.Model a = PatternFit.fit(data, 1e-6, 300);
        PatternFit.Model b = PatternFit.fit(data, 1e-6, 300);
        assertEquals(a.intercept(), b.intercept(), 0);
        assertTrue(PatternFit.rSquared(a, data) > 0.99);
    }

    private static double norm(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x * x;
        }
        return Math.sqrt(s);
    }
}
