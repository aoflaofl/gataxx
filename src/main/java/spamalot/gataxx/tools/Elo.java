package spamalot.gataxx.tools;

/**
 * Elo difference estimate from a win/draw/loss record, with a 95% error margin and the
 * likelihood of superiority (probability the first player is really stronger).
 */
public record Elo(double score, double elo, double margin, double los) {

    public static Elo of(int wins, int draws, int losses) {
        int n = wins + draws + losses;
        if (n == 0) {
            return new Elo(0.5, 0, Double.POSITIVE_INFINITY, 0.5);
        }
        double score = (wins + draws / 2.0) / n;
        double var = (wins * sq(1 - score) + draws * sq(0.5 - score) + losses * sq(score)) / n;
        double se = Math.sqrt(var / n);
        double elo = toElo(score);
        // Delta method: d(elo)/d(score) = 400 / (ln 10 * s * (1 - s)); finite until the score hits 0 or 1.
        double margin = score <= 0 || score >= 1
                ? Double.POSITIVE_INFINITY
                : 1.96 * se * 400 / (Math.log(10) * score * (1 - score));
        double los = wins + losses == 0 ? 0.5 : 0.5 * (1 + erf((wins - losses) / Math.sqrt(2.0 * (wins + losses))));
        return new Elo(score, elo, margin, los);
    }

    static double toElo(double score) {
        if (score <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        if (score >= 1) {
            return Double.POSITIVE_INFINITY;
        }
        return -400 * Math.log10(1 / score - 1);
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Abramowitz-Stegun 7.1.26 approximation, accurate to about 1.5e-7. */
    static double erf(double x) {
        double sign = x < 0 ? -1 : 1;
        double ax = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * ax);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-ax * ax);
        return sign * y;
    }
}
