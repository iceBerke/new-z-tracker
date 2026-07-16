package ztracker.project;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-logic tests for {@link ZProjector} (no ImageJ / no filesystem). Covers min/max
 * selection, tie-breaking to the first layer (numpy argmax/argmin semantics), and the
 * local→global index remap that keeps indices correct when a timepoint is missing layers.
 */
class ZProjectorTest {

    /** Builds a single {@code [height][width]} slice from row-major values. */
    private static float[][] slice(int width, int height, float... rowMajor) {
        float[][] s = new float[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                s[y][x] = rowMajor[y * width + x];
            }
        }
        return s;
    }

    @Test
    void maxZ_picksBrightestPixelAndRecordsItsLayerIndex() {
        // 2x1 image, three layers. Per pixel the brightest value lives in a different layer.
        float[][] l0 = slice(2, 1, 10f,  5f);
        float[][] l1 = slice(2, 1,  3f, 20f);
        float[][] l2 = slice(2, 1,  1f,  9f);

        ZProjector.Result r = ZProjector.project(
                ZProjector.Mode.MAX_Z, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});

        assertArrayEquals(new float[]{10f, 20f}, r.projection[0]);
        assertArrayEquals(new int[]{0, 1}, r.zOriginIndex[0]); // pixel0 max in layer0, pixel1 in layer1
    }

    @Test
    void minZ_picksDarkestPixelAndRecordsItsLayerIndex() {
        float[][] l0 = slice(2, 1, 10f,  5f);
        float[][] l1 = slice(2, 1,  3f, 20f);
        float[][] l2 = slice(2, 1,  1f,  9f);

        ZProjector.Result r = ZProjector.project(
                ZProjector.Mode.MIN_Z, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});

        assertArrayEquals(new float[]{1f, 5f}, r.projection[0]);
        assertArrayEquals(new int[]{2, 0}, r.zOriginIndex[0]); // pixel0 min in layer2, pixel1 in layer0
    }

    @Test
    void tiedValues_keepTheFirstLayer_matchingNumpyArgmax() {
        // Both layers hold the same value; the FIRST (lowest index) must win.
        float[][] l0 = slice(1, 1, 7f);
        float[][] l1 = slice(1, 1, 7f);

        ZProjector.Result max = ZProjector.project(
                ZProjector.Mode.MAX_Z, Arrays.asList(l0, l1), new int[]{0, 1});
        ZProjector.Result min = ZProjector.project(
                ZProjector.Mode.MIN_Z, Arrays.asList(l0, l1), new int[]{0, 1});

        assertEquals(0, max.zOriginIndex[0][0]);
        assertEquals(0, min.zOriginIndex[0][0]);
    }

    @Test
    void missingLayer_recordsGlobalIndexNotLocalStackPosition() {
        // Timepoint present only in global layers 0 and 2 (layer 1 absent). The winner is
        // the second *stack* slice, but its recorded index must be the GLOBAL 2, not 1 —
        // mirroring np.take(valid_z_indices, argmax).
        float[][] g0 = slice(1, 1, 4f);
        float[][] g2 = slice(1, 1, 9f);

        ZProjector.Result r = ZProjector.project(
                ZProjector.Mode.MAX_Z, Arrays.asList(g0, g2), new int[]{0, 2});

        assertEquals(2, r.zOriginIndex[0][0]);
    }

    @Test
    void emptyStack_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                ZProjector.project(ZProjector.Mode.MAX_Z, Collections.emptyList(), new int[]{}));
    }

    @Test
    void indexLengthMismatch_throws() {
        float[][] l0 = slice(1, 1, 1f);
        assertThrows(IllegalArgumentException.class, () ->
                ZProjector.project(ZProjector.Mode.MAX_Z, Collections.singletonList(l0), new int[]{0, 1}));
    }
}
