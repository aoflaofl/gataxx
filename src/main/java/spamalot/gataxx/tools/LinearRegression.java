package spamalot.gataxx.tools;

/** Ordinary least squares (with a tiny ridge term for collinear features) via the normal equations. */
public final class LinearRegression {
    private final double[] coef;
    private final double[] stdErr;

    private LinearRegression(double[] coef, double[] stdErr) {
        this.coef = coef;
        this.stdErr = stdErr;
    }

    public double[] coefficients() {
        return coef.clone();
    }

    public double[] standardErrors() {
        return stdErr.clone();
    }

    public double predict(double[] row) {
        double s = 0;
        for (int i = 0; i < coef.length; i++) {
            s += coef[i] * row[i];
        }
        return s;
    }

    /** Coefficient of determination on a data set, with its own mean as the reference (so it can be negative). */
    public double rSquared(double[][] x, double[] y) {
        double mean = 0;
        for (double v : y) {
            mean += v;
        }
        mean /= y.length;
        double sse = 0;
        double sst = 0;
        for (int i = 0; i < y.length; i++) {
            double e = y[i] - predict(x[i]);
            sse += e * e;
            sst += (y[i] - mean) * (y[i] - mean);
        }
        return sst == 0 ? 0 : 1 - sse / sst;
    }

    /** @param x one row per sample (include a constant column for an intercept) */
    public static LinearRegression fit(double[][] x, double[] y, double ridge) {
        int n = x.length;
        int k = x[0].length;
        double[][] xtx = new double[k][k];
        double[] xty = new double[k];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < k; a++) {
                xty[a] += x[i][a] * y[i];
                for (int b = a; b < k; b++) {
                    xtx[a][b] += x[i][a] * x[i][b];
                }
            }
        }
        for (int a = 0; a < k; a++) {
            xtx[a][a] += ridge;
            for (int b = 0; b < a; b++) {
                xtx[a][b] = xtx[b][a];
            }
        }
        double[] w = LogisticRegression.solve(xtx, xty);
        double sse = 0;
        for (int i = 0; i < n; i++) {
            double e = y[i];
            for (int a = 0; a < k; a++) {
                e -= w[a] * x[i][a];
            }
            sse += e * e;
        }
        double sigma2 = sse / Math.max(1, n - k);
        double[][] inv = LogisticRegression.invert(xtx);
        double[] se = new double[k];
        for (int a = 0; a < k; a++) {
            se[a] = Math.sqrt(Math.max(0, inv[a][a] * sigma2));
        }
        return new LinearRegression(w, se);
    }
}
