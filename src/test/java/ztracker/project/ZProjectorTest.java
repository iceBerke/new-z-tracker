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

    // ── NaN handling (p10.11) ─────────────────────────────────────────────────

    @Test
    void nanInLowestLayer_doesNotWin_soZOriginNamesTheRealExtremumsLayer() {
        // The reported bug: every comparison against NaN is false in Java, so a NaN seed could
        // never be beaten and pinned the pixel to layer 0 — a VALID mapping key, so Tool 2
        // resolved it to a real depth and reported STATUS_OK. A confident wrong Z.
        float[][] l0 = slice(2, 1, Float.NaN, 10f);
        float[][] l1 = slice(2, 1, 500f,      20f);
        float[][] l2 = slice(2, 1, 900f,      30f);

        ZProjector.Result max = ZProjector.project(
                ZProjector.Mode.MAX_Z, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});
        assertArrayEquals(new float[]{900f, 30f}, max.projection[0]);
        assertArrayEquals(new int[]{2, 2}, max.zOriginIndex[0]);

        // Symmetric: the mechanism is comparison-with-NaN, which is false in both directions.
        ZProjector.Result min = ZProjector.project(
                ZProjector.Mode.MIN_Z, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});
        assertArrayEquals(new float[]{500f, 10f}, min.projection[0]);
        assertArrayEquals(new int[]{1, 0}, min.zOriginIndex[0]);
    }

    @Test
    void nanInALaterLayer_wasAlreadyHarmless_andStaysThatWay() {
        // Guard against over-correcting: a NaN below the first layer never could win (NaN > x
        // is false), so this case was always right and must not move.
        float[][] l0 = slice(1, 1, 100f);
        float[][] l1 = slice(1, 1, Float.NaN);
        float[][] l2 = slice(1, 1, 900f);

        ZProjector.Result max = ZProjector.project(
                ZProjector.Mode.MAX_Z, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});
        assertArrayEquals(new float[]{900f}, max.projection[0]);
        assertArrayEquals(new int[]{2}, max.zOriginIndex[0]);

        ZProjector.Result min = ZProjector.project(
                ZProjector.Mode.MIN_Z, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});
        assertArrayEquals(new float[]{100f}, min.projection[0]);
        assertArrayEquals(new int[]{0}, min.zOriginIndex[0]);
    }

    @Test
    void allNanColumn_stillReportsLayerZero_theDocumentedLimit() {
        // NOT an endorsement — a pin. The indexed z-origin format carries layer indices with no
        // spare "no data" value, so there is nothing truthful to write here; layer 0 is what
        // comes out. Expressing it would be a format decision across both tools (see CLAUDE.md).
        // This test exists so that changing it is a deliberate choice, never an accident.
        float[][] l0 = slice(2, 1, Float.NaN, 1f);
        float[][] l1 = slice(2, 1, Float.NaN, 2f);
        float[][] l2 = slice(2, 1, Float.NaN, 3f);

        for (ZProjector.Mode mode : ZProjector.Mode.values()) {
            ZProjector.Result r = ZProjector.project(
                    mode, Arrays.asList(l0, l1, l2), new int[]{7, 8, 9});
            assertEquals(7, r.zOriginIndex[0][0], mode + ": all-NaN pixel reports the first layer");
            assertEquals(Float.NaN, r.projection[0][0], mode + ": and its projected value is NaN");
        }
    }

    @Test
    void nanIsIgnoredEvenWhenEveryLayerButOneHoldsIt() {
        // The single real value must win regardless of where it sits.
        float[][] l0 = slice(1, 1, Float.NaN);
        float[][] l1 = slice(1, 1, Float.NaN);
        float[][] l2 = slice(1, 1, 42f);

        for (ZProjector.Mode mode : ZProjector.Mode.values()) {
            ZProjector.Result r = ZProjector.project(
                    mode, Arrays.asList(l0, l1, l2), new int[]{0, 1, 2});
            assertArrayEquals(new float[]{42f}, r.projection[0], "" + mode);
            assertArrayEquals(new int[]{2}, r.zOriginIndex[0], "" + mode);
        }
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
