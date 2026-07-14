package ztracker.export;

import ij.IJ;
import ij.gui.PointRoi;
import ij.plugin.frame.RoiManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exports 3D track data in Fiji-native formats:
 *
 * <ol>
 *   <li><b>RoiManager point ROIs (XY)</b> — one {@link PointRoi} per track per frame,
 *       named {@code trackID_frame}, compatible with Fiji's ROI Manager and
 *       overlay system.</li>
 *   <li><b>RoiManager point ROIs (XZ / YZ)</b> — the same per-track-per-frame layout,
 *       but plotting X (or Y) against Z in µm instead of Y (or X); Z is left
 *       unconverted, and only detections with a valid (non-NaN) Z are included, since
 *       there is nothing to plot on that axis otherwise.</li>
 *   <li><b>Results Table CSV</b> — a flat CSV with columns
 *       {@code Track_ID, Frame, X, Y, Z}, importable via
 *       {@code Analyze > Import > Results...} in Fiji.</li>
 * </ol>
 *
 * <p>ROIs are saved as a {@code .zip} archive (ImageJ's standard ROI set format),
 * which can be reopened in Fiji via {@code More >> Open...} in the ROI Manager.
 */
public class FijiPointsExporter {

    private FijiPointsExporter() {}

    // ── Results Table CSV ─────────────────────────────────────────────────────

    /**
     * Writes a flat Results Table CSV with all track detections.
     *
     * <p>Format:
     * <pre>Track_ID,Frame,X,Y,Z</pre>
     *
     * @param trackIds  track ID per detection
     * @param frames    frame number per detection
     * @param x         X pixel coordinate per detection
     * @param y         Y pixel coordinate per detection
     * @param z         Z µm coordinate per detection (NaN allowed)
     * @param outPath   destination .csv file
     */
    public static void writeResultsTable(
            String[] trackIds, int[] frames,
            double[] x, double[] y, double[] z,
            Path outPath) throws IOException {

        Files.createDirectories(outPath.getParent());

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new FileWriter(outPath.toFile())))) {

            pw.println("Track_ID,Frame,X,Y,Z");

            for (int i = 0; i < trackIds.length; i++) {
                String zStr = Double.isNaN(z[i]) ? "" : String.format("%.4f", z[i]);
                pw.printf("%s,%d,%.4f,%.4f,%s%n",
                        trackIds[i], frames[i], x[i], y[i], zStr);
            }
        }

        IJ.log("[FijiPointsExporter] Results table written: " + outPath.getFileName());
    }

    // ── RoiManager point ROIs ─────────────────────────────────────────────────

    /**
     * Creates one {@link PointRoi} per (trackId, frame) combination and
     * adds all of them to the Fiji ROI Manager, then saves the full ROI set
     * as a {@code .zip} archive.
     *
     * <p>Each ROI is named {@code <trackID>_f<frame>} so it can be identified
     * and filtered programmatically. The Z coordinate is stored as a
     * {@code setPosition} slice if a hyperstack is open; otherwise it is
     * embedded in the ROI name for reference.
     *
     * @param trackIds  track ID per detection
     * @param frames    frame number per detection
     * @param x         X pixel coordinate per detection
     * @param y         Y pixel coordinate per detection
     * @param z         Z µm coordinate per detection
     * @param outZip    destination .zip file for the ROI set
     */
    public static void writeRoiSet(
            String[] trackIds, int[] frames,
            double[] x, double[] y, double[] z,
            Path outZip) throws IOException {
        writePlanarRoiSet(trackIds, frames, x, y, outZip, "ROI set");
    }

    /**
     * Creates one {@link PointRoi} per (trackId, frame) combination in the
     * <b>XZ plane</b> — each point's coordinates are {@code (X pixels, Z µm)} rather
     * than {@code (X, Y)}. Z is written as-is, in µm, with no conversion to pixels, so
     * the resulting ROI only overlays sensibly on an XZ projection image whose own
     * pixel size matches the Z spacing (or is otherwise interpreted with that in mind).
     *
     * @param trackIds  track ID per detection
     * @param frames    frame number per detection
     * @param x         X pixel coordinate per detection
     * @param z         Z µm coordinate per detection
     * @param outZip    destination .zip file for the ROI set
     */
    public static void writeXZRoiSet(
            String[] trackIds, int[] frames,
            double[] x, double[] z,
            Path outZip) throws IOException {
        writePlanarRoiSet(trackIds, frames, x, z, outZip, "XZ ROI set");
    }

    /**
     * Creates one {@link PointRoi} per (trackId, frame) combination in the
     * <b>YZ plane</b> — each point's coordinates are {@code (Y pixels, Z µm)} rather
     * than {@code (X, Y)}. Z is written as-is, in µm, with no conversion to pixels, so
     * the resulting ROI only overlays sensibly on a YZ projection image whose own
     * pixel size matches the Z spacing (or is otherwise interpreted with that in mind).
     *
     * @param trackIds  track ID per detection
     * @param frames    frame number per detection
     * @param y         Y pixel coordinate per detection
     * @param z         Z µm coordinate per detection
     * @param outZip    destination .zip file for the ROI set
     */
    public static void writeYZRoiSet(
            String[] trackIds, int[] frames,
            double[] y, double[] z,
            Path outZip) throws IOException {
        writePlanarRoiSet(trackIds, frames, y, z, outZip, "YZ ROI set");
    }

    /** Shared implementation for {@link #writeRoiSet}, {@link #writeXZRoiSet}, and
     *  {@link #writeYZRoiSet} — they differ only in which two coordinate arrays are
     *  plotted against each other, not in the grouping/naming/save logic. */
    private static void writePlanarRoiSet(
            String[] trackIds, int[] frames,
            double[] coord1, double[] coord2,
            Path outZip, String logLabel) throws IOException {

        Files.createDirectories(outZip.getParent());

        RoiManager rm = RoiManager.getInstance();
        if (rm == null) rm = new RoiManager();
        rm.reset();

        // Group detections by (trackId, frame) to build one ROI per group
        // (multiple detections for the same track+frame are merged into one MultiPoint ROI)
        Map<String, List<Integer>> groupedIndices = new LinkedHashMap<>();
        for (int i = 0; i < trackIds.length; i++) {
            String key = trackIds[i] + "|" + frames[i];
            groupedIndices.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        for (Map.Entry<String, List<Integer>> entry : groupedIndices.entrySet()) {
            List<Integer> idxList = entry.getValue();

            float[] p1 = new float[idxList.size()];
            float[] p2 = new float[idxList.size()];
            for (int k = 0; k < idxList.size(); k++) {
                int i = idxList.get(k);
                p1[k] = (float) coord1[i];
                p2[k] = (float) coord2[i];
            }

            PointRoi roi = new PointRoi(p1, p2, p1.length);

            // Name: trackID_f<frame>  (underscores allowed in ROI names)
            int    firstIdx = idxList.get(0);
            String roiName  = trackIds[firstIdx] + "_f" + frames[firstIdx];
            roi.setName(roiName);

            // Set frame position for hyperstack compatibility
            roi.setPosition(frames[firstIdx]);

            rm.addRoi(roi);
        }

        // Save as zip
        rm.runCommand("Save", outZip.toAbsolutePath().toString());
        IJ.log(String.format("[FijiPointsExporter] %s saved (%d ROIs): %s",
                logLabel, rm.getCount(), outZip.getFileName()));
    }
}
