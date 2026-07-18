package ztracker.core.topoj;

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

/**
 * Runnable walkthrough (NOT a test — has a {@code main}, no {@code @Test}, so Surefire ignores
 * it but it stays compiled against the real extractors) that prints, side by side, what the
 * indexed extractor (Tool 2, {@link ZExtractor}) and the direct-Z / TopoJ extractor
 * (Tool 3, {@link TopoJExtractor}) produce from <b>equivalent inputs</b> — an index grid + JSON
 * map on one side, and the float grid those two resolve to on the other.
 *
 * <p>It exists so the "same protocol, only the Z source differs" claim can be <b>eyeballed</b>:
 * the printed table shows Z (and its validity) matching row-for-row, with the only differences
 * being Tool 2's {@code numUnmapped} bookkeeping (always 0 in Tool 3) and the label for a
 * sample that resolves to no Z ({@code UNMAPPED_INDEX} vs {@code NO_DATA}).
 *
 * <p><b>This demo makes no assertions.</b> The actual pass/fail guarantee lives in
 * {@code ExtractorEquivalenceTest} (run via {@code mvn test}).
 *
 * <p><b>Run it:</b> from your IDE (right-click → Run 'main'), or from the command line after
 * {@code mvn test-compile}:
 * <pre>
 *   java -cp "target/classes;target/test-classes" ztracker.core.topoj.ExtractorEquivalenceDemo   (Windows)
 *   java -cp  target/classes:target/test-classes  ztracker.core.topoj.ExtractorEquivalenceDemo   (macOS/Linux)
 * </pre>
 */
public class ExtractorEquivalenceDemo {

    private static final int OFFSET = 1; // csvFrame + 1 = tiff frame

    // A 5x5 index grid; index 99 (row 2, col 4) is intentionally NOT in the mapping.
    private static final int[][] GRID = {
        { 0,  1,  2,  3,  4},
        { 5,  6,  7,  8,  9},
        {10, 11, 12, 13, 99},
        {15, 16, 17, 18, 19},
        {20, 21, 22, 23, 24},
    };

    public static void main(String[] args) {
        line('=');
        System.out.println("  EXTRACTOR EQUIVALENCE — indexed (Tool 2) vs. direct-Z / TopoJ (Tool 3)");
        line('=');
        System.out.println();
        System.out.println("Both tools run the SAME pipeline (sample → aggregate → classify → export).");
        System.out.println("The ONLY difference is how a sampled pixel becomes a Z:");
        System.out.println("  Tool 2: pixel is an INDEX  → look it up in the JSON index→Z map");
        System.out.println("  Tool 3: pixel is the Z (µm) → use it directly (no map)");
        System.out.println();
        System.out.println("To compare them fairly we build ONE index grid + map, then derive the");
        System.out.println("float grid Tool 3 would see: floatPixel = map.get(index), or NaN if unmapped.");
        System.out.println();

        Map<Integer, Double> map = mapping();

        // ── Show the inputs ──────────────────────────────────────────────────────
        System.out.println("index grid (what Tool 2 samples):");
        printIntGrid(GRID);
        System.out.println();
        System.out.println("index → Z mapping (µm):  " + prettyMapping(map));
        System.out.println("   (index 99 is deliberately absent → 'unmapped')");
        System.out.println();
        System.out.println("float grid (what Tool 3 samples) = each index resolved to its Z, NaN if unmapped:");
        printFloatGrid(toFloatGrid(GRID, map));
        System.out.println();

        // ── The detections ───────────────────────────────────────────────────────
        TrackData track = track();
        System.out.println("detections (offset " + signed(OFFSET) + " → tiff frame; frames 1,2 exist):");
        System.out.println("  d0 (2.0, 2.0) f0  — interior, all mapped");
        System.out.println("  d1 (4.0, 2.0) f0  — sits on the unmapped index 99");
        System.out.println("  d2 (50 , 50 ) f0  — out of bounds");
        System.out.println("  d3 (2.0, 2.0) f5  — frame 6 missing");
        System.out.println("  d4 (NaN, 2.0) f0  — invalid X/Y");
        System.out.println();

        // ── Run both, for a few representative combos ────────────────────────────
        LoadedStack indexed = indexedStack();
        LoadedFloatStack floats = floatStack(map);

        runCombo(track, indexed, floats, map,
                ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN, ZSampler.PixelConvention.PIXEL_CORNER);
        runCombo(track, indexed, floats, map,
                ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN, ZSampler.PixelConvention.PIXEL_CORNER);
        runCombo(track, indexed, floats, map,
                ZSampler.Method.RADIUS, ZAggregator.Method.MEAN, ZSampler.PixelConvention.PIXEL_CENTER);

        line('=');
        System.out.println("  TAKEAWAY");
        line('=');
        System.out.println("Z matches row-for-row in every combo. The only columns that differ are:");
        System.out.println("  • numUnmapped — Tool 2 counts sampled indices with no map entry;");
        System.out.println("                  Tool 3 has no map, so it is always 0.");
        System.out.println("  • status for a no-Z sample — Tool 2 'unmapped index' vs Tool 3 'no data'");
        System.out.println("                  (same meaning: pixels were sampled but none gave a Z).");
        System.out.println();
        System.out.println("This demo asserts nothing — see ExtractorEquivalenceTest for the real check.");
        System.out.println();
    }

    // ── Run one (sampling, aggregation, convention) combo and print the table ─────

    private static void runCombo(TrackData track, LoadedStack indexed, LoadedFloatStack floats,
                                 Map<Integer, Double> map,
                                 ZSampler.Method m, ZAggregator.Method a, ZSampler.PixelConvention c) {
        ExtractionResult r2 = ZExtractor.extract(track, indexed, map, OFFSET, m, a, c);
        ExtractionResult r3 = TopoJExtractor.extract(track, floats, OFFSET, m, a, c);

        line('-');
        System.out.printf("combo: %s + %s + %s%n", m.label, a.label, c.label);
        line('-');
        System.out.printf("%-4s | %-26s | %-26s | %s%n",
                "det", "Tool 2 (indexed+JSON)", "Tool 3 (TopoJ direct-Z)", "Z match?");
        System.out.printf("%-4s | %-9s %-6s %-9s | %-9s %-6s %-9s |%n",
                "", "z(µm)", "nUnmap", "status", "z(µm)", "nUnmap", "status");
        for (int i = 0; i < r2.size(); i++) {
            boolean zMatch = (Double.isNaN(r2.z[i]) && Double.isNaN(r3.z[i]))
                    || Math.abs(r2.z[i] - r3.z[i]) < 1e-9;
            System.out.printf("d%-3d | %-9s %-6d %-9s | %-9s %-6d %-9s | %s%n",
                    i,
                    fmtZ(r2.z[i]), r2.numUnmapped[i], shortStatus(r2.sampleStatus[i]),
                    fmtZ(r3.z[i]), r3.numUnmapped[i], shortStatus(r3.sampleStatus[i]),
                    zMatch ? "✓" : "✗ MISMATCH");
        }
        System.out.printf("counts: missingFrame=%d/%d  outOfBounds=%d/%d  invalidXY=%d/%d  (Tool2/Tool3)%n",
                r2.missingFrameCount, r3.missingFrameCount,
                r2.outOfBoundsCount, r3.outOfBoundsCount,
                r2.invalidXYCount, r3.invalidXYCount);
        System.out.println();
    }

    // ── Fixture builders ─────────────────────────────────────────────────────────

    private static Map<Integer, Double> mapping() {
        Map<Integer, Double> m = new HashMap<>();
        for (int i = 0; i <= 24; i++) m.put(i, i * 0.5 - 3.0);
        return m;
    }

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
        m.put(1, 0); m.put(2, 1);
        return m;
    }

    private static List<Integer> frameNumbers() {
        return new ArrayList<>(Arrays.asList(1, 2));
    }

    private static TrackData track() {
        return new TrackData(
                new double[]{2.0, 4.0, 50.0, 2.0, Double.NaN},
                new double[]{2.0, 2.0, 50.0, 2.0, 2.0},
                new int[]   {0,   0,   0,    5,   0},
                new double[]{1.0, 1.0, 1.0,  1.0, 1.0},
                new String[]{"1", "1", "1",  "1", "1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
    }

    // ── Formatting helpers ───────────────────────────────────────────────────────

    private static String fmtZ(double z)  { return Double.isNaN(z) ? "NaN" : String.format("%.3f", z); }
    private static String signed(int n)   { return (n >= 0 ? "+" : "") + n; }

    private static String shortStatus(String s) {
        if (ExtractionResult.STATUS_OK.equals(s))             return "OK";
        if (ExtractionResult.STATUS_MISSING_FRAME.equals(s))  return "missFrame";
        if (ExtractionResult.STATUS_OUT_OF_BOUNDS.equals(s))  return "oob";
        if (ExtractionResult.STATUS_INVALID_XY.equals(s))     return "invalidXY";
        if (ExtractionResult.STATUS_UNMAPPED_INDEX.equals(s)) return "unmapped";
        if (ExtractionResult.STATUS_NO_DATA.equals(s))        return "noData";
        return s;
    }

    private static String prettyMapping(Map<Integer, Double> map) {
        StringBuilder sb = new StringBuilder("{0:").append(String.format("%.1f", map.get(0)));
        sb.append(", 1:").append(String.format("%.1f", map.get(1)));
        sb.append(", … , 24:").append(String.format("%.1f", map.get(24))).append("}");
        return sb.toString();
    }

    private static void printIntGrid(int[][] g) {
        for (int[] row : g) {
            StringBuilder sb = new StringBuilder("   ");
            for (int v : row) sb.append(String.format("%5d", v));
            System.out.println(sb);
        }
    }

    private static void printFloatGrid(float[][] g) {
        for (float[] row : g) {
            StringBuilder sb = new StringBuilder("   ");
            for (float v : row) sb.append(String.format("%8s", Float.isNaN(v) ? "NaN" : String.format("%.1f", v)));
            System.out.println(sb);
        }
    }

    private static void line(char ch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 78; i++) sb.append(ch);
        System.out.println(sb);
    }
}
