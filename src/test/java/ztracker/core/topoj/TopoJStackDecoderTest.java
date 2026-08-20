package ztracker.core.topoj;

import org.junit.jupiter.api.Test;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopoJStackDecoder} — whole-stack validation and in-place decode.
 *
 * <p>The reference stack is deliberately tiny but shares the real one's shape: 5 source slices
 * running −4 µm to +4 µm at 2 µm per slice. It is decoded twice, at {@code encodingScale 2}
 * (unambiguous) and {@code encodingScale 1} (ambiguous), with pixel values chosen to be
 * legitimate under <b>both</b> — so the only thing that differs between the two runs is what the
 * `1.0f` sentinel is allowed to mean.
 */
class TopoJStackDecoderTest {

    private static final int    N_SLICES = 5;
    private static final double Z_FIRST  = -4.0;
    private static final double Z_STEP   = 2.0;

    private static TopoJZConversion unambiguous() {
        return new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 2.0);   // 1/2 is not a slice index
    }

    private static TopoJZConversion ambiguous() {
        return new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 1.0);   // 1.0 also encodes slice 4
    }

    private static LoadedFloatStack stack(int[] frameNumbers, float[][]... frames) {
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < frameNumbers.length; i++) {
            frameToIdx.put(frameNumbers[i], i);
            numbers.add(frameNumbers[i]);
        }
        return new LoadedFloatStack(frames, frameToIdx, numbers,
                frames[0][0].length, frames[0].length);
    }

    /** Two frames whose every value decodes under both scales, plus the 1.0 sentinel. */
    private static LoadedFloatStack referenceStack() {
        return stack(new int[]{10, 11},
                new float[][]{{2.0f, 4.0f}, {1.0f, 2.0f}},
                new float[][]{{4.0f, 2.0f}, {2.0f, 1.0f}});
    }

    private static void assertFrameEquals(float[] expected, float[] actual, String where) {
        assertEquals(expected.length, actual.length, where);
        for (int i = 0; i < expected.length; i++) {
            if (Float.isNaN(expected[i])) {
                assertTrue(Float.isNaN(actual[i]), where + "[" + i + "] should be NaN");
            } else {
                assertEquals(expected[i], actual[i], 1e-6f, where + "[" + i + "]");
            }
        }
    }

    // ── The same stack, both scales ───────────────────────────────────────────

    @Test
    void unambiguousScale_convertsValuesAndClampsTheSentinelToZFirst() {
        LoadedFloatStack s = referenceStack();
        TopoJStackDecoder.Report r = TopoJStackDecoder.decode(s, unambiguous());

        // v=2 is slice 4 (+4 µm), v=4 is slice 3 (+2 µm), v=1 is the sentinel → zFirst.
        assertFrameEquals(new float[]{4.0f, 2.0f}, s.pixels[0][0], "frame10 row0");
        assertFrameEquals(new float[]{-4.0f, 4.0f}, s.pixels[0][1], "frame10 row1");
        assertFrameEquals(new float[]{2.0f, 4.0f}, s.pixels[1][0], "frame11 row0");
        assertFrameEquals(new float[]{4.0f, -4.0f}, s.pixels[1][1], "frame11 row1");

        assertEquals(6, r.validCount);
        assertEquals(2, r.unassignedCount);
        assertEquals(0, r.nanCount);
        assertEquals(0, r.invalidCount);
        assertEquals(8, r.totalPixels());
        assertFalse(r.ambiguity.isAmbiguous());
    }

    @Test
    void ambiguousScale_sameStack_turnsTheSentinelIntoNaN_andReportsBothCandidates() {
        LoadedFloatStack s = referenceStack();
        TopoJStackDecoder.Report r = TopoJStackDecoder.decode(s, ambiguous());

        // Same pixels, different lattice: v=2 is slice 3 (+2 µm), v=4 is slice 1 (−2 µm), and
        // v=1 is both the sentinel and slice 4 — so it decodes to nothing at all.
        assertFrameEquals(new float[]{2.0f, -2.0f}, s.pixels[0][0], "frame10 row0");
        assertFrameEquals(new float[]{Float.NaN, 2.0f}, s.pixels[0][1], "frame10 row1");
        assertFrameEquals(new float[]{-2.0f, 2.0f}, s.pixels[1][0], "frame11 row0");
        assertFrameEquals(new float[]{2.0f, Float.NaN}, s.pixels[1][1], "frame11 row1");

        assertTrue(r.ambiguity.isAmbiguous());
        assertEquals(4, r.ambiguity.collidingSliceIndex());
        assertEquals(-4.0, r.ambiguity.sentinelCandidateZ(), 1e-9);  // read as k = 0
        assertEquals(4.0, r.ambiguity.sliceCandidateZ(), 1e-9);      // read as slice 4

        // The sentinel pixels are still counted as sentinels, not as failures.
        assertEquals(2, r.unassignedCount);
        assertEquals(0, r.invalidCount);
    }

    @Test
    void belowThresholdSentinel_becomesNaN_underBothScales() {
        for (TopoJZConversion c : new TopoJZConversion[]{unambiguous(), ambiguous()}) {
            LoadedFloatStack s = stack(new int[]{0}, new float[][]{{-1.0f, 2.0f}});
            TopoJStackDecoder.Report r = TopoJStackDecoder.decode(s, c);

            assertTrue(Float.isNaN(s.pixels[0][0][0]), "-1.0 means no surface was found");
            assertEquals(1, r.belowThresholdCount);
            assertEquals(0, r.invalidCount);
        }
    }

    // ── NaN is no-data, not a malformed file ──────────────────────────────────

    @Test
    void nanPixels_passThrough_andAreCountedApartFromInvalid() {
        LoadedFloatStack s = stack(new int[]{0}, new float[][]{{Float.NaN, 2.0f}});

        TopoJStackDecoder.Report r = TopoJStackDecoder.decode(s, unambiguous());

        assertTrue(Float.isNaN(s.pixels[0][0][0]), "a NaN pixel must survive the decode unchanged");
        assertEquals(4.0f, s.pixels[0][0][1], 1e-6f);
        assertEquals(1, r.nanCount);
        assertEquals(0, r.invalidCount, "NaN must never be counted as a malformed value");
        assertEquals(1, r.validCount);
    }

    // ── A non-NaN INVALID fails everything ────────────────────────────────────

    @Test
    void offLatticePixel_failsTheWholeStack_andTheMessageLocatesIt() {
        // 3.0 at encodingScale 2 is off-lattice (3/2 = 1.5 is not a slice index).
        LoadedFloatStack s = stack(new int[]{7}, new float[][]{{2.0f, 3.0f}, {4.0f, 2.0f}});

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TopoJStackDecoder.decode(s, unambiguous()));

        String m = e.getMessage();
        assertTrue(m.contains("3.0"), "must name the offending value: " + m);
        assertTrue(m.contains("frame 7"), "must name the frame: " + m);
        assertTrue(m.contains("x=1"), "must name the column: " + m);
        assertTrue(m.contains("y=0"), "must name the row: " + m);
        assertTrue(m.startsWith("1 pixel(s)"), "must lead with the total count: " + m);
        assertTrue(m.contains("nSlices=5"), "must restate the declared parameters: " + m);
    }

    @Test
    void failedValidation_leavesThePixelArrayCompletelyUntouched() {
        // The guarantee that makes two passes worth their cost: a half-decoded stack would hold
        // µm in some pixels and slice counters in others, and nothing downstream could tell.
        float[][] frame0 = {{2.0f, 4.0f}, {1.0f, 2.0f}};
        float[][] frame1 = {{4.0f, 2.0f}, {3.0f, 2.0f}};   // 3.0 is the offender, in the LAST frame
        LoadedFloatStack s = stack(new int[]{0, 1}, frame0, frame1);

        float[][] before0 = {{2.0f, 4.0f}, {1.0f, 2.0f}};
        float[][] before1 = {{4.0f, 2.0f}, {3.0f, 2.0f}};

        assertThrows(IllegalArgumentException.class,
                () -> TopoJStackDecoder.decode(s, unambiguous()));

        assertFrameEquals(before0[0], s.pixels[0][0], "frame0 row0");
        assertFrameEquals(before0[1], s.pixels[0][1], "frame0 row1");
        assertFrameEquals(before1[0], s.pixels[1][0], "frame1 row0");
        assertFrameEquals(before1[1], s.pixels[1][1], "frame1 row1");
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Test
    void perFrameSentinelCounts_areReportedPerFrame_notPooled() {
        LoadedFloatStack s = stack(new int[]{3, 9},
                new float[][]{{1.0f, 1.0f}, {2.0f, 4.0f}},   // half the frame unassigned
                new float[][]{{2.0f, 4.0f}, {2.0f, 4.0f}});  // none of it

        TopoJStackDecoder.Report r = TopoJStackDecoder.decode(s, unambiguous());

        assertEquals(2, r.perFrame.size());
        assertEquals(3, r.perFrame.get(0).frameNumber);
        assertEquals(2, r.perFrame.get(0).unassignedCount);
        assertEquals(50.0, r.perFrame.get(0).unassignedPercent, 1e-9);
        assertEquals(9, r.perFrame.get(1).frameNumber);
        assertEquals(0, r.perFrame.get(1).unassignedCount);
        assertEquals(0.0, r.perFrame.get(1).unassignedPercent, 1e-9);
    }

    @Test
    void impliedMinimumSliceCount_isTheLargestValuesOwnSliceCount() {
        // Largest value 8 = slice 1, so the stack implies at least 5 slices — the declared count.
        LoadedFloatStack full = stack(new int[]{0}, new float[][]{{8.0f, 2.0f}});
        TopoJStackDecoder.Report r = TopoJStackDecoder.decode(full, unambiguous());
        assertEquals(OptionalInt.of(N_SLICES), r.impliedMinimumSliceCount);
        assertEquals(N_SLICES, r.declaredSliceCount);
        assertTrue(r.sliceCountAgrees());

        // Nothing VALID at all implies nothing — absence, not a stand-in number.
        LoadedFloatStack sentinelsOnly = stack(new int[]{0}, new float[][]{{1.0f, -1.0f}});
        TopoJStackDecoder.Report empty = TopoJStackDecoder.decode(sentinelsOnly, unambiguous());
        assertEquals(OptionalInt.empty(), empty.impliedMinimumSliceCount);
        assertFalse(empty.sliceCountAgrees(), "nothing corroborates the declared count");
    }

    @Test
    void impliedMinimumShortOfDeclared_stillDecodes_andIsReportedNotRefused() {
        // Largest value 4 = slice 3, so nothing here was brightest at slice 1 and the stack only
        // implies 3 of the declared 5. Suspicious, entirely legitimate, and never a refusal.
        LoadedFloatStack shallow = stack(new int[]{0}, new float[][]{{4.0f, 2.0f}});

        TopoJStackDecoder.Report r = TopoJStackDecoder.decode(shallow, unambiguous());

        assertEquals(OptionalInt.of(3), r.impliedMinimumSliceCount);
        assertEquals(N_SLICES, r.declaredSliceCount);
        assertFalse(r.sliceCountAgrees(), "a shortfall must be reported, not hidden");
        // The decode succeeded and the pixels are real depths: 4 is slice 3, 2 is slice 4.
        assertFrameEquals(new float[]{2.0f, 4.0f}, shallow.pixels[0][0], "converted anyway");
        assertEquals(2, r.validCount);
        assertEquals(0, r.invalidCount);
    }
}
