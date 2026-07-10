package ztracker.core;

import org.junit.jupiter.api.Test;
import ztracker.io.TiffStackLoader.LoadedStack;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ZSamplerTest {

    private static LoadedStack singleFrameStack(int[][] frame) {
        int height = frame.length;
        int width  = frame[0].length;
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        return new LoadedStack(new int[][][]{frame}, frameToIdx,
                new java.util.ArrayList<>(java.util.Collections.singletonList(0)),
                width, height);
    }

    @Test
    void singlePixel_readsValueAbove16BitRange() {
        // 32-bit indexed TIFFs can carry indices beyond the 16-bit 0-65535 range.
        int[][] frame = {
            {0, 0, 0},
            {0, 100000, 0},
            {0, 0, 0}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 1, 1, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL);

        assertArrayEquals(new double[]{100000}, result);
    }

    @Test
    void fourNeighbors_readsAllFourCorners() {
        int[][] frame = {
            {1, 2},
            {3, 4}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 0.5, 0.5, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR);

        double sum = 0;
        for (double v : result) sum += v;
        assertEquals(10.0, sum);
    }

    @Test
    void missingFrame_returnsEmptyArray() {
        int[][] frame = {{1}};
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 0, 0, 5, 0, 1.0, ZSampler.Method.SINGLE_PIXEL);

        assertEquals(0, result.length);
    }

    @Test
    void outOfBounds_returnsEmptyArray() {
        int[][] frame = {{1, 2}, {3, 4}};
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 10, 10, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL);

        assertEquals(0, result.length);
    }
}
