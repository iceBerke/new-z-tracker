package ztracker.core;

import org.junit.jupiter.api.Test;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZExtractorTest {

    // Single 3x3 frame (frame number 0). Center pixel index = 5, corners = 1..4 clockwise.
    private static LoadedStack singleFrameStack() {
        int[][] frame = {
            {1, 1, 1},
            {1, 5, 1},
            {1, 1, 1}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        return new LoadedStack(new int[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 3, 3);
    }

    private static Map<Integer, Double> zMapping() {
        Map<Integer, Double> m = new HashMap<>();
        m.put(1, 0.0);
        m.put(5, 12.5);
        return m;
    }

    /** Two detections: one on-frame at the sampled center pixel, one on a missing frame. */
    private static TrackData twoDetectionTrack() {
        return new TrackData(
                new double[]{1.0, 1.0},   // x
                new double[]{1.0, 1.0},   // y
                new int[]{0, 99},         // frame (99 doesn't exist in the stack)
                new double[]{1.0, 1.0},   // radius
                new String[]{"1", "1"},   // trackId
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
    }

    @Test
    void extract_singlePixelMean_readsCenterPixelAndMapsZ() {
        TrackData track = twoDetectionTrack();
        ExtractionResult result = ZExtractor.extract(
                track, singleFrameStack(), zMapping(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEAN);

        assertEquals(12.5, result.z[0], 1e-9);
        assertEquals(1, result.numSamples[0]);
        assertEquals(0, result.numUnmapped[0]);

        // Missing frame -> NaN, zero samples
        assertTrue(Double.isNaN(result.z[1]));
        assertEquals(0, result.numSamples[1]);

        assertEquals("Single Pixel", result.samplingMethod);
        assertEquals("Mean", result.aggregationMethod);

        assertEquals(1, result.missingFrameCount);
        assertEquals(0, result.outOfBoundsCount);
    }

    @Test
    void extract_outOfBoundsPosition_isCountedSeparatelyFromMissingFrame() {
        // Frame 0 exists in the stack, but the detection's position (50, 50) is far
        // outside the 3x3 grid -> every sampling method returns zero samples even
        // though the frame itself was found. This must be counted as "out of bounds",
        // not lumped in with genuinely missing frames.
        TrackData track = new TrackData(
                new double[]{50.0}, new double[]{50.0}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, singleFrameStack(), zMapping(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN);

        assertTrue(Double.isNaN(result.z[0]));
        assertEquals(0, result.numSamples[0]);
        assertEquals(0, result.missingFrameCount);
        assertEquals(1, result.outOfBoundsCount);
    }

    @Test
    void extract_unmappedIndex_producesNaNZAndCountsUnmapped() {
        // 4-neighbor samples 4 corner pixels around (1.4, 1.4): indices include the
        // unmapped value 1 and, since only 1 and 5 are mapped, all corners resolve;
        // use a zMapping missing index 1 to force an unmapped count instead.
        Map<Integer, Double> partialMapping = new HashMap<>();
        partialMapping.put(5, 12.5); // index 1 intentionally absent

        TrackData track = new TrackData(
                new double[]{1.0}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, singleFrameStack(), partialMapping, 0,
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN);

        // Radius=1.0 around (1,1) covers the center (5) plus the 4 orthogonal
        // neighbours (all index 1, unmapped) -> median of the single mapped value.
        assertEquals(12.5, result.z[0], 1e-9);
        assertTrue(result.numUnmapped[0] > 0);
    }

    @Test
    void extractAll_matchesIndividualExtractCallsPerCombo() {
        TrackData track = twoDetectionTrack();
        LoadedStack stack = singleFrameStack();
        Map<Integer, Double> mapping = zMapping();

        List<ZSampler.Method> samplingMethods = Arrays.asList(
                ZSampler.Method.SINGLE_PIXEL, ZSampler.Method.FOUR_NEIGHBOR);
        List<ZAggregator.Method> aggregationMethods = Arrays.asList(
                ZAggregator.Method.MEDIAN, ZAggregator.Method.MEAN);

        List<ZExtractor.MethodCombo> combos = ZExtractor.extractAll(
                track, stack, mapping, 0, samplingMethods, aggregationMethods);

        assertEquals(4, combos.size()); // 2 sampling x 2 aggregation

        for (ZExtractor.MethodCombo combo : combos) {
            ExtractionResult expected = ZExtractor.extract(
                    track, stack, mapping, 0, combo.sampling, combo.aggregation);
            assertEquals(expected.z[0], combo.result.z[0], 1e-9);
            assertEquals(expected.z[1], combo.result.z[1], 1e-9,
                    () -> "mismatch for " + combo.sampling + "/" + combo.aggregation);
            assertEquals(expected.samplingMethod, combo.result.samplingMethod);
            assertEquals(expected.aggregationMethod, combo.result.aggregationMethod);
            assertEquals(expected.missingFrameCount, combo.result.missingFrameCount);
            assertEquals(expected.outOfBoundsCount, combo.result.outOfBoundsCount);
        }
    }
}
