package ztracker.core;

import java.util.*;

/**
 * Aggregates an array of Z values (physical coordinates, not raw indices)
 * into a single representative value.
 *
 * <p>NaN values are always excluded before aggregation.
 * Returns {@code Double.NaN} if no valid values remain.
 */
public class ZAggregator {

    public enum Method {
        MEDIAN("Median"),
        MEAN("Mean");

        public final String label;
        Method(String label) { this.label = label; }
    }

    private ZAggregator() {}

    /**
     * Aggregates {@code values} using the specified method.
     *
     * @param values z-coordinate values (may contain NaN)
     * @param method aggregation strategy
     * @return aggregated value, or NaN if all inputs are NaN
     */
    public static double aggregate(double[] values, Method method) {
        double[] valid = filterNaN(values);
        if (valid.length == 0) return Double.NaN;

        if (method == Method.MEDIAN) return median(valid);
        if (method == Method.MEAN)   return mean(valid);
        throw new IllegalArgumentException("Unknown aggregation method: " + method);
    }

    /**
     * Standard deviation of valid (non-NaN) values; 0.0 if fewer than 2.
     */
    public static double std(double[] values) {
        double[] valid = filterNaN(values);
        if (valid.length < 2) return 0.0;
        double m = mean(valid);
        double sum = 0.0;
        for (double v : valid) sum += (v - m) * (v - m);
        return Math.sqrt(sum / valid.length);
    }

    // ── Private strategies ────────────────────────────────────────────────────

    private static double median(double[] sorted) {
        double[] s = sorted.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 0) ? (s[n / 2 - 1] + s[n / 2]) / 2.0 : s[n / 2];
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double[] filterNaN(double[] values) {
        int count = 0;
        for (double v : values) if (!Double.isNaN(v)) count++;
        double[] valid = new double[count];
        int i = 0;
        for (double v : values) if (!Double.isNaN(v)) valid[i++] = v;
        return valid;
    }
}
