package ztracker.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZAggregatorTest {

    private static final double EPS = 1e-9;

    @Test
    void median_oddCount_returnsMiddleValue() {
        double[] values = {5.0, 1.0, 3.0};
        assertEquals(3.0, ZAggregator.aggregate(values, ZAggregator.Method.MEDIAN), EPS);
    }

    @Test
    void median_evenCount_returnsAverageOfMiddleTwo() {
        double[] values = {1.0, 2.0, 3.0, 4.0};
        assertEquals(2.5, ZAggregator.aggregate(values, ZAggregator.Method.MEDIAN), EPS);
    }

    @Test
    void mean_returnsAverage() {
        double[] values = {1.0, 2.0, 3.0, 4.0};
        assertEquals(2.5, ZAggregator.aggregate(values, ZAggregator.Method.MEAN), EPS);
    }

    @Test
    void aggregate_excludesNaNBeforeComputing() {
        double[] values = {Double.NaN, 2.0, 4.0};
        assertEquals(3.0, ZAggregator.aggregate(values, ZAggregator.Method.MEAN), EPS);
    }

    @Test
    void aggregate_allNaN_returnsNaN() {
        double[] values = {Double.NaN, Double.NaN};
        assertTrue(Double.isNaN(ZAggregator.aggregate(values, ZAggregator.Method.MEDIAN)));
        assertTrue(Double.isNaN(ZAggregator.aggregate(values, ZAggregator.Method.MEAN)));
    }

    @Test
    void std_usesPopulationDivisor() {
        // values 2, 4, 4, 4, 5, 5, 7, 9 -> population std = 2.0 (well-known example)
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(2.0, ZAggregator.std(values), EPS);
    }

    @Test
    void std_fewerThanTwoValidValues_returnsZero() {
        assertEquals(0.0, ZAggregator.std(new double[]{5.0}), EPS);
        assertEquals(0.0, ZAggregator.std(new double[]{Double.NaN, 5.0}), EPS);
    }

    @Test
    void std_ignoresNaN() {
        double[] withNaN = {Double.NaN, 2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(2.0, ZAggregator.std(withNaN), EPS);
    }
}
