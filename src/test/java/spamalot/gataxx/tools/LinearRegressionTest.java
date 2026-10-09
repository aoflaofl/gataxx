package spamalot.gataxx.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LinearRegressionTest {
    @Test
    void recoversKnownCoefficientsAndReportsRSquared() {
        double[] truth = {5.0, -2.0, 0.5, 0.0};
        double noise = 3.0;
        Random rnd = new Random(8);
        int n = 20_000;
        double[][] x = new double[n][];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = new double[] {1, rnd.nextGaussian() * 4, rnd.nextGaussian() * 10, rnd.nextGaussian()};
            y[i] = truth[0] + truth[1] * x[i][1] + truth[2] * x[i][2] + truth[3] * x[i][3] + rnd.nextGaussian() * noise;
        }
        LinearRegression fit = LinearRegression.fit(x, y, 1e-9);
        double[] c = fit.coefficients();
        double[] se = fit.standardErrors();
        for (int k = 0; k < truth.length; k++) {
            assertTrue(Math.abs(c[k] - truth[k]) < 4 * se[k], "coef " + k + ": " + c[k] + " vs " + truth[k] + " se " + se[k]);
        }
        // Signal variance: (-2)^2 * 4^2 + 0.5^2 * 10^2 = 64 + 25 = 89; noise variance 9 -> R^2 = 89 / 98.
        assertEquals(89.0 / 98.0, fit.rSquared(x, y), 0.01);
    }

    @Test
    void anUninformativeFeatureGainsNothingOutOfSample() {
        Random rnd = new Random(1);
        int n = 4000;
        double[][] train = new double[n][];
        double[][] test = new double[n][];
        double[] ytrain = new double[n];
        double[] ytest = new double[n];
        for (int i = 0; i < n; i++) {
            train[i] = new double[] {1, rnd.nextGaussian(), rnd.nextGaussian()};
            test[i] = new double[] {1, rnd.nextGaussian(), rnd.nextGaussian()};
            ytrain[i] = 2 * train[i][1] + rnd.nextGaussian();
            ytest[i] = 2 * test[i][1] + rnd.nextGaussian();
        }
        double[][] trainOne = new double[n][];
        double[][] testOne = new double[n][];
        for (int i = 0; i < n; i++) {
            trainOne[i] = new double[] {1, train[i][1]};
            testOne[i] = new double[] {1, test[i][1]};
        }
        double one = LinearRegression.fit(trainOne, ytrain, 1e-9).rSquared(testOne, ytest);
        double two = LinearRegression.fit(train, ytrain, 1e-9).rSquared(test, ytest);
        assertEquals(one, two, 0.005);
        assertTrue(one > 0.7);
    }
}
