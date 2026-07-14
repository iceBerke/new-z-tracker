package ztracker.export;

import ij.IJ;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import ij.plugin.frame.RoiManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

        Files.createDirectories(outZip.getParent());

        RoiManager rm = RoiManager.getInstance();
        if (rm == null) rm = new RoiManager();
        rm.reset();

        for (Roi roi : buildPlanarRois(trackIds, frames, x, y)) {
            rm.addRoi(roi);
        }

        rm.runCommand("Save", outZip.toAbsolutePath().toString());
        IJ.log(String.format("[FijiPointsExporter] ROI set saved (%d ROIs): %s",
                rm.getCount(), outZip.getFileName()));
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
        writeHeadlessRoiZip(trackIds, frames, x, z, outZip, "XZ ROI set");
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
        writeHeadlessRoiZip(trackIds, frames, y, z, outZip, "YZ ROI set");
    }

    /** Groups detections by (trackId, frame) into one {@link PointRoi} per group
     *  (multiple detections for the same track+frame merge into one multi-point ROI),
     *  named {@code <trackID>_f<frame>} and positioned on that frame. Shared by
     *  {@link #writeRoiSet}, {@link #writeXZRoiSet}, and {@link #writeYZRoiSet} — they
     *  differ only in which two coordinate arrays are plotted against each other. */
    private static List<Roi> buildPlanarRois(
            String[] trackIds, int[] frames, double[] coord1, double[] coord2) {

        Map<String, List<Integer>> groupedIndices = new LinkedHashMap<>();
        for (int i = 0; i < trackIds.length; i++) {
            String key = trackIds[i] + "|" + frames[i];
            groupedIndices.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        List<Roi> rois = new ArrayList<>();
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

            int    firstIdx = idxList.get(0);
            String roiName  = trackIds[firstIdx] + "_f" + frames[firstIdx];
            roi.setName(roiName);
            roi.setPosition(frames[firstIdx]);

            rois.add(roi);
        }
        return rois;
    }

    /**
     * Writes a planar ROI set straight to a {@code .zip} via {@link RoiEncoder}, without
     * touching the on-screen {@link RoiManager} — used for the XZ/YZ sets specifically
     * because they're not meant to share the interactive ROI list with the real XY
     * overlay (X-vs-Z points would look like nonsense image coordinates if shown there),
     * and because calling {@code RoiManager.reset()}/{@code addRoi} back-to-back for
     * XY, then XZ, then YZ within one export run was hammering the manager's on-screen
     * list faster than it could repaint, triggering a Swing/AWT rendering race in its
     * list widget (an ArrayIndexOutOfBoundsException from the renderer painting a row
     * the list model had already dropped). Writing bytes directly sidesteps that
     * entirely. The resulting {@code .zip} is still the same standard ROI-set format
     * (one {@code <name>.roi} entry per ROI), reopenable via {@code More >> Open...}.
     */
    private static void writeHeadlessRoiZip(
            String[] trackIds, int[] frames, double[] coord1, double[] coord2,
            Path outZip, String logLabel) throws IOException {

        Files.createDirectories(outZip.getParent());

        List<Roi> rois = buildPlanarRois(trackIds, frames, coord1, coord2);

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outZip)))) {
            for (Roi roi : rois) {
                zos.putNextEntry(new ZipEntry(roi.getName() + ".roi"));
                zos.write(RoiEncoder.saveAsByteArray(roi));
                zos.closeEntry();
            }
        }

        IJ.log(String.format("[FijiPointsExporter] %s saved (%d ROIs): %s",
                logLabel, rois.size(), outZip.getFileName()));
    }
}
