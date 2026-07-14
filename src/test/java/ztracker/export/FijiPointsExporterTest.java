package ztracker.export;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.process.FloatPolygon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FijiPointsExporterTest {

    @Test
    void writeRoiSet_writesOneRoiPerTrackFrameCombination(@TempDir Path outDir) throws IOException {
        // 4 detections: track "1" at frames 0 and 1, track "2" at frame 0 only.
        // -> 3 distinct (trackId, frame) groups, so 3 ROIs expected.
        String[] trackIds = {"1", "1", "2", "1"};
        int[]    frames   = {0, 1, 0, 1};
        double[] x        = {1.0, 2.0, 3.0, 2.5};
        double[] y        = {1.0, 2.0, 3.0, 2.5};
        double[] z        = {10.0, 11.0, 12.0, 11.5};

        Path outZip = outDir.resolve("track_rois.zip");
        FijiPointsExporter.writeRoiSet(trackIds, frames, x, y, z, outZip);

        assertTrue(Files.exists(outZip));

        int entryCount = 0;
        boolean sawTrack1Frame0 = false, sawTrack1Frame1 = false, sawTrack2Frame0 = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(outZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                String name = entry.getName();
                if (name.contains("1_f0")) sawTrack1Frame0 = true;
                if (name.contains("1_f1")) sawTrack1Frame1 = true;
                if (name.contains("2_f0")) sawTrack2Frame0 = true;
            }
        }

        assertEquals(3, entryCount, "one ROI per distinct (trackId, frame) combination");
        assertTrue(sawTrack1Frame0, "expected an ROI named like 1_f0");
        assertTrue(sawTrack1Frame1, "expected an ROI named like 1_f1");
        assertTrue(sawTrack2Frame0, "expected an ROI named like 2_f0");
    }

    @Test
    void writeRoiSet_preservesInputXYCoordinates(@TempDir Path outDir) throws IOException {
        // Values exactly representable as float so the double->float narrowing in
        // writeRoiSet (PointRoi stores float coordinates) introduces zero error.
        String[] trackIds = {"1", "2"};
        int[]    frames   = {0, 0};
        double[] x        = {12.375, 100.5};
        double[] y        = {45.625, 7.25};
        double[] z        = {10.0, 20.0};

        Path outZip = outDir.resolve("track_rois.zip");
        FijiPointsExporter.writeRoiSet(trackIds, frames, x, y, z, outZip);

        boolean sawTrack1 = false, sawTrack2 = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(outZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] bytes = readAllBytes(zis);
                Roi roi = new RoiDecoder(bytes, entry.getName()).getRoi();
                FloatPolygon poly = roi.getFloatPolygon();
                assertEquals(1, poly.npoints);

                if (entry.getName().contains("1_f0")) {
                    assertEquals((float) x[0], poly.xpoints[0], 1e-4f);
                    assertEquals((float) y[0], poly.ypoints[0], 1e-4f);
                    sawTrack1 = true;
                } else if (entry.getName().contains("2_f0")) {
                    assertEquals((float) x[1], poly.xpoints[0], 1e-4f);
                    assertEquals((float) y[1], poly.ypoints[0], 1e-4f);
                    sawTrack2 = true;
                }
            }
        }
        assertTrue(sawTrack1 && sawTrack2, "both ROIs should have been decoded and checked");
    }

    @Test
    void writeXZRoiSet_plotsXAgainstUnconvertedMicronZ(@TempDir Path outDir) throws IOException {
        String[] trackIds = {"1", "2"};
        int[]    frames   = {0, 0};
        double[] x        = {12.375, 100.5};
        double[] z        = {-45.625, 7.25};

        Path outZip = outDir.resolve("track_rois_XZ.zip");
        FijiPointsExporter.writeXZRoiSet(trackIds, frames, x, z, outZip);

        boolean sawTrack1 = false, sawTrack2 = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(outZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] bytes = readAllBytes(zis);
                Roi roi = new RoiDecoder(bytes, entry.getName()).getRoi();
                FloatPolygon poly = roi.getFloatPolygon();
                assertEquals(1, poly.npoints);

                if (entry.getName().contains("1_f0")) {
                    assertEquals((float) x[0], poly.xpoints[0], 1e-4f);
                    assertEquals((float) z[0], poly.ypoints[0], 1e-4f, "Z must be unconverted microns, not pixels");
                    sawTrack1 = true;
                } else if (entry.getName().contains("2_f0")) {
                    assertEquals((float) x[1], poly.xpoints[0], 1e-4f);
                    assertEquals((float) z[1], poly.ypoints[0], 1e-4f, "Z must be unconverted microns, not pixels");
                    sawTrack2 = true;
                }
            }
        }
        assertTrue(sawTrack1 && sawTrack2, "both ROIs should have been decoded and checked");
    }

    @Test
    void writeYZRoiSet_plotsYAgainstUnconvertedMicronZ(@TempDir Path outDir) throws IOException {
        String[] trackIds = {"1"};
        int[]    frames   = {2};
        double[] y        = {7.25};
        double[] z        = {-600.5};

        Path outZip = outDir.resolve("track_rois_YZ.zip");
        FijiPointsExporter.writeYZRoiSet(trackIds, frames, y, z, outZip);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(outZip))) {
            ZipEntry entry = zis.getNextEntry();
            assertTrue(entry != null && entry.getName().contains("1_f2"));
            byte[] bytes = readAllBytes(zis);
            Roi roi = new RoiDecoder(bytes, entry.getName()).getRoi();
            FloatPolygon poly = roi.getFloatPolygon();
            assertEquals(1, poly.npoints);
            assertEquals((float) y[0], poly.xpoints[0], 1e-4f);
            assertEquals((float) z[0], poly.ypoints[0], 1e-4f);
        }
    }

    @Test
    void writeResultsTable_hasCorrectHeaderAndPreservesInputXYZ(@TempDir Path outDir) throws IOException {
        String[] trackIds = {"1", "2"};
        int[]    frames   = {0, 3};
        double[] x        = {12.3456, 100.5};
        double[] y        = {45.6789, 7.25};
        double[] z         = {10.1111, Double.NaN};

        Path outCsv = outDir.resolve("results_table.csv");
        FijiPointsExporter.writeResultsTable(trackIds, frames, x, y, z, outCsv);

        List<String> lines = Files.readAllLines(outCsv, StandardCharsets.UTF_8);
        assertEquals("Track_ID,Frame,X,Y,Z", lines.get(0));
        assertEquals("1,0,12.3456,45.6789,10.1111", lines.get(1));
        // NaN Z (e.g. a dropped 3D point still present in the flat 2D-sourced table)
        // must render as an empty field, not the literal string "NaN".
        assertEquals("2,3,100.5000,7.2500,", lines.get(2));
    }

    private static byte[] readAllBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = zis.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
