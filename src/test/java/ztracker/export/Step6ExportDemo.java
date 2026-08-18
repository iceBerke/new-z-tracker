package ztracker.export;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.process.FloatPolygon;
import ztracker.core.extractor.ZSampler;
import ztracker.export.TrackExportManager.ExportConfig;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Human-readable walkthrough of the step-6 export machinery: the hand-rolled {@code .npy}
 * binary format ({@link NpyExporter}) and the guarantee — backed by
 * {@code NpyExporterTest}, {@code FijiPointsExporterTest}, and
 * {@code TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat}
 * — that a detection's X/Y pixel coordinates come out identical to how they went in, no matter
 * which export format(s) Step 6 was told to write. NOT a test — it has a {@code main} and no
 * {@code @Test} methods, so Surefire ignores it, but it stays compiled against the real
 * {@link NpyExporter} / {@link FijiPointsExporter} / {@link TrackExportManager} on every
 * {@code mvn test}.
 *
 * <p>Unlike {@link ztracker.core.Step5MethodsDemo}'s export section (which shows the per-track report and
 * multi-method folder layout), this demo focuses on two things that aren't shown elsewhere:
 * <ol>
 *   <li>The actual bytes of a {@code .npy} file — magic, version, header dict, the 64-byte
 *       alignment padding, and the little-endian float64 data — so the format CLAUDE.md
 *       promises to keep byte-compatible with {@code numpy.load()} can be seen directly rather
 *       than taken on faith.</li>
 *   <li>A single track's X/Y values traced through <em>all four</em> export outputs at once
 *       (2D {@code .npy}, 3D {@code .npy}, Results Table CSV, ROI {@code .zip}) side by side,
 *       making explicit that X/Y are the only coordinates that actually originate in the input
 *       CSV — Z is computed by {@link ztracker.core.extractor.ZExtractor}, not read from anywhere, so
 *       there's nothing for it to "round-trip" from.</li>
 * </ol>
 *
 * <p>Run it manually (from the project root, after {@code mvn test-compile}):
 * <pre>
 *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.export.Step6ExportDemo
 * </pre>
 *
 * <p>To save the full output to a file instead of (or in addition to) the console, redirect
 * stdout to {@code Step6ExportDemo_output.txt} alongside this source file. Java's default
 * stdout encoding on Windows is not UTF-8, which mangles the ✓/── characters in
 * {@code TrackExportManager}'s log lines when redirected — pass
 * {@code -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8} to keep them intact:
 * <pre>
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.export.Step6ExportDemo &gt; src/test/java/ztracker/export/Step6ExportDemo_output.txt 2&gt;&amp;1
 * </pre>
 */
public class Step6ExportDemo {

    // ── 1) Raw .npy byte anatomy ────────────────────────────────────────────────

    private static void npyByteAnatomy() throws IOException {
        System.out.println("\n--- 1) Anatomy of a .npy file written by NpyExporter ---");
        System.out.println("  CLAUDE.md requires this writer to stay byte-compatible with"
                + " numpy.load() -- printing the raw bytes here makes that contract visible"
                + " instead of just asserted in NpyExporterTest.");

        Path tmp = Files.createTempFile("ztracker-step6-demo", ".npy");
        double[][] data = {{1.5, -2.25, 100.0}, {0.0, 42.0, 7.0}};
        NpyExporter.write(data, tmp);

        byte[] bytes = Files.readAllBytes(tmp);
        System.out.printf("  magic bytes (hex): %02X %02X %02X %02X %02X %02X  (expect 93 4E 55 4D 50 59 = \\x93NUMPY)%n",
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
        System.out.printf("  version: major=%d minor=%d%n", bytes[6], bytes[7]);

        int headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
        String header = new String(bytes, 10, headerLen, StandardCharsets.US_ASCII);
        System.out.println("  header dict (" + headerLen + " bytes, includes padding + trailing '\\n'): "
                + header.replace("\n", "\\n"));

        int prefixLen = 6 + 2 + 2; // magic + version(2) + header_len(uint16)
        System.out.printf("  alignment check: (prefix %d + header %d) %% 64 = %d (must be 0)%n",
                prefixLen, headerLen, (prefixLen + headerLen) % 64);

        int dataStart = 10 + headerLen;
        double first = ByteBuffer.wrap(bytes, dataStart, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        System.out.printf("  first data value decoded back from little-endian bytes: %.2f (wrote %.2f)%n",
                first, data[0][0]);

        Files.delete(tmp);
    }

    // ── 2) Header padding stays 64-byte aligned across shapes ──────────────────

    private static void headerAlignmentAcrossShapes() throws IOException {
        System.out.println("\n--- 2) Header padding stays 64-byte aligned across the whole realistic shape range ---");
        System.out.println("  NpyExporter pads the header dict with spaces so magic+version+header_len+header"
                + " always lands on a 64-byte boundary. The header text's length depends only on how many"
                + " digits rows/cols print as, so its padded size can be computed without writing a real file"
                + " -- shown here from a tiny shape up to one far beyond anything this pipeline would ever"
                + " produce, confirming the fixed dict text dominates and the boundary never gets crossed in"
                + " practice. NpyExporterTest instead writes real (small) files for cols 1..5 and checks the"
                + " same alignment invariant on-disk, byte for byte.");

        int[][] shapes = {{1, 1}, {3, 4}, {1_000_000, 4}, {999_999_999, 999_999_999}};
        for (int[] shape : shapes) {
            int rows = shape[0], cols = shape[1];
            String headerStr = String.format(
                    "{'descr': '<f8', 'fortran_order': False, 'shape': (%d, %d), }", rows, cols);
            int prefixLen = 6 + 2 + 2; // magic + version(2) + header_len(uint16)
            int rawLen    = prefixLen + headerStr.length() + 1; // +1 for '\n'
            int headerLen = (((rawLen + 63) / 64) * 64) - prefixLen;
            System.out.printf("  shape=(%,d, %,d) -> header_len=%-4d total_prefix=%-4d (%%64 = %d)%n",
                    rows, cols, headerLen, prefixLen + headerLen, (prefixLen + headerLen) % 64);
        }
    }

    // ── 3) write2DTrack / write3DTrack column order ─────────────────────────────

    private static void trackColumnOrder(double[] x, double[] y, double[] z, int[] frame) throws IOException {
        System.out.println("\n--- 3) write2DTrack/write3DTrack column order: [X,Y,T] and [X,Y,Z,T] ---");
        System.out.println("  Same input track and synthetic Z printed above -- shown here written and"
                + " read back through NpyExporter's convenience builders, before section 4 runs the full"
                + " TrackExportManager pipeline on the same data.");

        Path p2d = Files.createTempFile("ztracker-step6-demo-2d", ".npy");
        Path p3d = Files.createTempFile("ztracker-step6-demo-3d", ".npy");
        NpyExporter.write2DTrack(x, y, frame, p2d);
        NpyExporter.write3DTrack(x, y, z, frame, p3d);

        double[][] rows2D = readNpy(p2d);
        double[][] rows3D = readNpy(p3d);

        System.out.println("  2D [X, Y, T]:");
        for (double[] row : rows2D) {
            System.out.printf("    %8.3f %8.3f %8.0f%n", row[0], row[1], row[2]);
        }
        System.out.println("  3D [X, Y, Z, T]:");
        for (double[] row : rows3D) {
            System.out.printf("    %8.3f %8.3f %8.3f %8.0f%n", row[0], row[1], row[2], row[3]);
        }

        Files.delete(p2d);
        Files.delete(p3d);
    }

    // ── 4) Full export across every format, then trace X/Y through all of them ─

    private static void crossFormatCoordinateIdentity(double[] x, double[] y, double[] z, int[] frame)
            throws IOException {
        System.out.println("\n--- 4) The input track above, exported to every format at once -- X/Y traced through each ---");
        System.out.println("  X/Y are the only coordinates that actually come from the input CSV; Z (printed"
                + " above as \"synthetic\") is a stand-in for a real ZExtractor output, not something read"
                + " from anywhere, so it has no \"input\" to match against -- only X/Y get the identity check"
                + " below.");
        System.out.println("  NOTE: this demo only PRINTS the comparison below -- it makes no assertions."
                + " The actual pass/fail check is in JUnit, run via `mvn test`:"
                + " NpyExporterTest, FijiPointsExporterTest, and"
                + " TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat"
                + " (which this section mirrors).");

        TrackData track = new TrackData(
                x, y, frame,
                new double[]{3.5, 3.5, 3.5},
                new String[]{"7", "7", "7"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                z, new double[]{0.0, 0.0, 0.0}, new int[]{1, 1, 1}, new int[]{0, 0, 0},
                new String[]{ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK},
                "Radius-based", "Median", ZSampler.PixelConvention.PIXEL_CENTER.label, 0, 0, 0);
        ExportConfig config = new ExportConfig(true, true, true); // npy + Results Table + ROI

        Path tmp = Files.createTempDirectory("ztracker-step6-demo-export");
        TrackExportManager.export(track, result, config, tmp, "");

        // The real path is a fresh system temp directory whose name carries a random suffix,
        // so printing it would make this demo's committed snapshot differ on every run and
        // hide real changes among the noise. Name what it is, not where it is.
        System.out.println("  Exported under: <temp>/ztracker-step6-demo-export* (path masked: it varies per run)");
        try (Stream<Path> walk = Files.walk(tmp)) {
            walk.filter(Files::isRegularFile)
                    .map(tmp::relativize)
                    .sorted()
                    .forEach(p -> System.out.println("    " + p));
        }

        double[][] rows2D = readNpy(tmp.resolve("tracks_2D").resolve("track_00007.npy"));
        double[][] rows3D = readNpy(tmp.resolve("tracks_3D").resolve("track_00007.npy"));
        String[] csvLines = Files.readAllLines(
                tmp.resolve("fiji").resolve("results_table.csv"), StandardCharsets.UTF_8)
                .toArray(new String[0]);
        Map<String, float[]> roiCoords = readRoiZipCoordinates(tmp.resolve("fiji").resolve("track_rois.zip"));

        boolean allMatch = true;

        System.out.printf("%n  %-6s %10s %10s %10s %10s %10s %10s%n",
                "point", "input.X", "2D.X", "3D.X", "csv.X", "roi.X", "MATCH?");
        for (int i = 0; i < 3; i++) {
            double csvX = Double.parseDouble(csvLines[i + 1].split(",")[2]);
            float roiX = roiCoords.get("7_f" + frame[i])[0];
            boolean match = x[i] == rows2D[i][0] && x[i] == rows3D[i][0]
                    && x[i] == csvX && (float) x[i] == roiX;
            allMatch &= match;
            System.out.printf("  %-6d %10.4f %10.4f %10.4f %10.4f %10.4f %10s%n",
                    i, x[i], rows2D[i][0], rows3D[i][0], csvX, roiX, match ? "MATCH" : "MISMATCH!!");
        }

        System.out.printf("%n  %-6s %10s %10s %10s %10s %10s %10s%n",
                "point", "input.Y", "2D.Y", "3D.Y", "csv.Y", "roi.Y", "MATCH?");
        for (int i = 0; i < 3; i++) {
            double csvY = Double.parseDouble(csvLines[i + 1].split(",")[3]);
            float roiY = roiCoords.get("7_f" + frame[i])[1];
            boolean match = y[i] == rows2D[i][1] && y[i] == rows3D[i][1]
                    && y[i] == csvY && (float) y[i] == roiY;
            allMatch &= match;
            System.out.printf("  %-6d %10.4f %10.4f %10.4f %10.4f %10.4f %10s%n",
                    i, y[i], rows2D[i][1], rows3D[i][1], csvY, roiY, match ? "MATCH" : "MISMATCH!!");
        }

        System.out.println();
        System.out.println(allMatch
                ? "  RESULT: X and Y match the original input identically in 2D npy, 3D npy, CSV, and ROI."
                : "  RESULT: MISMATCH DETECTED -- an export format diverged from the input X/Y (see rows above marked MISMATCH!!).");

        deleteRecursively(tmp);
        System.out.println("  (temp directory cleaned up after printing)");
    }

    // ── Shared readers (same technique as the export test suites, kept independent) ────────

    private static double[][] readNpy(Path npyFile) throws IOException {
        byte[] bytes = Files.readAllBytes(npyFile);
        int headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
        String header = new String(bytes, 10, headerLen, StandardCharsets.US_ASCII);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("'shape':\\s*\\((\\d+),\\s*(\\d+)").matcher(header);
        if (!m.find()) throw new IllegalStateException("no shape in npy header: " + header);
        int rows = Integer.parseInt(m.group(1));
        int cols = Integer.parseInt(m.group(2));

        int dataStart = 10 + headerLen;
        ByteBuffer buf = ByteBuffer.wrap(bytes, dataStart, bytes.length - dataStart)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        double[][] data = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) data[r][c] = buf.getDouble();
        }
        return data;
    }

    private static Map<String, float[]> readRoiZipCoordinates(Path zipPath) throws IOException {
        Map<String, float[]> result = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = zis.read(chunk)) != -1) buf.write(chunk, 0, n);

                Roi roi = new RoiDecoder(buf.toByteArray(), entry.getName()).getRoi();
                FloatPolygon poly = roi.getFloatPolygon();
                String name = entry.getName().replaceFirst("\\.roi$", "");
                result.put(name, new float[]{poly.xpoints[0], poly.ypoints[0]});
            }
        }
        return result;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        // The committed snapshot must not depend on the machine's decimal separator.
        Locale.setDefault(Locale.ROOT);

        System.out.println("============================================================");
        System.out.println("Step-6 demo: .npy byte format and cross-format X/Y identity");
        System.out.println("============================================================");

        // The same input track (as if loaded from a tracking CSV: track ID 7, one detection
        // per frame) is used throughout this demo -- printed once here so every section below
        // can be checked against it directly, most importantly section 4's X/Y identity table.
        double[] x     = {10.25, 20.5, 30.75};
        double[] y     = {1.125, 2.5, 45.625};
        int[]    frame = {0, 1, 2};
        System.out.println("\nInput track (Track_ID=7, as if loaded from a tracking CSV):");
        System.out.printf("  %-6s %-8s %10s %10s %10s%n", "point", "Track_ID", "Frame", "X", "Y");
        for (int i = 0; i < x.length; i++) {
            System.out.printf("  %-6d %-8s %10d %10.4f %10.4f%n", i, "7", frame[i], x[i], y[i]);
        }

        // Z is NOT part of the input -- a real run computes it via ZExtractor (sampling +
        // Z-mapping lookup + aggregation over the TIFF stack), so there's nothing in a tracking
        // CSV for it to come from. This is a synthetic stand-in for that computed value, declared
        // and printed here (rather than buried inside a later section) so it's clear it's a
        // separate input to this demo, not something read from anywhere.
        double[] z = {100.0, 200.0, 300.0};
        System.out.println("\nSynthetic Z (stand-in for a real ZExtractor output -- NOT read from any input):");
        System.out.printf("  %-6s %10s%n", "point", "Z");
        for (int i = 0; i < z.length; i++) {
            System.out.printf("  %-6d %10.4f%n", i, z[i]);
        }

        npyByteAnatomy();
        headerAlignmentAcrossShapes();
        trackColumnOrder(x, y, z, frame);
        crossFormatCoordinateIdentity(x, y, z, frame);
    }
}