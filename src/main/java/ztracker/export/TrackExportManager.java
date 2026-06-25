package ztracker.export;

import ij.IJ;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Groups detections by track ID, applies quality filters, and dispatches
 * to the requested exporters ({@link NpyExporter} and/or {@link FijiPointsExporter}).
 *
 * <p>Filter criteria (same as the Python pipeline):
 * <ul>
 *   <li>Minimum track length (default: 3 frames)</li>
 *   <li>Maximum average Z standard deviation (optional)</li>
 *   <li>Tracks with any NaN in X, Y, or T are excluded from 2D export</li>
 *   <li>Tracks with any NaN in Z are excluded from 3D export only</li>
 * </ul>
 */
public class TrackExportManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    public static class ExportConfig {
        /** Minimum number of frames a track must span to be exported. */
        public final int    minTrackLength;
        /** Maximum allowed mean Z std per track. Null = no limit. */
        public final Double maxZStd;
        /** Export .npy files for the downstream Python pipeline. */
        public final boolean exportNpy;
        /** Export Fiji Results Table CSV. */
        public final boolean exportResultsTable;
        /** Export Fiji ROI set (.zip). */
        public final boolean exportRoiSet;

        public ExportConfig(int minTrackLength, Double maxZStd,
                            boolean exportNpy, boolean exportResultsTable,
                            boolean exportRoiSet) {
            this.minTrackLength    = minTrackLength;
            this.maxZStd           = maxZStd;
            this.exportNpy         = exportNpy;
            this.exportResultsTable= exportResultsTable;
            this.exportRoiSet      = exportRoiSet;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs the full export pipeline.
     *
     * @param track    original track data
     * @param result   extraction result (Z values, std, etc.)
     * @param config   export configuration
     * @param outDir   parent output directory (sub-folders are created automatically)
     * @param methodTag label appended to folder names when multiple methods are compared
     */
    public static void export(
            TrackData track,
            ExtractionResult result,
            ExportConfig config,
            Path outDir,
            String methodTag) throws IOException {

        // Build suffix for folder naming (empty string → no suffix)
        String suffix = (methodTag == null || methodTag.isEmpty()) ? "" : "_" + methodTag;

        Path dir2D   = outDir.resolve("tracks_2D" + suffix);
        Path dir3D   = outDir.resolve("tracks_3D" + suffix);
        Path dirFiji = outDir.resolve("fiji" + suffix);

        // Group detections by track ID, preserving insertion order
        Map<String, List<Integer>> byTrack = groupByTrack(track);

        int exported2D = 0, exported3D = 0;
        int filteredShort = 0, filteredNoisy = 0, filteredNaN = 0;

        // Accumulate data for Fiji bulk export
        List<String> allTids   = new ArrayList<>();
        List<Integer> allFrames = new ArrayList<>();
        List<Double>  allX     = new ArrayList<>();
        List<Double>  allY     = new ArrayList<>();
        List<Double>  allZ     = new ArrayList<>();

        for (Map.Entry<String, List<Integer>> entry : byTrack.entrySet()) {
            String       trackId  = entry.getKey();
            List<Integer> indices  = entry.getValue();

            // Sort detections by frame
            indices.sort(Comparator.comparingInt(i -> track.frame[i]));

            // ── Filter: minimum length
            if (indices.size() < config.minTrackLength) { filteredShort++; continue; }

            // ── Filter: Z noise
            if (config.maxZStd != null) {
                double meanStd = indices.stream()
                        .mapToDouble(i -> result.zStd[i])
                        .filter(v -> !Double.isNaN(v))
                        .average().orElse(0.0);
                if (meanStd > config.maxZStd) { filteredNoisy++; continue; }
            }

            // ── Extract arrays
            double[] xArr = indices.stream().mapToDouble(i -> track.x[i]).toArray();
            double[] yArr = indices.stream().mapToDouble(i -> track.y[i]).toArray();
            double[] zArr = indices.stream().mapToDouble(i -> result.z[i]).toArray();
            int[]    tArr = indices.stream().mapToInt(i -> track.frame[i]).toArray();

            // ── Filter: NaN in X, Y, T (would break 2D export)
            if (hasNaN(xArr) || hasNaN(yArr)) { filteredNaN++; continue; }

            // ── NPY export
            if (config.exportNpy) {
                int trackIdInt = parseTrackId(trackId);
                dir2D.toFile().mkdirs();
                NpyExporter.write2DTrack(xArr, yArr, tArr,
                        dir2D.resolve(String.format("track_%05d.npy", trackIdInt)));
                exported2D++;

                if (!hasNaN(zArr)) {
                    dir3D.toFile().mkdirs();
                    NpyExporter.write3DTrack(xArr, yArr, zArr, tArr,
                            dir3D.resolve(String.format("track_%05d.npy", trackIdInt)));
                    exported3D++;
                }
            }

            // ── Accumulate for Fiji export
            if (config.exportResultsTable || config.exportRoiSet) {
                for (int i : indices) {
                    allTids.add(trackId);
                    allFrames.add(track.frame[i]);
                    allX.add(track.x[i]);
                    allY.add(track.y[i]);
                    allZ.add(result.z[i]);
                }
            }
        }

        // ── Fiji bulk export
        if (!allTids.isEmpty()) {
            String[]  tidsArr   = allTids.toArray(new String[0]);
            int[]     framesArr = allFrames.stream().mapToInt(Integer::intValue).toArray();
            double[]  xBulk     = allX.stream().mapToDouble(Double::doubleValue).toArray();
            double[]  yBulk     = allY.stream().mapToDouble(Double::doubleValue).toArray();
            double[]  zBulk     = allZ.stream().mapToDouble(Double::doubleValue).toArray();

            dirFiji.toFile().mkdirs();

            if (config.exportResultsTable) {
                FijiPointsExporter.writeResultsTable(tidsArr, framesArr, xBulk, yBulk, zBulk,
                        dirFiji.resolve("results_table.csv"));
            }
            if (config.exportRoiSet) {
                FijiPointsExporter.writeRoiSet(tidsArr, framesArr, xBulk, yBulk, zBulk,
                        dirFiji.resolve("track_rois.zip"));
            }
        }

        // ── Summary
        IJ.log(String.format(
                "[TrackExportManager] Exported 2D=%d, 3D=%d | "
                + "Filtered: short=%d, noisy=%d, NaN=%d",
                exported2D, exported3D, filteredShort, filteredNoisy, filteredNaN));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static Map<String, List<Integer>> groupByTrack(TrackData track) {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        for (int i = 0; i < track.size(); i++) {
            String tid = track.trackId[i];
            if (tid == null || tid.isEmpty()) continue;
            map.computeIfAbsent(tid, k -> new ArrayList<>()).add(i);
        }
        return map;
    }

    private static boolean hasNaN(double[] arr) {
        for (double v : arr) if (Double.isNaN(v)) return true;
        return false;
    }

    private static int parseTrackId(String tid) {
        try { return (int) Double.parseDouble(tid); }
        catch (NumberFormatException e) { return tid.hashCode() & 0x7FFFFFFF; }
    }
}
