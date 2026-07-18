package ztracker.core.topoj;

import org.junit.jupiter.api.Test;
import ztracker.core.ZAggregator;
import ztracker.core.extractor.ZExtractor;
import ztracker.core.extractor.ZSampler;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the indexed extractor (Tool 2, {@link ZExtractor}) and the direct-Z / TopoJ
 * extractor (Tool 3, {@link TopoJExtractor}) run the <b>same extraction protocol</b> —
 * identical sampling geometry, aggregation, frame handling, and failure classification —
 * differing <b>only in where the Z value comes from</b>: an indexed pixel resolved through a
 * JSON {@code index → Z} map (Tool 2) vs. a float pixel that already <i>is</i> the Z (Tool 3).
 *
 * <p>The two inputs are made equivalent <b>by construction</b>: given an index grid and a
 * mapping, the paired float grid's pixel is {@code mapping.get(index)} for a mapped index and
 * {@code NaN} for an unmapped one. Under that construction the two pipelines must, for every
 * sampling × aggregation × convention combination and a battery of edge-case detections
 * (interior, partial-unmapped, fully-unmapped, out-of-bounds, missing-frame, invalid-X/Y),
 * produce <b>identical</b> {@code z}, {@code zStd}, {@code numSamples}, and missing/OOB/invalid
 * tallies. The one expected divergence — Tool 2's {@link ExtractionResult#STATUS_UNMAPPED_INDEX}
 * vs. Tool 3's {@link ExtractionResult#STATUS_NO_DATA} for a sample that yields no valid Z, and
 * Tool 2's {@code numUnmapped} bookkeeping (always 0 in Tool 3) — is asserted explicitly rather
 * than swept under the rug.
 *
 * <p>See also {@code ExtractorEquivalenceDemo} for a printed side-by-side walkthrough.
 */
class ExtractorEquivalenceTest {

    private static final int OFFSET = 1; // csvFrame + 1 = tiff frame

    // ── Fixture: one index grid + mapping, plus the float grid derived from them ──

    private static final int[][] GRID = {
        { 0,  1,  2,  3,  4},
        { 5,  6,  7,  8,  9},
        {10, 11, 12, 13, 99},   // 99 is intentionally absent from the mapping (unmapped)
        {15, 16, 17, 18, 19},
        {20, 21, 22, 23, 24},
    };

    private static Map<Integer, Double> mapping() {
        Map<Integer, Double> m = new HashMap<>();
        for (int i = 0; i <= 24; i++) m.put(i, i * 0.5 - 3.0); // decimals + negatives; index 99 absent
        return m;
    }

    /** Float grid where each pixel is its mapped Z, or NaN when the index is unmapped. */
    private static float[][] toFloatGrid(int[][] idx, Map<Integer, Double> map) {
        float[][] f = new float[idx.length][idx[0].length];
        for (int y = 0; y < idx.length; y++) {
            for (int x = 0; x < idx[0].length; x++) {
                Double z = map.get(idx[y][x]);
                f[y][x] = (z != null) ? z.floatValue() : Float.NaN;
            }
        }
        return f;
    }

    private static LoadedStack indexedStack() {
        return new LoadedStack(new int[][][]{GRID, GRID}, frameToIdx(), frameNumbers(), 5, 5);
    }

    private static LoadedFloatStack floatStack(Map<Integer, Double> map) {
        float[][] f = toFloatGrid(GRID, map);
        return new LoadedFloatStack(new float[][][]{f, f}, frameToIdx(), frameNumbers(), 5, 5);
    }

    private static Map<Integer, Integer> frameToIdx() {
        Map<Integer, Integer> m = new HashMap<>();
        m.put(1, 0); m.put(2, 1); // tiff frames 1 and 2 present (3+ absent → missing-frame case)
        return m;
    }

    private static List<Integer> frameNumbers() {
        return new ArrayList<>(Arrays.asList(1, 2));
    }

    /** Detections covering interior, unmapped, out-of-bounds, missing-frame, and invalid-X/Y. */
    private static TrackData track() {
        return new TrackData(
                new double[]{2.0, 4.0, 50.0, 2.0, Double.NaN}, // x  (d4 invalid)
                new double[]{2.0, 2.0, 50.0, 2.0, 2.0},        // y
                new int[]   {0,   0,   0,    5,   0},          // csvFrame (d3 → tiff6 missing)
                new double[]{1.0, 1.0, 1.0,  1.0, 1.0},        // radius
                new String[]{"1", "1", "1",  "1", "1"},        // trackId
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
    }

    // ── The equivalence proof ────────────────────────────────────────────────────

    @Test
    void bothExtractors_produceIdenticalResults_acrossEveryCombo() {
        TrackData track = track();
        LoadedStack indexed = indexedStack();
        Map<Integer, Double> map = mapping();
        LoadedFloatStack floats = floatStack(map);

        for (ZSampler.Method m : ZSampler.Method.values()) {
            for (ZAggregator.Method a : ZAggregator.Method.values()) {
                for (ZSampler.PixelConvention c : ZSampler.PixelConvention.values()) {
                    String combo = m + "/" + a + "/" + c;

                    ExtractionResult r2 = ZExtractor.extract(track, indexed, map, OFFSET, m, a, c);
                    ExtractionResult r3 = TopoJExtractor.extract(track, floats, OFFSET, m, a, c);

                    assertEquals(r2.size(), r3.size(), combo + " size");
                    for (int i = 0; i < r2.size(); i++) {
                        String at = combo + " [det " + i + "]";
                        assertZEqual(r2.z[i],    r3.z[i],    at + " z");
                        assertZEqual(r2.zStd[i], r3.zStd[i], at + " zStd");
                        assertEquals(r2.numSamples[i], r3.numSamples[i], at + " numSamples");
                        // Status matches once the "no valid Z" label difference is normalized.
                        assertEquals(normStatus(r2.sampleStatus[i]), normStatus(r3.sampleStatus[i]),
                                at + " status");
                        // The one documented bookkeeping difference: Tool 3 never has unmapped samples.
                        assertEquals(0, r3.numUnmapped[i], at + " tool-3 numUnmapped must be 0");
                    }
                    assertEquals(r2.missingFrameCount, r3.missingFrameCount, combo + " missingFrameCount");
                    assertEquals(r2.outOfBoundsCount,  r3.outOfBoundsCount,  combo + " outOfBoundsCount");
                    assertEquals(r2.invalidXYCount,    r3.invalidXYCount,    combo + " invalidXYCount");
                }
            }
        }
    }

    @Test
    void theOnlyDifference_isWhereZComesFrom_notHowItsProcessed() {
        // Detection d1 (4, 2) single-pixels the unmapped index 99. Both tools fail it to a NaN Z,
        // but Tool 2 records the miss in numUnmapped and labels it UNMAPPED_INDEX, while Tool 3 —
        // having no mapping — reports numUnmapped 0 and labels it NO_DATA. The Z outcome is the same.
        TrackData track = track();
        Map<Integer, Double> map = mapping();

        ExtractionResult r2 = ZExtractor.extract(track, indexedStack(), map, OFFSET,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, ZSampler.PixelConvention.PIXEL_CORNER);
        ExtractionResult r3 = TopoJExtractor.extract(track, floatStack(map), OFFSET,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, ZSampler.PixelConvention.PIXEL_CORNER);

        assertTrue(Double.isNaN(r2.z[1]) && Double.isNaN(r3.z[1]), "both fail d1 to a NaN Z");
        assertEquals(1, r2.numSamples[1]);
        assertEquals(1, r3.numSamples[1]); // a pixel WAS sampled in both — it just had no Z

        assertEquals(1, r2.numUnmapped[1], "Tool 2 counts the unmapped index");
        assertEquals(0, r3.numUnmapped[1], "Tool 3 has no mapping — always 0");

        assertEquals(ExtractionResult.STATUS_UNMAPPED_INDEX, r2.sampleStatus[1]);
        assertEquals(ExtractionResult.STATUS_NO_DATA,        r3.sampleStatus[1]);

        // And an interior detection resolves to the exact same Z through both paths.
        assertEquals(r2.z[0], r3.z[0], 1e-9, "interior detection: identical Z");
        assertEquals(map.get(12), r2.z[0], 1e-9); // pixel (2,2) = index 12 = 12*0.5-3.0 = 3.0 µm
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Asserts two Z values equal, treating NaN==NaN as a match. */
    private static void assertZEqual(double a, double b, String msg) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            assertTrue(Double.isNaN(a) && Double.isNaN(b), msg + " (NaN mismatch: " + a + " vs " + b + ")");
        } else {
            assertEquals(a, b, 1e-9, msg);
        }
    }

    /** Collapses the two "sampled but no valid Z" labels to one token so they compare equal. */
    private static String normStatus(String s) {
        return (ExtractionResult.STATUS_UNMAPPED_INDEX.equals(s) || ExtractionResult.STATUS_NO_DATA.equals(s))
                ? "<no-valid-Z>" : s;
    }
}
