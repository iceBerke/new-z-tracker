package ztracker.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
