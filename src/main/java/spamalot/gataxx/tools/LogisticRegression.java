package spamalot.gataxx.tools;

/**
 * Logistic regression with soft labels (0, 0.5, 1) fitted by Newton's method (iteratively reweighted least squares).
 * Small and dependency-free: used offline to see how well evaluation features predict game results.
 */
public final class LogisticRegression {
    private final double[] coef;
    private final double[] stdErr;
    private final int iterations;

    private LogisticRegression(double[] coef, double[] stdErr, int iterations) {
        this.coef = coef;
        this.stdErr = stdErr;
        this.iterations = iterations;
    }

    public double[] coefficients() {
        return coef.clone();
    }

    public double[] standardErrors() {
        return stdErr.clone();
    }

    public int iterations() {
        return iterations;
    }

    /** Probability predicted for one row. */
    public double predict(double[] row) {
        return sigmoid(dot(coef, row));
    }

    /** Mean cross-entropy (natural log) of the model on a data set. */
    public double logLoss(double[][] x, double[] y) {
        return logLoss(coef, x, y);
    }

    public static double logLoss(double[] coef, double[][] x, double[] y) {
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            double p = Math.min(1 - 1e-12, Math.max(1e-12, sigmoid(dot(coef, x[i]))));
            sum -= y[i] * Math.log(p) + (1 - y[i]) * Math.log(1 - p);
        }
        return sum / x.length;
    }

    /**
     * @param x one row per sample, one column per feature (include a constant column for an intercept)
     * @param y labels in [0, 1]
     * @param ridge tiny L2 penalty that keeps the Hessian invertible with collinear features
     */
    public static LogisticRegression fit(double[][] x, double[] y, double ridge) {
        int n = x.length;
        int k = x[0].length;
        double[] w = new double[k];
        int it = 0;
        double[][] hessian = new double[k][k];
        for (; it < 50; it++) {
            double[] grad = new double[k];
            for (double[] row : hessian) {
                java.util.Arrays.fill(row, 0);
            }
            for (int i = 0; i < n; i++) {
                double p = sigmoid(dot(w, x[i]));
                double r = y[i] - p;
                double v = p * (1 - p);
                double[] xi = x[i];
                for (int a = 0; a < k; a++) {
                    grad[a] += r * xi[a];
                    double va = v * xi[a];
                    for (int b = a; b < k; b++) {
                        hessian[a][b] += va * xi[b];
                    }
                }
            }
            for (int a = 0; a < k; a++) {
                hessian[a][a] += ridge;
                grad[a] -= ridge * w[a];
                for (int b = 0; b < a; b++) {
                    hessian[a][b] = hessian[b][a];
                }
            }
            double[] step = solve(hessian, grad);
            double biggest = 0;
            for (int a = 0; a < k; a++) {
                w[a] += step[a];
                biggest = Math.max(biggest, Math.abs(step[a]));
            }
            if (biggest < 1e-9) {
                it++;
                break;
            }
        }
        double[] se = new double[k];
        double[][] inv = invert(hessian);
        for (int a = 0; a < k; a++) {
            se[a] = Math.sqrt(Math.max(0, inv[a][a]));
        }
        return new LogisticRegression(w, se, it);
    }

    static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** Solves {@code a * x = b} by Gaussian elimination with partial pivoting; {@code a} is left unchanged. */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        eliminate(m, n, n + 1);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = m[i][n] / m[i][i];
        }
        return x;
    }

    static double[][] invert(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1;
        }
        eliminate(m, n, 2 * n);
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                inv[i][j] = m[i][n + j] / m[i][i];
            }
        }
        return inv;
    }

    /** Reduces the left {@code n} columns of {@code m} to diagonal form (Gauss-Jordan, partial pivoting). */
    private static void eliminate(double[][] m, int n, int cols) {
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) {
                    piv = r;
                }
            }
            double[] t = m[c];
            m[c] = m[piv];
            m[piv] = t;
            if (Math.abs(m[c][c]) < 1e-300) {
                throw new ArithmeticException("singular matrix");
            }
            for (int r = 0; r < n; r++) {
                if (r == c) {
                    continue;
                }
                double f = m[r][c] / m[c][c];
                if (f != 0) {
                    for (int j = c; j < cols; j++) {
                        m[r][j] -= f * m[c][j];
                    }
                }
            }
        }
    }
}
