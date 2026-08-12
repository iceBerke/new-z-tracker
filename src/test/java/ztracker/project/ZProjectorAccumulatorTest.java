package ztracker.project;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ZProjector.Accumulator}, the incremental form of {@link ZProjector#project}
 * used by the TIFF-stack input type (which streams one slice at a time so a multi-hundred-MB
 * timepoint never sits in memory whole).
 *
 * <p>The point of most of these is <b>parity</b>: the accumulator must produce exactly what
 * {@code project} produces for the same slices in the same order — same projection, same
 * z-origin indices, same tie-breaking — or the two input layouts would quietly disagree.
 */
class ZProjectorAccumulatorTest {

    // ── Parity with project() ─────────────────────────────────────────────────

    @Test
    void accumulator_matchesProject_onRandomStacks_bothModes() {
        Random rnd = new Random(20260812L);
        for (int trial = 0; trial < 20; trial++) {
            int slices = 1 + rnd.nextInt(6);
            int w = 1 + rnd.nextInt(5);
            int h = 1 + rnd.nextInt(5);

            List<float[][]> stack = new ArrayList<>();
            int[] globalZ = new int[slices];
            for (int k = 0; k < slices; k++) {
                float[][] s = new float[h][w];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // Small integral range, so ties happen often — that's the interesting case.
                        s[y][x] = rnd.nextInt(4);
                    }
                }
                stack.add(s);
                globalZ[k] = k * 3; // deliberately not 0,1,2… — indices are global, not positions
            }

            for (ZProjector.Mode mode : ZProjector.Mode.values()) {
                ZProjector.Result expected = ZProjector.project(mode, stack, globalZ);

                ZProjector.Accumulator acc = new ZProjector.Accumulator(mode);
                for (int k = 0; k < slices; k++) acc.add(stack.get(k), globalZ[k]);
                ZProjector.Result actual = acc.result();

                assertEquals(slices, acc.sliceCount());
                for (int y = 0; y < h; y++) {
                    assertArrayEquals(expected.projection[y], actual.projection[y],
                            "projection row " + y + " (trial " + trial + ", " + mode + ")");
                    assertArrayEquals(expected.zOriginIndex[y], actual.zOriginIndex[y],
                            "z-origin row " + y + " (trial " + trial + ", " + mode + ")");
                }
            }
        }
    }

    @Test
    void accumulator_keepsFirstSliceOnTies_likeProject() {
        // Every slice holds the same value at (0,0): the FIRST one added must win, in both modes.
        List<float[][]> stack = Arrays.asList(
                new float[][]{{5f}},
                new float[][]{{5f}},
                new float[][]{{5f}});
        int[] globalZ = {7, 8, 9};

        for (ZProjector.Mode mode : ZProjector.Mode.values()) {
            ZProjector.Accumulator acc = new ZProjector.Accumulator(mode);
            for (int k = 0; k < stack.size(); k++) acc.add(stack.get(k), globalZ[k]);

            assertEquals(7, acc.result().zOriginIndex[0][0], "tie should go to the first slice added");
            assertEquals(7, ZProjector.project(mode, stack, globalZ).zOriginIndex[0][0]);
        }
    }

    @Test
    void accumulator_recordsGlobalIndex_notSlicePosition() {
        // Two present layers whose global indices are 0 and 2 (the middle layer is absent).
        ZProjector.Accumulator acc = new ZProjector.Accumulator(ZProjector.Mode.MAX_Z);
        acc.add(new float[][]{{10f, 90f}}, 0);
        acc.add(new float[][]{{40f, 20f}}, 2);

        ZProjector.Result r = acc.result();
        assertArrayEquals(new float[]{40f, 90f}, r.projection[0]);
        assertArrayEquals(new int[]{2, 0}, r.zOriginIndex[0]);
    }

    // ── Streaming behaviour ───────────────────────────────────────────────────

    @Test
    void accumulator_copiesEachSlice_soCallersCanReuseOneBuffer() {
        // This is exactly what ProjectionStackScanner does: one buffer, refilled per slice.
        float[][] buffer = new float[1][2];
        ZProjector.Accumulator acc = new ZProjector.Accumulator(ZProjector.Mode.MAX_Z);

        buffer[0][0] = 10f; buffer[0][1] = 90f;
        acc.add(buffer, 0);
        buffer[0][0] = 40f; buffer[0][1] = 20f;  // same array, new contents
        acc.add(buffer, 1);
        buffer[0][0] = -1f; buffer[0][1] = -1f;  // mutated again after the fact

        ZProjector.Result r = acc.result();
        assertArrayEquals(new float[]{40f, 90f}, r.projection[0]);
        assertArrayEquals(new int[]{1, 0}, r.zOriginIndex[0]);
    }

    @Test
    void accumulator_singleSlice_returnsThatSliceAndItsIndex() {
        ZProjector.Accumulator acc = new ZProjector.Accumulator(ZProjector.Mode.MIN_Z);
        acc.add(new float[][]{{3f, 4f}}, 5);

        ZProjector.Result r = acc.result();
        assertArrayEquals(new float[]{3f, 4f}, r.projection[0]);
        assertArrayEquals(new int[]{5, 5}, r.zOriginIndex[0]);
    }

    // ── Failure modes ─────────────────────────────────────────────────────────

    @Test
    void accumulator_resultBeforeAnySlice_throws() {
        ZProjector.Accumulator acc = new ZProjector.Accumulator(ZProjector.Mode.MAX_Z);
        assertEquals(0, acc.sliceCount());
        assertThrows(IllegalStateException.class, acc::result);
    }

    @Test
    void accumulator_inconsistentSliceDimensions_throws() {
        ZProjector.Accumulator acc = new ZProjector.Accumulator(ZProjector.Mode.MAX_Z);
        acc.add(new float[][]{{1f, 2f}}, 0);

        assertThrows(IllegalArgumentException.class, () -> acc.add(new float[][]{{1f, 2f, 3f}}, 1));
        assertThrows(IllegalArgumentException.class, () -> acc.add(new float[][]{{1f}, {2f}}, 1));
        assertThrows(IllegalArgumentException.class, () -> acc.add(null, 1));
    }
}
