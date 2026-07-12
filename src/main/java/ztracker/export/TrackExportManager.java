package ztracker.export;

import ij.IJ;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Groups detections by track ID, applies quality filters, and dispatches
 * to the requested exporters ({@link NpyExporter} and/or {@link FijiPointsExporter}).
 *
 * <p>Filter criteria (same as the Python pipeline):
 * <ul>
 *   <li>Minimum track length (default: 3 frames) — applied to the track as a whole</li>
 *   <li>Maximum average Z standard deviation (optional) — applied to the track as a whole</li>
 *   <li>Tracks with any NaN in X or Y are excluded entirely (would break 2D export)</li>
 *   <li>Individual points with a NaN Z (missing TIFF frame, out-of-bounds position, or every
 *       sampled pixel unmapped) are dropped from that track's <b>3D</b> export only — the
 *       track itself is kept and still gets a full 2D export. 3D is skipped only if too few
 *       valid-Z points remain (fewer than {@code minTrackLength})</li>
 * </ul>
 *
 * <p>Dropping a point from 3D never renumbers the surviving points' frame numbers — the
 * {@code T} column always holds each detection's original {@code track.frame[i]}, so a
 * dropped point shows up as a genuine gap in the frame sequence (e.g. {@code 0,1,3} if frame
 * 2 was dropped), never a shift or compaction (never {@code 0,1,2}).
 *
 * <p>A per-track report is logged for every export run (not just when something goes wrong),
 * so the user can see exactly what happened to each track at a glance. The same report — in
 * full, uncapped — is also written to {@code export_report.txt} in {@code outDir}, since the
 * Fiji Log view caps at {@link #MAX_TRACKS_LOGGED} rows for readability.
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

    /** Max number of per-track report lines logged (keeps huge CSVs readable). */
    private static final int MAX_TRACKS_LOGGED = 50;

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

        // Group detections by track ID, ordered by track id (numeric-aware) for reporting
        Map<String, List<Integer>> byTrack = groupByTrack(track);
        List<String> orderedTrackIds = new ArrayList<>(byTrack.keySet());
        orderedTrackIds.sort(TrackExportManager::compareTrackIds);

        int exported2D = 0, exported3D = 0;
        int filteredShort = 0, filteredNoisy = 0, filteredInvalidXY = 0, skipped3DInsufficient = 0;
        int droppedMissingFrame = 0, droppedOutOfBounds = 0, droppedUnmapped = 0;
        List<String> reportLines = new ArrayList<>();

        // Accumulate data for Fiji bulk export
        List<String> allTids   = new ArrayList<>();
        List<Integer> allFrames = new ArrayList<>();
        List<Double>  allX     = new ArrayList<>();
        List<Double>  allY     = new ArrayList<>();
        List<Double>  allZ     = new ArrayList<>();

        for (String trackId : orderedTrackIds) {
            List<Integer> indices = byTrack.get(trackId);

            // Sort detections by frame
            indices.sort(Comparator.comparingInt(i -> track.frame[i]));
            int n = indices.size();

            // ── Filter: minimum length
            if (n < config.minTrackLength) {
                filteredShort++;
                reportLines.add(String.format(
                        "Track %-10s SKIPPED — length %d < minimum %d",
                        trackId, n, config.minTrackLength));
                continue;
            }

            // ── Filter: Z noise
            if (config.maxZStd != null) {
                double meanStd = indices.stream()
                        .mapToDouble(i -> result.zStd[i])
                        .filter(v -> !Double.isNaN(v))
                        .average().orElse(0.0);
                if (meanStd > config.maxZStd) {
                    filteredNoisy++;
                    reportLines.add(String.format(
                            "Track %-10s SKIPPED — noisy (mean Z std %.2f > max %.2f)",
                            trackId, meanStd, config.maxZStd));
                    continue;
                }
            }

            // ── Extract arrays (full track)
            double[] xArr = indices.stream().mapToDouble(i -> track.x[i]).toArray();
            double[] yArr = indices.stream().mapToDouble(i -> track.y[i]).toArray();
            int[]    tArr = indices.stream().mapToInt(i -> track.frame[i]).toArray();

            // ── Filter: NaN in X, Y (would break 2D export)
            if (hasNaN(xArr) || hasNaN(yArr)) {
                filteredInvalidXY++;
                reportLines.add(String.format(
                        "Track %-10s SKIPPED — invalid X/Y in one or more detections",
                        trackId));
                continue;
            }

            // ── Split the track's points into valid-Z (kept for 3D) and dropped, tallying why
            List<Integer> valid3D = new ArrayList<>(n);
            Map<String, Integer> dropReasons = new LinkedHashMap<>();
            for (int i : indices) {
                if (!Double.isNaN(result.z[i])) {
                    valid3D.add(i);
                } else {
                    String reason = result.sampleStatus[i];
                    dropReasons.merge(reason, 1, Integer::sum);
                    if (ExtractionResult.STATUS_MISSING_FRAME.equals(reason)) droppedMissingFrame++;
                    else if (ExtractionResult.STATUS_OUT_OF_BOUNDS.equals(reason)) droppedOutOfBounds++;
                    else if (ExtractionResult.STATUS_UNMAPPED_INDEX.equals(reason)) droppedUnmapped++;
                }
            }
            int droppedCount = n - valid3D.size();

            // ── NPY export
            boolean did2D = false, did3D = false;
            if (config.exportNpy) {
                int trackIdInt = parseTrackId(trackId);

                dir2D.toFile().mkdirs();
                NpyExporter.write2DTrack(xArr, yArr, tArr,
                        dir2D.resolve(String.format("track_%05d.npy", trackIdInt)));
                exported2D++;
                did2D = true;

                if (valid3D.size() >= config.minTrackLength) {
                    double[] xArr3 = valid3D.stream().mapToDouble(i -> track.x[i]).toArray();
                    double[] yArr3 = valid3D.stream().mapToDouble(i -> track.y[i]).toArray();
                    double[] zArr3 = valid3D.stream().mapToDouble(i -> result.z[i]).toArray();
                    int[]    tArr3 = valid3D.stream().mapToInt(i -> track.frame[i]).toArray();

                    dir3D.toFile().mkdirs();
                    NpyExporter.write3DTrack(xArr3, yArr3, zArr3, tArr3,
                            dir3D.resolve(String.format("track_%05d.npy", trackIdInt)));
                    exported3D++;
                    did3D = true;
                } else if (droppedCount > 0) {
                    skipped3DInsufficient++;
                }
            }

            reportLines.add(buildTrackReportLine(
                    trackId, n, did2D, did3D, valid3D.size(), config.minTrackLength, dropReasons));

            // ── Accumulate for Fiji export (unfiltered — includes NaN-Z points as before)
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

        String summaryLine = String.format(
                "Exported 2D=%d, 3D=%d | Filtered tracks: short=%d, "
                + "noisy=%d, invalidXY=%d, 3Dskipped(insufficientValidZ)=%d | "
                + "Dropped 3D points: missingFrame=%d, outOfBounds=%d, unmappedIndex=%d",
                exported2D, exported3D, filteredShort, filteredNoisy, filteredInvalidXY,
                skipped3DInsufficient, droppedMissingFrame, droppedOutOfBounds, droppedUnmapped);

        // ── Per-track report (Fiji Log view is capped for readability)
        logPerTrackReport(reportLines);
        IJ.log("[TrackExportManager] " + summaryLine);

        // ── Full, uncapped report written to disk (methods/labels included since the
        //    file lives under this method combo's own output folder)
        writeReportFile(outDir, result, reportLines, summaryLine);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Builds one report line for a track that made it past the whole-track filters. */
    private static String buildTrackReportLine(
            String trackId, int totalPoints, boolean did2D, boolean did3D,
            int validZCount, int minTrackLength, Map<String, Integer> dropReasons) {

        String twoDPart = did2D
                ? String.format("2D ✓ (%d pt)", totalPoints)
                : "2D ✗ (npy export off)";

        String threeDPart;
        if (!did2D) {
            threeDPart = "3D ✗";
        } else if (dropReasons.isEmpty()) {
            threeDPart = String.format("3D ✓ (%d/%d pt) — all points valid", validZCount, totalPoints);
        } else {
            String breakdown = describeDropReasons(dropReasons);
            threeDPart = did3D
                    ? String.format("3D ✓ (%d/%d pt) — dropped %d: %s",
                            validZCount, totalPoints, totalPoints - validZCount, breakdown)
                    : String.format("3D ✗ (%d/%d valid < min %d) — dropped %d: %s",
                            validZCount, totalPoints, minTrackLength,
                            totalPoints - validZCount, breakdown);
        }

        return String.format("Track %-10s %s | %s", trackId, twoDPart, threeDPart);
    }

    private static String describeDropReasons(Map<String, Integer> dropReasons) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : dropReasons.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey());
        }
        return sb.toString();
    }

    /**
     * Writes the full, uncapped per-track report (plus method labels and the summary
     * line) to {@code export_report.txt} in {@code outDir} — the same content logged
     * to the Fiji Log, but without the {@link #MAX_TRACKS_LOGGED} cap, so every track
     * is on record for large datasets.
     */
    private static void writeReportFile(Path outDir, ExtractionResult result,
                                        List<String> reportLines, String summaryLine)
            throws IOException {
        Files.createDirectories(outDir);

        List<String> lines = new ArrayList<>();
        lines.add("ZTracker export report");
        lines.add("Sampling method:    " + result.samplingMethod);
        lines.add("Aggregation method: " + result.aggregationMethod);
        lines.add("");
        lines.add(String.format("Per-track report (%d track(s)):", reportLines.size()));
        lines.addAll(reportLines);
        lines.add("");
        lines.add(summaryLine);

        Files.write(outDir.resolve("export_report.txt"), lines, StandardCharsets.UTF_8);
    }

    private static void logPerTrackReport(List<String> reportLines) {
        IJ.log(String.format("[TrackExportManager] ── Per-track report (%d track(s)) ──",
                reportLines.size()));
        int shown = 0;
        for (String line : reportLines) {
            if (shown++ >= MAX_TRACKS_LOGGED) break;
            IJ.log("[TrackExportManager]   " + line);
        }
        int remaining = reportLines.size() - Math.min(reportLines.size(), MAX_TRACKS_LOGGED);
        if (remaining > 0) {
            IJ.log(String.format("[TrackExportManager]   … %d more track(s) not shown (log cap %d)",
                    remaining, MAX_TRACKS_LOGGED));
        }
    }

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

    /** Orders track ids numerically when both parse as integers, else lexicographically —
     *  consistent with {@link ztracker.core.FrameAligner}'s per-track table ordering. */
    private static int compareTrackIds(String a, String b) {
        try {
            return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim()));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
