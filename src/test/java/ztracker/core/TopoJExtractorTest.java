package ztracker.core;

import org.junit.jupiter.api.Test;
import ztracker.io.TopoJStackLoader.LoadedFloatStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopoJExtractor}. The defining property is <b>identity Z</b>: the sampled
 * float pixel value IS the exported Z (µm), with no index → mapping step. Otherwise its
 * failure-classification and cross-product behaviour mirror {@link ZExtractor} exactly.
 */
class TopoJExtractorTest {

    private static final ZSampler.PixelConvention CENTER = ZSampler.PixelConvention.PIXEL_CENTER;

    /** Single 3x3 float frame (frame 0). Center pixel Z = 12.5 µm, all others 0.0. */
    private static LoadedFloatStack singleFrameStack() {
        float[][] frame = {
            {0f, 0f,   0f},
            {0f, 12.5f, 0f},
            {0f, 0f,   0f}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        return new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 3, 3);
    }

    private static TrackData oneDetection(double x, double y, int frame) {
        return new TrackData(
                new double[]{x}, new double[]{y}, new int[]{frame},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
    }

    @Test
    void extract_singlePixel_returnsSampledValueDirectlyAsZ() {
        TrackData track = oneDetection(1.0, 1.0, 0);
        ExtractionResult result = TopoJExtractor.extract(
                track, singleFrameStack(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEAN, CENTER);

        assertEquals(12.5, result.z[0], 1e-9); // the pixel value IS the Z
        assertEquals(1, result.numSamples[0]);
        assertEquals(0, result.numUnmapped[0]); // no mapping — always 0
        assertEquals(ExtractionResult.STATUS_OK, result.sampleStatus[0]);
    }

    @Test
    void extract_preservesNegativeAndFractionalZ() {
        float[][] frame = {{ -123.75f }};
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedFloatStack stack = new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 1, 1);

        ExtractionResult result = TopoJExtractor.extract(
                oneDetection(0.0, 0.0, 0), stack, 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertEquals(-123.75, result.z[0], 1e-9);
    }

    @Test
    void extract_missingFrame_isNaNAndCountedAsMissing() {
        TrackData track = oneDetection(1.0, 1.0, 99); // frame 99 not in stack
        ExtractionResult result = TopoJExtractor.extract(
                track, singleFrameStack(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]));
        assertEquals(1, result.missingFrameCount);
        assertEquals(0, result.outOfBoundsCount);
        assertEquals(ExtractionResult.STATUS_MISSING_FRAME, result.sampleStatus[0]);
    }

    @Test
    void extract_outOfBoundsPosition_isCountedSeparatelyFromMissingFrame() {
        TrackData track = oneDetection(50.0, 50.0, 0); // frame exists, position far off-grid
        ExtractionResult result = TopoJExtractor.extract(
                track, singleFrameStack(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]));
        assertEquals(0, result.missingFrameCount);
        assertEquals(1, result.outOfBoundsCount);
        assertEquals(ExtractionResult.STATUS_OUT_OF_BOUNDS, result.sampleStatus[0]);
    }

    @Test
    void extract_nanX_isNeverSampled_notTreatedAsPixelZero() {
        // Math.round(NaN)==0 trap: a NaN X must fail as invalid, not silently sample pixel 0.
        float[][] frame = {
            {0f,   0f,  0f},
            {999f, 12.5f, 0f}, // marker at column 0 that must never be read
            {0f,   0f,  0f}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedFloatStack stack = new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 3, 3);

        TrackData track = new TrackData(
                new double[]{Double.NaN}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = TopoJExtractor.extract(
                track, stack, 0, ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]), "must be NaN, not 999 from the marker pixel");
        assertEquals(0, result.numSamples[0]);
        assertEquals(1, result.invalidXYCount);
        assertEquals(ExtractionResult.STATUS_INVALID_XY, result.sampleStatus[0]);
    }

    @Test
    void extract_allSampledPixelsNaN_producesNoDataStatus() {
        // A no-data pixel in a float map is stored as NaN. Sampling only NaN pixels yields a
        // NaN aggregate — the direct-Z analogue of ZExtractor's STATUS_UNMAPPED_INDEX.
        float[][] frame = {{ Float.NaN }};
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedFloatStack stack = new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 1, 1);

        ExtractionResult result = TopoJExtractor.extract(
                oneDetection(0.0, 0.0, 0), stack, 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]));
        assertEquals(1, result.numSamples[0]);       // a pixel WAS sampled...
        assertEquals(0, result.missingFrameCount);
        assertEquals(0, result.outOfBoundsCount);
        assertEquals(ExtractionResult.STATUS_NO_DATA, result.sampleStatus[0]); // ...it was just NaN
    }

    @Test
    void extract_partialNaNSamples_stillAggregatesOverValidValues() {
        // ZAggregator NaN-filters, so a mix of NaN and real Z still yields a valid aggregate
        // (median of the non-NaN values) and STATUS_OK — matching Tool 1's partial-unmapped case.
        float[][] frame = {
            {Float.NaN, 10.0f, Float.NaN},
            {Float.NaN, 20.0f, Float.NaN},
            {Float.NaN, 30.0f, Float.NaN}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedFloatStack stack = new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 3, 3);

        ExtractionResult result = TopoJExtractor.extract(
                oneDetection(1.0, 1.0, 0), stack, 0,
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN, CENTER);

        // Radius=1.0 around (1,1) samples center(20) + 4 orthogonal (10,30,NaN,NaN) ->
        // median of {10,20,20,30}... center col values are 10,20,30; left/right are NaN.
        assertEquals(20.0, result.z[0], 1e-9);
        assertEquals(ExtractionResult.STATUS_OK, result.sampleStatus[0]);
    }

    @Test
    void extractAll_singlePixelWithAllAggregations_runsOnlyOnce() {
        TrackData track = oneDetection(1.0, 1.0, 0);
        LoadedFloatStack stack = singleFrameStack();

        List<ZSampler.Method> sampling = Arrays.asList(
                ZSampler.Method.RADIUS, ZSampler.Method.SINGLE_PIXEL);
        List<ZAggregator.Method> aggregation = Arrays.asList(
                ZAggregator.Method.MEDIAN, ZAggregator.Method.MEAN);

        List<ZExtractor.MethodCombo> combos = TopoJExtractor.extractAll(
                track, stack, 0, sampling, aggregation, CENTER);

        assertEquals(3, combos.size()); // RADIUS x {MEDIAN,MEAN} + SINGLE_PIXEL collapsed to 1
        assertEquals(1, combos.stream()
                .filter(c -> c.sampling == ZSampler.Method.SINGLE_PIXEL).count());
    }

    @Test
    void extractAll_matchesIndividualExtractCallsPerCombo() {
        TrackData track = oneDetection(1.0, 1.0, 0);
        LoadedFloatStack stack = singleFrameStack();

        List<ZExtractor.MethodCombo> combos = TopoJExtractor.extractAll(
                track, stack, 0,
                Collections.singletonList(ZSampler.Method.RADIUS),
                Arrays.asList(ZAggregator.Method.MEDIAN, ZAggregator.Method.MEAN), CENTER);

        assertEquals(2, combos.size());
        for (ZExtractor.MethodCombo combo : combos) {
            ExtractionResult expected = TopoJExtractor.extract(
                    track, stack, 0, combo.sampling, combo.aggregation, CENTER);
            assertEquals(expected.z[0], combo.result.z[0], 1e-9);
        }
    }
}
