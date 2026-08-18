package ztracker.core.extractor;

import org.junit.jupiter.api.Test;
import ztracker.core.ZAggregator;
import ztracker.io.extractor.TiffStackLoader;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZExtractorTest {

    private static final ZSampler.PixelConvention CENTER = ZSampler.PixelConvention.PIXEL_CENTER;

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
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEAN, CENTER);

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

        assertEquals(ExtractionResult.STATUS_OK, result.sampleStatus[0]);
        assertEquals(ExtractionResult.STATUS_MISSING_FRAME, result.sampleStatus[1]);
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
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]));
        assertEquals(0, result.numSamples[0]);
        assertEquals(0, result.missingFrameCount);
        assertEquals(1, result.outOfBoundsCount);
        assertEquals(ExtractionResult.STATUS_OUT_OF_BOUNDS, result.sampleStatus[0]);
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
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN, CENTER);

        // Radius=1.0 around (1,1) covers the center (5) plus the 4 orthogonal
        // neighbours (all index 1, unmapped) -> median of the single mapped value.
        assertEquals(12.5, result.z[0], 1e-9);
        assertTrue(result.numUnmapped[0] > 0);
        // Aggregate still succeeded (partial unmapped), so this is NOT flagged as an issue.
        assertEquals(ExtractionResult.STATUS_OK, result.sampleStatus[0]);
    }

    @Test
    void extract_everySampledIndexUnmapped_producesUnmappedIndexStatus() {
        // zMapping is completely empty -> every sampled index (however many) fails to
        // resolve, so the aggregate is NaN. Unlike the partial-unmapped case above,
        // this must be flagged as an issue (STATUS_UNMAPPED_INDEX), not STATUS_OK.
        Map<Integer, Double> emptyMapping = new HashMap<>();

        TrackData track = new TrackData(
                new double[]{1.0}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, singleFrameStack(), emptyMapping, 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]));
        assertEquals(1, result.numSamples[0]);   // a pixel WAS sampled...
        assertEquals(1, result.numUnmapped[0]);  // ...it just had no Z-mapping entry
        assertEquals(0, result.missingFrameCount);
        assertEquals(0, result.outOfBoundsCount);
        assertEquals(ExtractionResult.STATUS_UNMAPPED_INDEX, result.sampleStatus[0]);
    }

    @Test
    void extract_noDataSentinelPixel_isUnmapped_withoutAnyDownstreamSpecialCase() {
        // p10.12's whole case for costing two lines is that TiffStackLoader.NO_DATA needs no
        // downstream handling: it is negative, and ZMappingLoader's key pattern is "(\d+)" —
        // digits only — so a negative key CANNOT exist in any mapping, by construction rather
        // than by luck. indicesToZ therefore turns it into NaN on its own. ZExtractor contains
        // no reference to NO_DATA, deliberately; if that ever changes, this test still holds.
        int[][] frame = {
            {TiffStackLoader.NO_DATA, 1},
            {1, 1}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedStack stack = new LoadedStack(new int[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 2, 2);

        TrackData track = new TrackData(
                new double[]{0.0}, new double[]{0.0}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, stack, zMapping(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]), "a no-data pixel must not yield a Z");
        assertEquals(1, result.numSamples[0], "a pixel WAS sampled...");
        assertEquals(1, result.numUnmapped[0], "...and is disclosed as unmapped");
        assertEquals(ExtractionResult.STATUS_UNMAPPED_INDEX, result.sampleStatus[0]);
        // Not confused with the other two NaN-Z causes, which need different fixes.
        assertEquals(0, result.missingFrameCount);
        assertEquals(0, result.outOfBoundsCount);
    }

    @Test
    void extract_noDataSentinelAlongsideMappedNeighbours_stillAggregatesOverTheRest() {
        // Partial no-data behaves like partial unmapped: ZAggregator NaN-filters, so the
        // aggregate still succeeds over the real samples and numUnmapped discloses the rest.
        int[][] frame = {
            {1, 1, 1},
            {1, TiffStackLoader.NO_DATA, 1},
            {1, 1, 1}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedStack stack = new LoadedStack(new int[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 3, 3);

        TrackData track = new TrackData(
                new double[]{1.0}, new double[]{1.0}, new int[]{0},
                new double[]{1.5}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, stack, zMapping(), 0,
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN, CENTER);

        assertEquals(0.0, result.z[0], "index 1 maps to 0.0 — the real neighbours carry it");
        assertEquals(ExtractionResult.STATUS_OK, result.sampleStatus[0]);
        assertTrue(result.numUnmapped[0] >= 1, "the no-data pixel is still disclosed");
    }

    @Test
    void extract_nanX_isNeverSampled_notTreatedAsPixelZero() {
        // Regression test: Math.round(Double.NaN) == 0 in Java, so a naive implementation
        // that doesn't check for NaN X/Y before sampling would silently treat x=NaN as
        // x=0 and sample pixel (0, round(y)) -- producing a bogus "valid" Z instead of
        // failing. Plant a distinctive, otherwise-unreachable marker value at column 0
        // to prove that pixel is never read.
        int[][] frame = {
            { 1,  1,  1},
            {999,  5,  1},  // column 0 has a marker value that must never be sampled
            { 1,  1,  1}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedStack stack = new LoadedStack(new int[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 3, 3);

        Map<Integer, Double> mapping = new HashMap<>();
        mapping.put(999, 42.0); // if the marker pixel were ever sampled, z would be 42.0
        mapping.put(5, 12.5);

        TrackData track = new TrackData(
                new double[]{Double.NaN}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, stack, mapping, 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, CENTER);

        assertTrue(Double.isNaN(result.z[0]), "must be NaN, not 42.0 from the marker pixel");
        assertEquals(0, result.numSamples[0], "sampling must never be attempted");
        assertEquals(0, result.missingFrameCount);
        assertEquals(0, result.outOfBoundsCount);
        assertEquals(1, result.invalidXYCount);
        assertEquals(ExtractionResult.STATUS_INVALID_XY, result.sampleStatus[0]);
    }

    @Test
    void extract_pixelCorner_threadsThroughToSamplerAndResult() {
        // A detection at (1.6, 1.6): under PIXEL_CENTER this rounds to pixel (2,2)=1
        // (unmapped-to-nonzero edge value, mapped to 0.0), but under PIXEL_CORNER it floors
        // to pixel (1,1)=5 (the mapped center, 12.5). Confirms the convention parameter
        // genuinely reaches ZSampler -- not just gets stored/ignored -- and that
        // ExtractionResult.pixelConvention reports the convention actually used.
        TrackData track = new TrackData(
                new double[]{1.6}, new double[]{1.6}, new int[]{0},
                new double[]{1.0}, new String[]{"1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, singleFrameStack(), zMapping(), 0,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN,
                ZSampler.PixelConvention.PIXEL_CORNER);

        assertEquals(12.5, result.z[0], 1e-9);
        assertEquals(ZSampler.PixelConvention.PIXEL_CORNER.label, result.pixelConvention);
    }

    @Test
    void extractAll_matchesIndividualExtractCallsPerCombo() {
        TrackData track = twoDetectionTrack();
        LoadedStack stack = singleFrameStack();
        Map<Integer, Double> mapping = zMapping();

        List<ZSampler.Method> samplingMethods = Arrays.asList(
                ZSampler.Method.RADIUS, ZSampler.Method.FOUR_NEIGHBOR);
        List<ZAggregator.Method> aggregationMethods = Arrays.asList(
                ZAggregator.Method.MEDIAN, ZAggregator.Method.MEAN);

        List<ZExtractor.MethodCombo> combos = ZExtractor.extractAll(
                track, stack, mapping, 0, samplingMethods, aggregationMethods, CENTER);

        assertEquals(4, combos.size()); // 2 sampling x 2 aggregation

        for (ZExtractor.MethodCombo combo : combos) {
            ExtractionResult expected = ZExtractor.extract(
                    track, stack, mapping, 0, combo.sampling, combo.aggregation, CENTER);
            assertEquals(expected.z[0], combo.result.z[0], 1e-9);
            assertEquals(expected.z[1], combo.result.z[1], 1e-9,
                    () -> "mismatch for " + combo.sampling + "/" + combo.aggregation);
            assertEquals(expected.samplingMethod, combo.result.samplingMethod);
            assertEquals(expected.aggregationMethod, combo.result.aggregationMethod);
            assertEquals(expected.missingFrameCount, combo.result.missingFrameCount);
            assertEquals(expected.outOfBoundsCount, combo.result.outOfBoundsCount);
            assertEquals(expected.invalidXYCount, combo.result.invalidXYCount);
        }
    }

    @Test
    void extractAll_singlePixelWithAllAggregations_runsOnlyOnce() {
        // SINGLE_PIXEL samples exactly one pixel, so MEDIAN and MEAN of that one value
        // are identical -- running it once per aggregation method requested would be
        // pure waste (and duplicate, byte-identical exports). Only RADIUS should be
        // run once per aggregation method here.
        TrackData track = twoDetectionTrack();
        LoadedStack stack = singleFrameStack();
        Map<Integer, Double> mapping = zMapping();

        List<ZSampler.Method> samplingMethods = Arrays.asList(
                ZSampler.Method.RADIUS, ZSampler.Method.SINGLE_PIXEL);
        List<ZAggregator.Method> aggregationMethods = Arrays.asList(
                ZAggregator.Method.MEDIAN, ZAggregator.Method.MEAN);

        List<ZExtractor.MethodCombo> combos = ZExtractor.extractAll(
                track, stack, mapping, 0, samplingMethods, aggregationMethods, CENTER);

        // RADIUS x {MEDIAN, MEAN} = 2, SINGLE_PIXEL collapsed to 1 = 3 total.
        assertEquals(3, combos.size());

        long singlePixelCombos = combos.stream()
                .filter(c -> c.sampling == ZSampler.Method.SINGLE_PIXEL)
                .count();
        assertEquals(1, singlePixelCombos);

        long radiusCombos = combos.stream()
                .filter(c -> c.sampling == ZSampler.Method.RADIUS)
                .count();
        assertEquals(2, radiusCombos);
    }

    @Test
    void extractAll_singlePixelAlone_usesFirstRequestedAggregation() {
        TrackData track = twoDetectionTrack();
        LoadedStack stack = singleFrameStack();
        Map<Integer, Double> mapping = zMapping();

        List<ZExtractor.MethodCombo> combos = ZExtractor.extractAll(
                track, stack, mapping, 0,
                Collections.singletonList(ZSampler.Method.SINGLE_PIXEL),
                Arrays.asList(ZAggregator.Method.MEAN, ZAggregator.Method.MEDIAN), CENTER);

        assertEquals(1, combos.size());
        assertEquals(ZAggregator.Method.MEAN, combos.get(0).aggregation);
    }

    @Test
    void resolveComboOutputDir_singleMethod_isFlatOutputDir() {
        Path outputDir = Paths.get("out");
        ExtractionResult dummy = new ExtractionResult(
                new double[]{1.0}, new double[]{0.0}, new int[]{1}, new int[]{0},
                new String[]{ExtractionResult.STATUS_OK}, "Radius-based", "Median",
                ZSampler.PixelConvention.PIXEL_CORNER.label, 0, 0, 0);
        ZExtractor.MethodCombo combo = new ZExtractor.MethodCombo(
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN, dummy);

        Path resolved = ZExtractor.resolveComboOutputDir(outputDir, combo, false);

        assertEquals(outputDir, resolved);
    }

    @Test
    void resolveComboOutputDir_multiMethodNonSinglePixel_usesSamplingThenAggregationSubfolder() {
        Path outputDir = Paths.get("out");
        ExtractionResult dummy = new ExtractionResult(
                new double[]{1.0}, new double[]{0.0}, new int[]{1}, new int[]{0},
                new String[]{ExtractionResult.STATUS_OK}, "Radius-based", "Median",
                ZSampler.PixelConvention.PIXEL_CORNER.label, 0, 0, 0);
        ZExtractor.MethodCombo combo = new ZExtractor.MethodCombo(
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN, dummy);

        Path resolved = ZExtractor.resolveComboOutputDir(outputDir, combo, true);

        assertEquals(outputDir.resolve("radius").resolve("median"), resolved);
    }

    @Test
    void resolveComboOutputDir_multiMethodSinglePixel_collapsesToSamplingFolderOnly() {
        Path outputDir = Paths.get("out");
        ExtractionResult dummy = new ExtractionResult(
                new double[]{1.0}, new double[]{0.0}, new int[]{1}, new int[]{0},
                new String[]{ExtractionResult.STATUS_OK}, "Single Pixel", "Median",
                ZSampler.PixelConvention.PIXEL_CORNER.label, 0, 0, 0);
        ZExtractor.MethodCombo combo = new ZExtractor.MethodCombo(
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, dummy);

        Path resolved = ZExtractor.resolveComboOutputDir(outputDir, combo, true);

        // No "median" subfolder, unlike the non-single-pixel case above.
        assertEquals(outputDir.resolve("single_pixel"), resolved);
    }
}
