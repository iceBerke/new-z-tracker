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
 *   <li>Runs {@link ZExtractor#extractAll} over the full 3x2 sampling x aggregation
 *       cross product (what Step 5's "All" / "All" selection triggers) and prints one
 *       row per combination.</li>
 *   <li>Actually exports two of those combinations via {@link TrackExportManager} into a
 *       temp directory and prints the resulting file tree, so the
 *       {@code outputDir/<sampling>/<aggregation>/...} subfolder layout can be verified
 *       by eye, not just by assertion.</li>
 * </ol>
 *
 * <p>Run it manually (from the project root, after {@code mvn test-compile}):
 * <pre>
 *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.core.Step5MethodsDemo
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
        System.out.println("\n--- 3) ZExtractor.extractAll: full 3x2 sampling x aggregation cross product ---");
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
    }

    private static void exportFolderDemo(TrackData t, LoadedStack s, Map<Integer, Double> zMap) throws IOException {
        System.out.println("\n--- 4) Exporting two combinations to see the folder layout ---");
        Path tmp = Files.createTempDirectory("ztracker-step5-demo");
        ExportConfig config = new ExportConfig(1, null, true, false, false); // minTrackLength=1 for this tiny demo track

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

        deleteRecursively(tmp);
        System.out.println("  (temp directory cleaned up after printing)");
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
        samplingComparison(s, zMap);
        aggregationComparison(s, zMap);
        extractAllComparison(t, s, zMap);
        exportFolderDemo(t, s, zMap);
    }
}
