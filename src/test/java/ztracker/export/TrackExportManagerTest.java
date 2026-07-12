package ztracker.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.export.TrackExportManager.ExportConfig;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackExportManagerTest {

    // One track of 3 detections (meets default min length).
    private static TrackData threeDetectionTrack() {
        return new TrackData(
                new double[]{1.0, 2.0, 3.0},
                new double[]{1.0, 2.0, 3.0},
                new int[]{0, 1, 2},
                new double[]{3.5, 3.5, 3.5},
                new String[]{"1", "1", "1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
    }

    private static ExtractionResult validResult() {
        return new ExtractionResult(
                new double[]{10.0, 11.0, 12.0},
                new double[]{0.1, 0.1, 0.1},
                new int[]{5, 5, 5},
                new int[]{0, 0, 0},
                new String[]{ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 0, 0, 0);
    }

    @Test
    void export_shortTrack_isFilteredOut(@TempDir Path outDir) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = validResult();
        ExportConfig config = new ExportConfig(4, null, true, false, false); // min length 4 > 3 detections

        TrackExportManager.export(track, result, config, outDir, "");

        assertFalse(Files.exists(outDir.resolve("tracks_2D")));
        assertFalse(Files.exists(outDir.resolve("tracks_3D")));
    }

    @Test
    void export_validTrack_writesNpyFiles(@TempDir Path outDir) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = validResult();
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        assertTrue(Files.exists(outDir.resolve("tracks_2D").resolve("track_00001.npy")));
        assertTrue(Files.exists(outDir.resolve("tracks_3D").resolve("track_00001.npy")));
    }

    /** A track where one detection (index 1) has a NaN Z from a missing TIFF frame. */
    private static ExtractionResult partialNaNResult() {
        return new ExtractionResult(
                new double[]{10.0, Double.NaN, 12.0},
                new double[]{0.1, Double.NaN, 0.1},
                new int[]{5, 0, 5},
                new int[]{0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK,
                        ExtractionResult.STATUS_MISSING_FRAME,
                        ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 1, 0, 0);
    }

    @Test
    void export_trackWithOneBadPoint_keepsTrackButDropsOnlyThatPointFrom3D(@TempDir Path outDir) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = partialNaNResult();
        // min length 2 so the remaining 2 valid-Z points still clear the 3D bar
        ExportConfig config = new ExportConfig(2, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        Path npy2D = outDir.resolve("tracks_2D").resolve("track_00001.npy");
        Path npy3D = outDir.resolve("tracks_3D").resolve("track_00001.npy");
        assertTrue(Files.exists(npy2D));
        assertTrue(Files.exists(npy3D));

        // 2D keeps all 3 points; 3D keeps only the 2 with a valid Z.
        assertEquals(3, npyRowCount(npy2D));
        assertEquals(2, npyRowCount(npy3D));
    }

    @Test
    void export_tooFewValidZPoints_keeps2DButSkips3D(@TempDir Path outDir) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = partialNaNResult();
        // min length 3: only 2 valid-Z points remain after dropping the bad one -> 3D skipped
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        assertTrue(Files.exists(outDir.resolve("tracks_2D").resolve("track_00001.npy")));
        assertFalse(Files.exists(outDir.resolve("tracks_3D").resolve("track_00001.npy")));
    }

    /** A track where every detection has a NaN Z (all missing frames). */
    private static ExtractionResult allNaNResult() {
        return new ExtractionResult(
                new double[]{Double.NaN, Double.NaN, Double.NaN},
                new double[]{Double.NaN, Double.NaN, Double.NaN},
                new int[]{0, 0, 0},
                new int[]{0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_MISSING_FRAME,
                        ExtractionResult.STATUS_MISSING_FRAME,
                        ExtractionResult.STATUS_MISSING_FRAME},
                "Radius-based", "Median", 3, 0, 0);
    }

    @Test
    void export_allPointsBad_keeps2DButSkips3DEntirely(@TempDir Path outDir) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = allNaNResult();
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        // 2D is unaffected by Z at all -> still exported with all 3 points.
        Path npy2D = outDir.resolve("tracks_2D").resolve("track_00001.npy");
        assertTrue(Files.exists(npy2D));
        assertEquals(3, npyRowCount(npy2D));

        // 0 valid-Z points < minTrackLength -> 3D skipped entirely, not written at all.
        assertFalse(Files.exists(outDir.resolve("tracks_3D").resolve("track_00001.npy")));

        String content = String.join("\n", Files.readAllLines(
                outDir.resolve("export_report.txt"), StandardCharsets.UTF_8));
        assertTrue(content.contains("dropped 3: 3 missing frame"),
                "report should attribute all 3 drops to missing frame");
    }

    @Test
    void export_trackWithOneBadXYPoint_dropsFromBothDimensionsButKeepsTrack(
            @TempDir Path outDir) throws IOException {
        // Frames 0,1,2,3; detection at frame=1 has a NaN X (e.g. unparseable/missing in the
        // source CSV) even though its Z extracted fine. A point with no position can't be
        // placed in EITHER export, unlike a NaN-Z point which only breaks 3D.
        TrackData track = new TrackData(
                new double[]{1.0, Double.NaN, 3.0, 4.0},
                new double[]{1.0, 2.0, 3.0, 4.0},
                new int[]{0, 1, 2, 3},
                new double[]{3.5, 3.5, 3.5, 3.5},
                new String[]{"1", "1", "1", "1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                new double[]{10.0, 11.0, 12.0, 13.0},
                new double[]{0.1, 0.1, 0.1, 0.1},
                new int[]{5, 5, 5, 5},
                new int[]{0, 0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK,
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 0, 0, 0);
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        Path npy2D = outDir.resolve("tracks_2D").resolve("track_00001.npy");
        Path npy3D = outDir.resolve("tracks_3D").resolve("track_00001.npy");
        assertTrue(Files.exists(npy2D), "track kept — 2D still exports the 3 good points");
        assertTrue(Files.exists(npy3D), "3D also drops the same bad point, not the whole track");

        // Both dimensions keep 3 of the 4 points; the bad-X point is dropped from both.
        assertEquals(3, npyRowCount(npy2D));
        assertEquals(3, npyRowCount(npy3D));

        double[][] data2D = readNpy(npy2D);
        int tCol2D = data2D[0].length - 1;
        double[] frames2D = {data2D[0][tCol2D], data2D[1][tCol2D], data2D[2][tCol2D]};
        // Real frame gap (0,2,3), not renumbered (never 0,1,2) — same invariant as Z drops.
        assertArrayEquals(new double[]{0.0, 2.0, 3.0}, frames2D, 1e-9);

        String content = String.join("\n", Files.readAllLines(
                outDir.resolve("export_report.txt"), StandardCharsets.UTF_8));
        assertTrue(content.contains("dropped 1: 1 invalid X/Y"),
                "report should attribute the drop to invalid X/Y, not a Z-related reason");
    }

    @Test
    void export_invalidXYAndInvalidZInSameTrack_dropReasonsDoNotConflate(
            @TempDir Path outDir) throws IOException {
        // 4 detections: frame 0 and 3 fully valid, frame 1 has invalid X (no Z problem),
        // frame 2 has valid X/Y but a NaN Z (missing frame). The two failure reasons must
        // stay distinct in both the per-point drop and the aggregate counters -- an
        // invalid-XY point must not also get tallied as a missing-frame point (or vice versa).
        TrackData track = new TrackData(
                new double[]{1.0, Double.NaN, 3.0, 4.0},
                new double[]{1.0, 2.0, 3.0, 4.0},
                new int[]{0, 1, 2, 3},
                new double[]{3.5, 3.5, 3.5, 3.5},
                new String[]{"1", "1", "1", "1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                new double[]{10.0, 11.0, Double.NaN, 13.0},
                new double[]{0.1, 0.1, Double.NaN, 0.1},
                new int[]{5, 5, 0, 5},
                new int[]{0, 0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK,
                        ExtractionResult.STATUS_MISSING_FRAME, ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 1, 0, 0);
        ExportConfig config = new ExportConfig(1, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        // 2D drops only the invalid-XY point (frame 1) -> 3 of 4 remain.
        Path npy2D = outDir.resolve("tracks_2D").resolve("track_00001.npy");
        assertEquals(3, npyRowCount(npy2D));

        // 3D drops BOTH the invalid-XY point and the invalid-Z point -> 2 of 4 remain.
        Path npy3D = outDir.resolve("tracks_3D").resolve("track_00001.npy");
        assertEquals(2, npyRowCount(npy3D));

        String content = String.join("\n", Files.readAllLines(
                outDir.resolve("export_report.txt"), StandardCharsets.UTF_8));
        assertTrue(content.contains("2D ✓ (3/4 pt) — dropped 1: 1 invalid X/Y"),
                "2D should attribute its single drop to invalid X/Y only");
        assertTrue(content.contains("3D ✓ (2/4 pt) — dropped 2: 1 invalid X/Y, 1 missing frame"),
                "3D should list both distinct reasons, not conflate or double-count them");
        assertTrue(content.contains(
                "Dropped points: invalidXY=1, missingFrame=1, outOfBounds=0, unmappedIndex=0"),
                "aggregate counters must keep the two reasons separate");
    }

    @Test
    void export_summaryLine_reportsInsufficientSkipsSeparatelyFor2DAnd3D(
            @TempDir Path outDir) throws IOException {
        // 3 detections, min length 3: two have invalid X -> only 1 valid-XY point remains,
        // which is short of minTrackLength for BOTH 2D and 3D. Since that one remaining
        // point's Z is perfectly valid, 3D's shortfall here is caused entirely by the X/Y
        // drops, not by Z -- exercising exactly the case the "insufficientValidPoints"
        // (not "insufficientValidZ") label exists to describe accurately.
        TrackData track = new TrackData(
                new double[]{1.0, Double.NaN, Double.NaN},
                new double[]{1.0, 2.0, 3.0},
                new int[]{0, 1, 2},
                new double[]{3.5, 3.5, 3.5},
                new String[]{"1", "1", "1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                new double[]{10.0, 11.0, 12.0},
                new double[]{0.1, 0.1, 0.1},
                new int[]{5, 5, 5},
                new int[]{0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK,
                        ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 0, 0, 0);
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        assertFalse(Files.exists(outDir.resolve("tracks_2D").resolve("track_00001.npy")));
        assertFalse(Files.exists(outDir.resolve("tracks_3D").resolve("track_00001.npy")));

        String content = String.join("\n", Files.readAllLines(
                outDir.resolve("export_report.txt"), StandardCharsets.UTF_8));
        assertTrue(content.contains("Skipped: 2D(insufficientValidXY)=1, 3D(insufficientValidPoints)=1"),
                "both dimensions' skip counters should reflect this one track");
    }

    @Test
    void export_droppedPointFromMidTrack_leavesGenuineGapInFrameNumbers_notRenumbered(
            @TempDir Path outDir) throws IOException {
        // Frames 0,1,2,3; detection at frame=1 (index 1) has a NaN Z (missing TIFF frame).
        TrackData track = new TrackData(
                new double[]{1.0, 2.0, 3.0, 4.0},
                new double[]{1.0, 2.0, 3.0, 4.0},
                new int[]{0, 1, 2, 3},
                new double[]{3.5, 3.5, 3.5, 3.5},
                new String[]{"1", "1", "1", "1"},
                "X", "Y", "Frame", "Track_ID", "Radius", 3.5);
        ExtractionResult result = new ExtractionResult(
                new double[]{10.0, Double.NaN, 12.0, 13.0},
                new double[]{0.1, Double.NaN, 0.1, 0.1},
                new int[]{5, 0, 5, 5},
                new int[]{0, 0, 0, 0},
                new String[]{
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_MISSING_FRAME,
                        ExtractionResult.STATUS_OK, ExtractionResult.STATUS_OK},
                "Radius-based", "Median", 1, 0, 0);
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        double[][] data3D = readNpy(outDir.resolve("tracks_3D").resolve("track_00001.npy"));
        assertEquals(3, data3D.length); // frame 1 dropped, 3 points remain

        int tCol = data3D[0].length - 1; // last column is T
        double[] frames = {data3D[0][tCol], data3D[1][tCol], data3D[2][tCol]};
        // Must be the true frame numbers with a gap where frame 1 used to be —
        // NOT renumbered/compacted to 0,1,2.
        assertArrayEquals(new double[]{0.0, 2.0, 3.0}, frames, 1e-9);
    }

    @Test
    void export_writesFullUncappedReportFile(@TempDir Path outDir) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = partialNaNResult();
        ExportConfig config = new ExportConfig(2, null, true, false, false);

        TrackExportManager.export(track, result, config, outDir, "");

        Path reportFile = outDir.resolve("export_report.txt");
        assertTrue(Files.exists(reportFile));

        String content = String.join("\n", Files.readAllLines(reportFile, StandardCharsets.UTF_8));
        assertTrue(content.contains("Radius-based"), "should record the sampling method");
        assertTrue(content.contains("Median"), "should record the aggregation method");
        assertTrue(content.contains("Track 1"), "should include the per-track line");
        assertTrue(content.contains("dropped 1: 1 missing frame"), "should include the drop reason");
        assertTrue(content.contains("Exported 2D=1, 3D=1"), "should include the summary line");
    }

    /** Reads the row count out of a .npy file's header (e.g. "'shape': (3, 4), "). */
    private static int npyRowCount(Path npyFile) throws IOException {
        byte[] bytes = Files.readAllBytes(npyFile);
        String header = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.US_ASCII);
        Matcher m = Pattern.compile("'shape':\\s*\\((\\d+),").matcher(header);
        if (!m.find()) throw new AssertionError("Could not find shape in npy header: " + header);
        return Integer.parseInt(m.group(1));
    }

    /** Reads a full float64 .npy array written by {@link NpyExporter#write}. */
    private static double[][] readNpy(Path npyFile) throws IOException {
        byte[] bytes = Files.readAllBytes(npyFile);
        int headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
        String header = new String(bytes, 10, headerLen, StandardCharsets.US_ASCII);
        Matcher m = Pattern.compile("'shape':\\s*\\((\\d+),\\s*(\\d+)").matcher(header);
        if (!m.find()) throw new AssertionError("Could not find shape in npy header: " + header);
        int rows = Integer.parseInt(m.group(1));
        int cols = Integer.parseInt(m.group(2));

        int dataStart = 10 + headerLen;
        ByteBuffer buf = ByteBuffer.wrap(bytes, dataStart, bytes.length - dataStart)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        double[][] data = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                data[r][c] = buf.getDouble();
            }
        }
        return data;
    }

    @Test
    void export_differentOutDirsPerMethod_doNotCrossContaminate(@TempDir Path base) throws IOException {
        TrackData track = threeDetectionTrack();
        ExtractionResult result = validResult();
        ExportConfig config = new ExportConfig(3, null, true, false, false);

        Path radiusDir = base.resolve("radius").resolve("median");
        Path pixelDir  = base.resolve("single_pixel").resolve("mean");

        TrackExportManager.export(track, result, config, radiusDir, "");
        TrackExportManager.export(track, result, config, pixelDir, "");

        assertTrue(Files.exists(radiusDir.resolve("tracks_2D").resolve("track_00001.npy")));
        assertTrue(Files.exists(pixelDir.resolve("tracks_2D").resolve("track_00001.npy")));
        // Each method's export stays under its own subtree.
        assertFalse(Files.exists(base.resolve("tracks_2D")));
    }
}
