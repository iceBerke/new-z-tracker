package ztracker.core;

import org.junit.jupiter.api.Test;
import ztracker.io.TopoJStackLoader.LoadedFloatStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Geometry parity tests for {@link TopoJSampler} — it must sample the same pixels as
 * {@link ZSampler} for every method/convention, the only difference being that the values
 * returned are float Z coordinates (µm), kept exactly, rather than integer indices.
 */
class TopoJSamplerTest {

    private static final ZSampler.PixelConvention CENTER = ZSampler.PixelConvention.PIXEL_CENTER;
    private static final ZSampler.PixelConvention CORNER = ZSampler.PixelConvention.PIXEL_CORNER;

    private static LoadedFloatStack singleFrameStack(float[][] frame) {
        int height = frame.length;
        int width  = frame[0].length;
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        return new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Collections.singletonList(0)), width, height);
    }

    @Test
    void singlePixel_preservesFractionalAndNegativeZ() {
        // Direct-Z values are physical µm — fractional and negative values must survive intact
        // (unlike Tool 1's integer indices).
        float[][] frame = {
            {0f,    0f,   0f},
            {0f, -3.75f,  0f},
            {0f,    0f,   0f}
        };
        LoadedFloatStack stack = singleFrameStack(frame);

        double[] result = TopoJSampler.sample(stack, 1, 1, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);

        assertArrayEquals(new double[]{-3.75}, result, 1e-9);
    }

    @Test
    void fourNeighbors_readsAllFourCorners() {
        float[][] frame = {
            {1.5f, 2.5f},
            {3.5f, 4.5f}
        };
        LoadedFloatStack stack = singleFrameStack(frame);

        double[] result = TopoJSampler.sample(stack, 0.5, 0.5, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR, CENTER);

        double sum = 0;
        for (double v : result) sum += v;
        assertEquals(12.0, sum, 1e-9);
    }

    @Test
    void radius_disk_includesOnlyPixelsWithinRadiusOfCenter() {
        // Mirrors ZSamplerTest's disk test, values = row*5+col as floats.
        float[][] frame = {
            { 0,  1,  2,  3,  4},
            { 5,  6,  7,  8,  9},
            {10, 11, 12, 13, 14},
            {15, 16, 17, 18, 19},
            {20, 21, 22, 23, 24}
        };
        LoadedFloatStack stack = singleFrameStack(frame);

        double[] result = TopoJSampler.sample(stack, 2, 2, 0, 0, 1.0, ZSampler.Method.RADIUS, CENTER);

        double[] expected = {7, 11, 12, 13, 17};
        double[] sorted = result.clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(expected, sorted, 1e-9);
    }

    @Test
    void radius_nearEdge_isClippedNotErrored() {
        float[][] frame = {
            {1, 2, 3},
            {4, 5, 6},
            {7, 8, 9}
        };
        LoadedFloatStack stack = singleFrameStack(frame);

        double[] result = TopoJSampler.sample(stack, 2, 1, 0, 0, 1.0, ZSampler.Method.RADIUS, CENTER);

        double[] expected = {3, 5, 6, 9}; // right neighbor (x=3) is out of bounds
        double[] sorted = result.clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(expected, sorted, 1e-9);
    }

    @Test
    void missingFrame_returnsEmptyArray() {
        LoadedFloatStack stack = singleFrameStack(new float[][]{{1f}});
        double[] result = TopoJSampler.sample(stack, 0, 0, 5, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);
        assertEquals(0, result.length);
    }

    @Test
    void outOfBounds_returnsEmptyArray() {
        LoadedFloatStack stack = singleFrameStack(new float[][]{{1f, 2f}, {3f, 4f}});
        double[] result = TopoJSampler.sample(stack, 10, 10, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);
        assertEquals(0, result.length);
    }

    @Test
    void singlePixel_pixelCorner_readsFlooredPixelNotRounded() {
        // Under PIXEL_CORNER, (1.6, 1.6) belongs to pixel (1,1), not (2,2) — same geometry
        // rule as ZSampler.
        float[][] frame = {
            {10, 11, 12},
            {13, 14, 15},
            {16, 17, 18}
        };
        LoadedFloatStack stack = singleFrameStack(frame);

        double[] result = TopoJSampler.sample(stack, 1.6, 1.6, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CORNER);

        assertArrayEquals(new double[]{14}, result, 1e-9);
    }

    @Test
    void negativeCoordinate_isInBoundsUnderCenterButOutOfBoundsUnderCorner() {
        float[][] frame = {
            {10, 11, 12},
            {13, 14, 15},
            {16, 17, 18}
        };
        LoadedFloatStack stack = singleFrameStack(frame);

        double[] centerResult = TopoJSampler.sample(stack, -0.4, 1.0, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);
        double[] cornerResult = TopoJSampler.sample(stack, -0.4, 1.0, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CORNER);

        assertArrayEquals(new double[]{13}, centerResult, 1e-9); // round(-0.4)=0 -> (0,1)
        assertEquals(0, cornerResult.length);                    // floor(-0.4)=-1 -> OOB
    }
}
