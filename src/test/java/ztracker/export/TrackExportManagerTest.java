package ztracker.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.export.TrackExportManager.ExportConfig;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
                "Radius-based", "Median", 0, 0);
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
                "Radius-based", "Median", 1, 0);
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

    /** Reads the row count out of a .npy file's header (e.g. "'shape': (3, 4), "). */
    private static int npyRowCount(Path npyFile) throws IOException {
        byte[] bytes = Files.readAllBytes(npyFile);
        String header = new String(bytes, 0, Math.min(bytes.length, 256), java.nio.charset.StandardCharsets.US_ASCII);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'shape':\\s*\\((\\d+),").matcher(header);
        if (!m.find()) throw new AssertionError("Could not find shape in npy header: " + header);
        return Integer.parseInt(m.group(1));
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
