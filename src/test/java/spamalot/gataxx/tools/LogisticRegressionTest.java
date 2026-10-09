package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LogisticRegressionTest {
    @Test
    void solvesALinearSystem() {
        double[][] a = {{2, 1, -1}, {-3, -1, 2}, {-2, 1, 2}};
        double[] x = LogisticRegression.solve(a, new double[] {8, -11, -3});
        assertEquals(2, x[0], 1e-9);
        assertEquals(3, x[1], 1e-9);
        assertEquals(-1, x[2], 1e-9);
    }

    @Test
    void invertsAMatrix() {
        double[][] a = {{4, 7}, {2, 6}};
        double[][] inv = LogisticRegression.invert(a);
        assertEquals(0.6, inv[0][0], 1e-9);
        assertEquals(-0.7, inv[0][1], 1e-9);
        assertEquals(-0.2, inv[1][0], 1e-9);
        assertEquals(0.4, inv[1][1], 1e-9);
    }

    /** Draws labels from a known model and checks the fit recovers it, within the reported standard errors. */
    @Test
    void recoversKnownCoefficientsFromSyntheticData() {
        double[] truth = {0.3, -0.8, 1.5, 0.0};
        Random rnd = new Random(42);
        int n = 40_000;
        double[][] x = new double[n][];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = new double[] {1, rnd.nextGaussian(), rnd.nextGaussian() * 2, rnd.nextGaussian() * 0.5};
            double p = LogisticRegression.sigmoid(truth[0] * x[i][0] + truth[1] * x[i][1] + truth[2] * x[i][2]
                    + truth[3] * x[i][3]);
            y[i] = rnd.nextDouble() < p ? 1 : 0;
        }
        LogisticRegression fit = LogisticRegression.fit(x, y, 1e-9);
        double[] c = fit.coefficients();
        double[] se = fit.standardErrors();
        for (int k = 0; k < truth.length; k++) {
            assertTrue(Math.abs(c[k] - truth[k]) < 4 * se[k], "coef " + k + ": " + c[k] + " vs " + truth[k] + " se " + se[k]);
            assertTrue(se[k] > 0 && se[k] < 0.1);
        }
        assertTrue(fit.iterations() < 20);
        // A model with the true coefficients cannot be beaten much by the fit on the same data.
        assertTrue(fit.logLoss(x, y) <= LogisticRegression.logLoss(truth, x, y) + 1e-9);
    }

    @Test
    void handlesSoftLabelsAndAConstantFeature() {
        // Always labelled 0.5: the intercept must come out as 0.
        double[][] x = new double[100][];
        double[] y = new double[100];
        for (int i = 0; i < 100; i++) {
            x[i] = new double[] {1, (i % 7) - 3};
            y[i] = 0.5;
        }
        LogisticRegression fit = LogisticRegression.fit(x, y, 1e-9);
        assertEquals(0, fit.coefficients()[0], 1e-6);
        assertEquals(0, fit.coefficients()[1], 1e-6);
        assertEquals(Math.log(2), fit.logLoss(x, y), 1e-9);
    }
}
