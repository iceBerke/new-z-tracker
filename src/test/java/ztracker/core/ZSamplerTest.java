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
    void radius_disk_includesOnlyPixelsWithinRadiusOfCenter() {
        // 5x5 frame, value = row*5+col. radius=1.0 around (2,2) selects the center plus its
        // 4 orthogonal neighbors (dx*dx+dy*dy<=1) -- NOT the diagonal corners, which are
        // sqrt(2) away and fall outside the disk.
        int[][] frame = {
            { 0,  1,  2,  3,  4},
            { 5,  6,  7,  8,  9},
            {10, 11, 12, 13, 14},
            {15, 16, 17, 18, 19},
            {20, 21, 22, 23, 24}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 2, 2, 0, 0, 1.0, ZSampler.Method.RADIUS);

        // Center (2,2)=12, up (2,1)=7, down (2,3)=17, left (1,2)=11, right (3,2)=13.
        // Diagonal corners 6, 8, 16, 18 must be excluded.
        double[] expected = {7, 11, 12, 13, 17};
        double[] sorted = result.clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(expected, sorted);
    }

    @Test
    void radius_largerDisk_includesDiagonalsWithinReach() {
        // radius=1.5 around (2,2): r2=2.25, so diagonal neighbors (dx*dx+dy*dy=2) now qualify
        // alongside the orthogonal ones, but corners at distance sqrt(8) (r=ceil(1.5)=2 out)
        // stay excluded since 8 > 2.25.
        int[][] frame = {
            { 0,  1,  2,  3,  4},
            { 5,  6,  7,  8,  9},
            {10, 11, 12, 13, 14},
            {15, 16, 17, 18, 19},
            {20, 21, 22, 23, 24}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 2, 2, 0, 0, 1.5, ZSampler.Method.RADIUS);

        double[] expected = {6, 7, 8, 11, 12, 13, 16, 17, 18};
        double[] sorted = result.clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(expected, sorted);
    }

    @Test
    void radius_nearEdge_isClippedNotErrored() {
        // Center at the last column (x=2) of a 3-wide frame: the disk's reach past column 2
        // into column 3 (which doesn't exist) is silently dropped, not clamped or errored,
        // so the sample count shrinks instead of the disk sliding or throwing.
        int[][] frame = {
            {1, 2, 3},
            {4, 5, 6},
            {7, 8, 9}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 2, 1, 0, 0, 1.0, ZSampler.Method.RADIUS);

        // Full disk would be center(2,1)=6, up(2,0)=3, down(2,2)=9, left(1,1)=5, right(3,1)=OOB.
        double[] expected = {3, 5, 6, 9};
        double[] sorted = result.clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(expected, sorted);
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
