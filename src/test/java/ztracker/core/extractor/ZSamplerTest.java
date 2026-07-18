package ztracker.core.extractor;

import org.junit.jupiter.api.Test;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ZSamplerTest {

    private static final ZSampler.PixelConvention CENTER = ZSampler.PixelConvention.PIXEL_CENTER;
    private static final ZSampler.PixelConvention CORNER = ZSampler.PixelConvention.PIXEL_CORNER;

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

        double[] result = ZSampler.sample(stack, 1, 1, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);

        assertArrayEquals(new double[]{100000}, result);
    }

    @Test
    void fourNeighbors_readsAllFourCorners() {
        int[][] frame = {
            {1, 2},
            {3, 4}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 0.5, 0.5, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR, CENTER);

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

        double[] result = ZSampler.sample(stack, 2, 2, 0, 0, 1.0, ZSampler.Method.RADIUS, CENTER);

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

        double[] result = ZSampler.sample(stack, 2, 2, 0, 0, 1.5, ZSampler.Method.RADIUS, CENTER);

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

        double[] result = ZSampler.sample(stack, 2, 1, 0, 0, 1.0, ZSampler.Method.RADIUS, CENTER);

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

        double[] result = ZSampler.sample(stack, 0, 0, 5, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);

        assertEquals(0, result.length);
    }

    @Test
    void outOfBounds_returnsEmptyArray() {
        int[][] frame = {{1, 2}, {3, 4}};
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 10, 10, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);

        assertEquals(0, result.length);
    }

    // ── PIXEL_CORNER convention ──────────────────────────────────────────────

    @Test
    void singlePixel_pixelCorner_readsFlooredPixelNotRounded() {
        // Under PIXEL_CORNER, integer i is pixel i's top-left corner (pixel spans [i, i+1)),
        // so (1.6, 1.6) belongs to pixel (1, 1) -- NOT (2, 2), which is what PIXEL_CENTER's
        // round-to-nearest would pick for the same sub-pixel position.
        int[][] frame = {
            {10, 11, 12},
            {13, 14, 15},
            {16, 17, 18}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 1.6, 1.6, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CORNER);

        assertArrayEquals(new double[]{14}, result); // pixel (1,1), not (2,2)=18
    }

    @Test
    void fourNeighbors_pixelCorner_bracketsHalfPixelShiftedCenters() {
        // Under PIXEL_CORNER, pixel centers sit at i+0.5, so bracketing (2.3, 2.3) requires
        // the standard bilinear half-pixel shift: floor(x-0.5)=1, xc=2 -- not floor(x)=2,
        // ceil(x)=3, which is what PIXEL_CENTER uses for the same sub-pixel position.
        int[][] frame = {
            { 0,  1,  2,  3},
            { 4,  5,  6,  7},
            { 8,  9, 10, 11},
            {12, 13, 14, 15}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 2.3, 2.3, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR, CORNER);

        // Corners: (1,1)=5, (2,1)=6, (1,2)=9, (2,2)=10.
        double sum = 0;
        for (double v : result) sum += v;
        assertEquals(30.0, sum);
    }

    @Test
    void radius_pixelCorner_anchorsOnContainingPixelNotRounded() {
        // radius=1.0 around (2.6, 2.6) under PIXEL_CORNER anchors on the containing pixel
        // floor(2.6)=2, not round(2.6)=3 (which PIXEL_CENTER would use for the same position).
        int[][] frame = {
            { 0,  1,  2,  3,  4},
            { 5,  6,  7,  8,  9},
            {10, 11, 12, 13, 14},
            {15, 16, 17, 18, 19},
            {20, 21, 22, 23, 24}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] result = ZSampler.sample(stack, 2.6, 2.6, 0, 0, 1.0, ZSampler.Method.RADIUS, CORNER);

        // Anchored at (2,2), same disk as the PIXEL_CENTER test's integer (2,2) case:
        // center=12, up(2,1)=7, down(2,3)=17, left(1,2)=11, right(3,2)=13.
        double[] expected = {7, 11, 12, 13, 17};
        double[] sorted = result.clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(expected, sorted);
    }

    @Test
    void fourNeighbors_exactIntegerBoundary_divergesBetweenConventions() {
        // At an exact-integer position, the two conventions diverge most visibly:
        // PIXEL_CENTER's floor(5)/ceil(5) collapse to the SAME pixel (5,5) sampled 4 times,
        // while PIXEL_CORNER's half-pixel shift brackets two genuinely different pixels
        // on each axis (4 and 5).
        int[][] frame = {
            { 0,  1,  2,  3,  4,  5,  6},
            { 7,  8,  9, 10, 11, 12, 13},
            {14, 15, 16, 17, 18, 19, 20},
            {21, 22, 23, 24, 25, 26, 27},
            {28, 29, 30, 31, 32, 33, 34},
            {35, 36, 37, 38, 39, 40, 41},
            {42, 43, 44, 45, 46, 47, 48}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] centerResult = ZSampler.sample(stack, 5.0, 5.0, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR, CENTER);
        double[] cornerResult = ZSampler.sample(stack, 5.0, 5.0, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR, CORNER);

        // CENTER: pixel (5,5)=40 sampled 4 times (xf==xc==5, yf==yc==5).
        double centerSum = 0;
        for (double v : centerResult) centerSum += v;
        assertEquals(160.0, centerSum);

        // CORNER: (4,4)=32, (5,4)=33, (4,5)=39, (5,5)=40.
        double cornerSum = 0;
        for (double v : cornerResult) cornerSum += v;
        assertEquals(144.0, cornerSum);
    }

    @Test
    void negativeCoordinate_isInBoundsUnderCenterButOutOfBoundsUnderCorner() {
        // The documented p7.0 gotcha: near a zero/negative coordinate the two conventions
        // disagree about whether the detection is even in bounds. x=-0.4 rounds to pixel 0
        // (in bounds) under CENTER, but floors to pixel -1 (out of bounds) under CORNER --
        // Math.round and Math.floor diverge for any negative non-integer. y=1.0 is an exact
        // integer, so it maps to row 1 under both conventions, isolating the x-axis flip.
        int[][] frame = {
            {10, 11, 12},
            {13, 14, 15},
            {16, 17, 18}
        };
        LoadedStack stack = singleFrameStack(frame);

        double[] centerResult = ZSampler.sample(stack, -0.4, 1.0, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CENTER);
        double[] cornerResult = ZSampler.sample(stack, -0.4, 1.0, 0, 0, 1.0, ZSampler.Method.SINGLE_PIXEL, CORNER);

        assertArrayEquals(new double[]{13}, centerResult); // round(-0.4)=0 -> pixel (0,1)=13
        assertEquals(0, cornerResult.length);              // floor(-0.4)=-1 -> out of bounds
    }
}
