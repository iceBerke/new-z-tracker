package ztracker.core;

import ztracker.export.TrackExportManager;
import ztracker.export.TrackExportManager.ExportConfig;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Human-readable walkthrough of the step-5 sampling/aggregation logic, including the
 * new "All methods" cross-product feature. NOT a test — it has a {@code main} and no
 * {@code @Test} methods, so Surefire ignores it, but it stays compiled against the real
 * {@link ZSampler} / {@link ZAggregator} / {@link ZExtractor} / {@link TrackExportManager}
 * on every {@code mvn test}.
 *
 * <p>It builds one small synthetic TIFF frame with an intentional outlier pixel, then:
 * <ol>
 *   <li>Runs each {@link ZSampler.Method} on the same detection and prints exactly which
 *       raw pixel indices were picked up (so RADIUS's disk vs FOUR_NEIGHBOR's corners vs
 *       SINGLE_PIXEL's nearest pixel can be eyeballed against the printed grid).</li>
 *   <li>Aggregates those samples with MEDIAN vs MEAN, showing how MEDIAN shrugs off the
 *       planted outlier while MEAN does not.</li>
 *   <li>Runs {@link ZExtractor#extractAll} over the 3x2 sampling x aggregation cross
 *       product (what Step 5's "All" / "All" selection triggers), collapsed to 5 combos
 *       — Single Pixel samples exactly one pixel, so Median and Mean of that one value
 *       are identical and it only runs once instead of once per aggregation method —
 *       and prints one row per combination actually run.</li>
 *   <li>Places a detection right at the image edge — still in-bounds for SINGLE_PIXEL,
 *       but far enough out that RADIUS's disk and FOUR_NEIGHBOR's corners get clipped by
 *       the frame boundary — and prints the reduced sample counts.</li>
 *   <li>Places a detection far outside the frame entirely (frame exists, position doesn't)
 *       and shows that {@link ExtractionResult#outOfBoundsCount} is incremented instead of
 *       {@link ExtractionResult#missingFrameCount} — the two are genuinely different root
 *       causes (bad detection coordinates vs. a frame-offset problem) and are now reported
 *       to the user separately rather than both being logged as "missing frames".</li>
 *   <li>Actually exports two of those combinations via {@link TrackExportManager} into a
 *       temp directory and prints the resulting file tree, so the
 *       {@code outputDir/<sampling>/<aggregation>/...} subfolder layout can be verified
 *       by eye — including {@code TrackExportManager}'s per-track report line, which shows
 *       a track with one NaN-Z detection keeping its full 2D export while only that one
 *       point is dropped from 3D (the track itself is never discarded for a single bad
 *       point), and prints the full contents of the {@code export_report.txt} file written
 *       alongside the .npy output (the same report, uncapped, since the Fiji Log view caps
 *       at 50 tracks).</li>
 *   <li>Builds a track with an invalid-X/Y detection (unparseable coordinate — no position at
 *       all) alongside a separate invalid-Z detection (missing TIFF frame), and exports it to
 *       show the asymmetry: the invalid-X/Y point is dropped from <b>both</b> 2D and 3D (no
 *       position, can't be placed in either), while the invalid-Z point is dropped from 3D
 *       only — printed straight from {@code export_report.txt} so the two distinct drop
 *       reasons can be seen side by side in the same per-track line.</li>
 *   <li>Plants a distinctive marker pixel at column 0 and runs {@link ZExtractor#extract} on a
 *       detection with a NaN X, proving {@code ZSampler} is never even called for it (rather
 *       than {@code Math.round(Double.NaN) == 0} silently treating it as {@code x=0} and
 *       sampling the marker) — {@link ExtractionResult#invalidXYCount} and
 *       {@link ExtractionResult#STATUS_INVALID_XY} confirm the short-circuit.</li>
 *   <li>Exports a fully-valid track with {@code .npy} unchecked but Results Table CSV checked,
 *       and prints {@code export_report.txt} to show the report's new trailing segment — the
 *       2D/3D verdict reads "npy export off" either way, but the added segment makes clear the
 *       track's points still landed in the CSV rather than reading as a total export failure.</li>
 *   <li>Plants a pixel value that has no entry in the Z JSON mapping and contrasts two
 *       detections against it: one that samples <b>only</b> the unmapped pixel (every sample
 *       lacks a mapping, so aggregation has nothing left and the detection fails with
 *       {@link ExtractionResult#STATUS_UNMAPPED_INDEX} — a third distinct NaN-Z cause, separate
 *       from a missing TIFF frame or an out-of-bounds position) versus one that samples a mix
 *       of the unmapped pixel <b>and</b> mapped neighbors (partial unmapped still yields a
 *       valid aggregate over the remaining samples, per {@link ZAggregator}'s NaN-filtering, so
 *       the detection succeeds with {@code numUnmapped > 0} but status {@code STATUS_OK}).</li>
 * </ol>
 *
 * <p>Run it manually (from the project root, after {@code mvn test-compile}):
 * <pre>
 *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.core.Step5MethodsDemo
 * </pre>
 *
 * <p>To save the full output to a file instead of (or in addition to) the console, redirect
 * stdout to {@code Step5MethodsDemo_output.txt} alongside this source file. Java's default
 * stdout encoding on Windows is not UTF-8, which mangles the ✓/✗/── characters in
 * {@code TrackExportManager}'s log lines when redirected — pass
 * {@code -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8} to keep them intact:
 * <pre>
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.core.Step5MethodsDemo &gt; src/test/java/ztracker/core/Step5MethodsDemo_output.txt 2&gt;&amp;1
 * </pre>
 */
public class Step5MethodsDemo {

    // 5x5 frame, value == row*5+col, except (3,3) is planted as an outlier (100)
    // instead of its natural value 18 — visible in FOUR_NEIGHBOR's corner sampling.
    private static final int[][] FRAME = {
        { 0,  1,  2,  3,  4},
        { 5,  6,  7,  8,  9},
        {10, 11, 12, 13, 14},
        {15, 16, 17, 100, 19},
        {20, 21, 22, 23, 24}
    };

    private static LoadedStack stack() {
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        return new LoadedStack(new int[][][]{FRAME}, frameToIdx,
                new ArrayList<>(Collections.singletonList(0)), 5, 5);
    }

    // Z = index * 0.5 for every pixel value that appears in FRAME.
    private static Map<Integer, Double> zMapping() {
        Map<Integer, Double> m = new HashMap<>();
        for (int[] row : FRAME) for (int v : row) m.put(v, v * 0.5);
        return m;
    }

    private static TrackData track() {
        // Detection "A" at frame 0, near the center of FRAME (x=2.4, y=2.4).
        // Detection "A" again at frame 99, which has no matching TIFF (missing-frame case).
        return new TrackData(
                new double[]{2.4, 2.4},
                new double[]{2.4, 2.4},
                new int[]{0, 99},
                new double[]{1.0, 1.0},
                new String[]{"A", "A"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
    }

    private static void printGrid() {
        System.out.println("  TIFF frame (value = row*5+col, except the planted outlier):");
        for (int y = 0; y < FRAME.length; y++) {
            StringBuilder sb = new StringBuilder("    ");
            for (int x = 0; x < FRAME[y].length; x++) {
                sb.append(String.format("%4d", FRAME[y][x]));
            }
            System.out.println(sb);
        }
        System.out.println("  (outlier planted at row=3,col=3 -> value 100 instead of 18)");
    }

    private static void printZMapping(Map<Integer, Double> zMap) {
        System.out.println("\n  Z mapping (this is the ztracker.io.ZMappingLoader JSON, index -> Z"
                + " in micrometers -- what a real run loads from the mapping file the user picks in"
                + " Step 1): for this demo it's synthesized as Z = index * 0.5, so every pixel value"
                + " above maps 1:1 to a Z below. Real mappings come from an arbitrary JSON file and"
                + " aren't necessarily linear -- this formula is just how THIS demo's fake data was built.");
        List<Integer> indices = new ArrayList<>(zMap.keySet());
        Collections.sort(indices);
        StringBuilder idxLine = new StringBuilder("    index:");
        StringBuilder zLine   = new StringBuilder("    Z:    ");
        for (int idx : indices) {
            idxLine.append(String.format("%6d", idx));
            zLine.append(String.format("%6.1f", zMap.get(idx)));
        }
        System.out.println(idxLine);
        System.out.println(zLine);
        System.out.println("  (a pixel value with NO row here -- e.g. a stray index not in the JSON"
                + " -- is what section 10 below calls an \"unmapped index\")");
    }

    private static void samplingComparison(LoadedStack s, Map<Integer, Double> zMap) {
        System.out.println("\n--- 1) Sampling methods on the same detection (x=2.4, y=2.4, radius=1.0) ---");
        for (ZSampler.Method method : ZSampler.Method.values()) {
            double[] indices = ZSampler.sample(s, 2.4, 2.4, 0, 0, 1.0, method);
            double[] zValues = Arrays.stream(indices)
                    .map(idx -> zMap.get((int) Math.round(idx)))
                    .toArray();
            System.out.printf("  %-14s indices=%-24s z=%s%n",
                    method.label, Arrays.toString(indices), Arrays.toString(zValues));
        }
    }

    private static void aggregationComparison(LoadedStack s, Map<Integer, Double> zMap) {
        System.out.println("\n--- 2) Aggregation on FOUR_NEIGHBOR's samples (includes the outlier) ---");
        double[] indices = ZSampler.sample(s, 2.4, 2.4, 0, 0, 1.0, ZSampler.Method.FOUR_NEIGHBOR);
        double[] zValues = Arrays.stream(indices)
                .map(idx -> zMap.get((int) Math.round(idx)))
                .toArray();
        System.out.println("  z samples = " + Arrays.toString(zValues));
        for (ZAggregator.Method method : ZAggregator.Method.values()) {
            double result = ZAggregator.aggregate(zValues, method);
            System.out.printf("  %-8s -> %6.2f%n", method.label, result);
        }
        System.out.println("  (MEDIAN should sit near the non-outlier cluster; MEAN gets pulled toward 50)");
    }

    private static void extractAllComparison(TrackData t, LoadedStack s, Map<Integer, Double> zMap) {
        System.out.println("\n--- 3) ZExtractor.extractAll: 3x2 sampling x aggregation cross product, "
                + "collapsed to 5 combos ---");
        List<ZExtractor.MethodCombo> combos = ZExtractor.extractAll(
                t, s, zMap, 0,
                Arrays.asList(ZSampler.Method.values()),
                Arrays.asList(ZAggregator.Method.values()));

        System.out.printf("  %-14s %-8s %8s %8s %10s%n",
                "sampling", "aggreg.", "z[det0]", "z[det1]", "numSamples");
        for (ZExtractor.MethodCombo combo : combos) {
            ExtractionResult r = combo.result;
            System.out.printf("  %-14s %-8s %8.2f %8s %10d%n",
                    combo.sampling.label, combo.aggregation.label,
                    r.z[0], Double.isNaN(r.z[1]) ? "NaN" : String.valueOf(r.z[1]),
                    r.numSamples[0]);
        }
        System.out.println("  (det1 is frame 99, which has no TIFF -> always NaN / 0 samples,"
                + " independent of method)");
        System.out.println("  (Single Pixel only appears once above, not once per aggregation method: "
                + "it samples exactly one pixel, so Median and Mean of that one value are identical -- "
                + "running it twice would just be duplicate work. See ZExtractor.extractAll and "
                + "ZExtractor.resolveComboOutputDir, which also collapses its export folder the same way.)");
    }

    private static void edgeOfImageComparison(LoadedStack s, Map<Integer, Double> zMap) {
        System.out.println("\n--- 4) Edge-of-image detection: x=4.4, y=2.4 (col 4 is the last valid "
                + "column in a 5-wide frame) ---");
        System.out.println("  SINGLE_PIXEL's nearest pixel (4,2) is still in-bounds, so it samples "
                + "normally. RADIUS's disk and FOUR_NEIGHBOR's corners both reach past column 4 into "
                + "column 5, which doesn't exist — those out-of-frame samples are silently dropped, "
                + "not clamped, so their sample counts shrink instead of erroring.");
        for (ZSampler.Method method : ZSampler.Method.values()) {
            double[] indices = ZSampler.sample(s, 4.4, 2.4, 0, 0, 1.0, method);
            double[] zValues = Arrays.stream(indices)
                    .map(idx -> zMap.get((int) Math.round(idx)))
                    .toArray();
            System.out.printf("  %-14s samples=%d  indices=%-20s z=%s%n",
                    method.label, indices.length, Arrays.toString(indices), Arrays.toString(zValues));
        }
    }

    private static void outOfBoundsVsMissingFrameComparison(LoadedStack s, Map<Integer, Double> zMap) {
        System.out.println("\n--- 5) Out-of-bounds position vs. genuinely missing frame ---");
        System.out.println("  Two failure detections that both end up with 0 samples / NaN Z, but for "
                + "different reasons — ZExtractor now counts and reports them separately instead of "
                + "lumping both into \"missing frames\":");

        // Detection A: frame 0 exists, but (50, 50) is nowhere near the 5x5 grid.
        TrackData outOfBoundsTrack = new TrackData(
                new double[]{50.0}, new double[]{50.0}, new int[]{0},
                new double[]{1.0}, new String[]{"OOB"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult oobResult = ZExtractor.extract(
                outOfBoundsTrack, s, zMap, 0, ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN);
        System.out.printf("  Detection A: frame=0 (exists), position=(50,50) (way off-grid)%n");
        System.out.printf("     -> z=%s | missingFrameCount=%d | outOfBoundsCount=%d%n",
                oobResult.z[0], oobResult.missingFrameCount, oobResult.outOfBoundsCount);

        // Detection B: position is fine, but frame 99 was never loaded from the TIFF folder.
        TrackData missingFrameTrack = new TrackData(
                new double[]{2.0}, new double[]{2.0}, new int[]{99},
                new double[]{1.0}, new String[]{"MF"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult mfResult = ZExtractor.extract(
                missingFrameTrack, s, zMap, 0, ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN);
        System.out.printf("  Detection B: frame=99 (no such TIFF), position=(2,2) (fine)%n");
        System.out.printf("     -> z=%s | missingFrameCount=%d | outOfBoundsCount=%d%n",
                mfResult.z[0], mfResult.missingFrameCount, mfResult.outOfBoundsCount);

        System.out.println("  Same symptom (NaN, 0 samples), different root cause and different fix:");
        System.out.println("    A -> bad detection X/Y (or wrong radius) — check the tracking CSV");
        System.out.println("    B -> frame-offset / TIFF folder problem — check Step 4's alignment");
    }

    private static void exportFolderDemo(TrackData t, LoadedStack s, Map<Integer, Double> zMap) throws IOException {
        System.out.println("\n--- 6) Exporting two combinations to see the folder layout ---");
        System.out.println("  Track \"A\" has 2 detections: frame 0 (valid) and frame 99 (missing TIFF,"
                + " NaN Z). TrackExportManager now keeps the track and just drops that one bad point"
                + " from the 3D export -- watch the per-track report line below: 2D still writes"
                + " both points, 3D writes only the 1 valid one.");
        Path tmp = Files.createTempDirectory("ztracker-step5-demo");
        ExportConfig config = new ExportConfig(true, false, false);

        ExtractionResult radiusMedian = ZExtractor.extract(
                t, s, zMap, 0, ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN);
        ExtractionResult pixelMean = ZExtractor.extract(
                t, s, zMap, 0, ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEAN);

        TrackExportManager.export(t, radiusMedian, config,
                tmp.resolve("radius").resolve("median"), "");
        TrackExportManager.export(t, pixelMean, config,
                tmp.resolve("single_pixel").resolve("mean"), "");

        System.out.println("  Exported under: " + tmp);
        try (Stream<Path> walk = Files.walk(tmp)) {
            walk.filter(Files::isRegularFile)
                    .map(tmp::relativize)
                    .sorted()
                    .forEach(p -> System.out.println("    " + p));
        }

        System.out.println("\n  Contents of radius/median/export_report.txt (full, uncapped -- the"
                + " Fiji Log view caps at 50 tracks, this file never does):");
        for (String line : Files.readAllLines(tmp.resolve("radius").resolve("median")
                .resolve("export_report.txt"))) {
            System.out.println("    " + line);
        }

        deleteRecursively(tmp);
        System.out.println("  (temp directory cleaned up after printing)");
    }

    private static void invalidXYVsInvalidZComparison() throws IOException {
        System.out.println("\n--- 7) Invalid X/Y vs. invalid Z: dropped from different exports ---");
        System.out.println("  Track \"B\" has 3 detections: frame 0 (fully valid), frame 1 (X is NaN --"
                + " e.g. an unparseable coordinate from the CSV), frame 2 (valid X/Y but a NaN Z from a"
                + " missing TIFF frame). A missing position can't be placed in EITHER export, so frame 1"
                + " is dropped from both 2D and 3D; frame 2's bad Z only breaks 3D, so it survives in 2D.");

        TrackData track = new TrackData(
                new double[]{1.0, Double.NaN, 3.0},
                new double[]{1.0, 2.0, 3.0},
                new int[]{0, 1, 2},
                new double[]{1.0, 1.0, 1.0},
                new String[]{"B", "B", "B"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                new double[]{5.0, 10.0, Double.NaN},
                new double[]{0.1, 0.1, Double.NaN},
                new int[]{5, 5, 0},
                new int[]{0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK,
                        ExtractionResult.STATUS_MISSING_FRAME},
                "Radius-based", "Median", 1, 0, 0);

        Path tmp = Files.createTempDirectory("ztracker-step5-demo-xy");
        ExportConfig config = new ExportConfig(true, false, false);
        TrackExportManager.export(track, result, config, tmp, "");

        System.out.println("  Contents of export_report.txt:");
        for (String line : Files.readAllLines(tmp.resolve("export_report.txt"))) {
            System.out.println("    " + line);
        }
        deleteRecursively(tmp);
        System.out.println("  (temp directory cleaned up after printing)");
    }

    private static void nanXIsNeverSampledComparison() {
        System.out.println("\n--- 8) A NaN X/Y is never sampled -- not silently treated as pixel 0 ---");
        System.out.println("  Math.round(Double.NaN) == 0 in Java. Without an explicit check, a NaN X"
                + " would be silently rounded to 0 and a REAL pixel at column 0 would get sampled,"
                + " producing a bogus \"valid\" Z instead of failing. A distinctive marker value (999,"
                + " mapped to Z=42.0) is planted at column 0 to prove it's never read.");

        int[][] frame = {
            { 1,  1,  1},
            {999,  5,  1},   // column 0 -- must never be sampled, even though round(NaN)==0
            { 1,  1,  1}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedStack s = new LoadedStack(new int[][][]{frame}, frameToIdx,
                new ArrayList<>(Collections.singletonList(0)), 3, 3);

        Map<Integer, Double> zMap = new HashMap<>();
        zMap.put(999, 42.0); // if the marker pixel were ever sampled, z would come out as 42.0
        zMap.put(5, 12.5);

        TrackData track = new TrackData(
                new double[]{Double.NaN}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"C"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);

        ExtractionResult result = ZExtractor.extract(
                track, s, zMap, 0, ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN);

        System.out.printf("  Detection: x=NaN, y=1.0, frame=0 (exists)%n");
        System.out.printf("     -> z=%s (NOT 42.0) | numSamples=%d | invalidXYCount=%d | sampleStatus=\"%s\"%n",
                result.z[0], result.numSamples[0], result.invalidXYCount, result.sampleStatus[0]);
        System.out.println("  (numSamples=0 proves ZSampler was never even called for this detection)");
    }

    private static void reportNotesOtherFormatsComparison() throws IOException {
        System.out.println("\n--- 9) Per-track report notes Results Table/ROI, even with .npy off ---");
        System.out.println("  Track \"D\" has 3 fully-valid detections. Exporting with .npy UNCHECKED but"
                + " Results Table CSV checked: the 2D/3D verdict is specifically about .npy, so both read"
                + " \"npy export off\" -- but the report now adds a trailing segment so this doesn't read"
                + " as a total export failure when the CSV actually succeeded.");

        TrackData track = new TrackData(
                new double[]{1.0, 2.0, 3.0},
                new double[]{1.0, 2.0, 3.0},
                new int[]{0, 1, 2},
                new double[]{1.0, 1.0, 1.0},
                new String[]{"D", "D", "D"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                new double[]{5.0, 6.0, 7.0},
                new double[]{0.1, 0.1, 0.1},
                new int[]{5, 5, 5},
                new int[]{0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 0, 0, 0);

        Path tmp = Files.createTempDirectory("ztracker-step5-demo-formats");
        // npy OFF, Results Table CSV ON
        ExportConfig config = new ExportConfig(false, true, false);
        TrackExportManager.export(track, result, config, tmp, "");

        System.out.println("  Files written: ");
        try (Stream<Path> walk = Files.walk(tmp)) {
            walk.filter(Files::isRegularFile)
                    .map(tmp::relativize)
                    .sorted()
                    .forEach(p -> System.out.println("    " + p));
        }
        System.out.println("  Contents of export_report.txt:");
        for (String line : Files.readAllLines(tmp.resolve("export_report.txt"))) {
            System.out.println("    " + line);
        }
        deleteRecursively(tmp);
        System.out.println("  (temp directory cleaned up after printing)");
    }

    private static void unmappedIndexComparison() {
        System.out.println("\n--- 10) Unmapped pixel index: a third distinct NaN-Z cause ---");
        System.out.println("  Pixel value 999 is planted with NO entry in the Z mapping (simulating a"
                + " stray index the JSON mapping doesn't cover). This is different from a missing TIFF"
                + " frame or an out-of-bounds position: the frame exists and the position is in-bounds,"
                + " so sampling succeeds and numSamples > 0 -- it's the index-to-Z lookup that fails.");

        int[][] frame = {
            {7,   7,   7},
            {7, 999,   7},   // center pixel has no Z-mapping entry
            {7,   7,   7}
        };
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedStack s = new LoadedStack(new int[][][]{frame}, frameToIdx,
                new ArrayList<>(Collections.singletonList(0)), 3, 3);

        Map<Integer, Double> zMap = new HashMap<>();
        zMap.put(7, 1.0); // 999 is deliberately left out of the mapping

        // Case A: SINGLE_PIXEL samples only the unmapped center pixel -- nothing left to
        // aggregate once the lone sample is filtered out.
        TrackData single = new TrackData(
                new double[]{1.0}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"E1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult singleResult = ZExtractor.extract(
                single, s, zMap, 0, ZSampler.Method.SINGLE_PIXEL, ZAggregator.Method.MEDIAN);
        System.out.printf("  Case A (SINGLE_PIXEL, only the unmapped pixel sampled):%n");
        System.out.printf("     -> z=%s | numSamples=%d | numUnmapped=%d | sampleStatus=\"%s\"%n",
                singleResult.z[0], singleResult.numSamples[0], singleResult.numUnmapped[0],
                singleResult.sampleStatus[0]);

        // Case B: RADIUS samples the unmapped center plus mapped neighbors -- the aggregate
        // still succeeds over the remaining valid samples.
        TrackData radius = new TrackData(
                new double[]{1.0}, new double[]{1.0}, new int[]{0},
                new double[]{1.0}, new String[]{"E2"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult radiusResult = ZExtractor.extract(
                radius, s, zMap, 0, ZSampler.Method.RADIUS, ZAggregator.Method.MEDIAN);
        System.out.printf("  Case B (RADIUS, unmapped pixel + mapped neighbors sampled):%n");
        System.out.printf("     -> z=%s | numSamples=%d | numUnmapped=%d | sampleStatus=\"%s\"%n",
                radiusResult.z[0], radiusResult.numSamples[0], radiusResult.numUnmapped[0],
                radiusResult.sampleStatus[0]);
        System.out.println("  (Case A fails: the ONLY sample was unmapped, nothing left to aggregate."
                + " Case B succeeds: partial unmapped still leaves valid neighbors for the aggregate.)");
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        LoadedStack s = stack();
        Map<Integer, Double> zMap = zMapping();
        TrackData t = track();

        System.out.println("============================================================");
        System.out.println("Step-5 demo: sampling methods, aggregation methods, \"All\"");
        System.out.println("============================================================");
        printGrid();
        printZMapping(zMap);
        samplingComparison(s, zMap);
        aggregationComparison(s, zMap);
        extractAllComparison(t, s, zMap);
        edgeOfImageComparison(s, zMap);
        outOfBoundsVsMissingFrameComparison(s, zMap);
        exportFolderDemo(t, s, zMap);
        invalidXYVsInvalidZComparison();
        nanXIsNeverSampledComparison();
        reportNotesOtherFormatsComparison();
        unmappedIndexComparison();
    }
}
