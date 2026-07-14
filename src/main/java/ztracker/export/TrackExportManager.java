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
 * Groups detections by track ID and dispatches to the requested exporters
 * ({@link NpyExporter} and/or {@link FijiPointsExporter}). No whole-track quality
 * filtering is applied — every track is exported.
 *
 * <p>Individual points with invalid X/Y (missing/unparseable coordinates) are dropped from
 * <b>both</b> 2D and 3D — a point with no position can't be placed in either export.
 * Individual points with a NaN Z (missing TIFF frame, out-of-bounds position, or every
 * sampled pixel unmapped) are dropped from <b>3D only</b> (2D never depended on Z). In
 * both cases the track itself is kept; only the specific bad points are excluded. Each
 * dimension is skipped only if it has zero valid points remaining — 2D and 3D are gated
 * independently.
 *
 * <p>Dropping a point never renumbers the surviving points' frame numbers — the {@code T}
 * column always holds each detection's original {@code track.frame[i]}, so a dropped point
 * shows up as a genuine gap in the frame sequence (e.g. {@code 0,1,3} if frame 2 was dropped),
 * never a shift or compaction (never {@code 0,1,2}).
 *
 * <p>A per-track report is logged for every export run (not just when something goes wrong),
 * so the user can see exactly what happened to each track at a glance. The same report — in
 * full, uncapped — is also written to {@code export_report.txt} in {@code outDir}, since the
 * Fiji Log view caps at {@link #MAX_TRACKS_LOGGED} rows for readability.
 */
public class TrackExportManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    public static class ExportConfig {
        /** Export .npy files for the downstream Python pipeline. */
        public final boolean exportNpy;
        /** Export Fiji Results Table CSV. */
        public final boolean exportResultsTable;
        /** Export Fiji ROI set (.zip). */
        public final boolean exportRoiSet;

        public ExportConfig(boolean exportNpy, boolean exportResultsTable,
                            boolean exportRoiSet) {
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
        int skipped2DNoValidPoints = 0, skipped3DNoValidPoints = 0;
        int droppedInvalidXY = 0, droppedMissingFrame = 0, droppedOutOfBounds = 0, droppedUnmapped = 0;
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

            // ── Classify each detection: invalid X/Y drops it from BOTH 2D and 3D; a valid
            //    X/Y but NaN Z drops it from 3D only; tally why for the per-track report.
            List<Integer> valid2D = new ArrayList<>(n);
            List<Integer> valid3D = new ArrayList<>(n);
            Map<String, Integer> dropReasons2D = new LinkedHashMap<>();
            Map<String, Integer> dropReasons3D = new LinkedHashMap<>();

            for (int i : indices) {
                boolean validXY = !Double.isNaN(track.x[i]) && !Double.isNaN(track.y[i]);
                if (!validXY) {
                    droppedInvalidXY++;
                    dropReasons2D.merge(ExtractionResult.STATUS_INVALID_XY, 1, Integer::sum);
                    dropReasons3D.merge(ExtractionResult.STATUS_INVALID_XY, 1, Integer::sum);
                    continue;
                }
                valid2D.add(i);
                if (!Double.isNaN(result.z[i])) {
                    valid3D.add(i);
                } else {
                    String reason = result.sampleStatus[i];
                    dropReasons3D.merge(reason, 1, Integer::sum);
                    if (ExtractionResult.STATUS_MISSING_FRAME.equals(reason)) droppedMissingFrame++;
                    else if (ExtractionResult.STATUS_OUT_OF_BOUNDS.equals(reason)) droppedOutOfBounds++;
                    else if (ExtractionResult.STATUS_UNMAPPED_INDEX.equals(reason)) droppedUnmapped++;
                }
            }

            // ── NPY export (2D and 3D gated independently on their own valid-point count)
            boolean did2D = false, did3D = false;
            if (config.exportNpy) {
                int trackIdInt = parseTrackId(trackId);

                if (!valid2D.isEmpty()) {
                    double[] xArr = valid2D.stream().mapToDouble(i -> track.x[i]).toArray();
                    double[] yArr = valid2D.stream().mapToDouble(i -> track.y[i]).toArray();
                    int[]    tArr = valid2D.stream().mapToInt(i -> track.frame[i]).toArray();

                    dir2D.toFile().mkdirs();
                    NpyExporter.write2DTrack(xArr, yArr, tArr,
                            dir2D.resolve(String.format("track_%05d.npy", trackIdInt)));
                    exported2D++;
                    did2D = true;
                } else if (!dropReasons2D.isEmpty()) {
                    skipped2DNoValidPoints++;
                }

                if (!valid3D.isEmpty()) {
                    double[] xArr3 = valid3D.stream().mapToDouble(i -> track.x[i]).toArray();
                    double[] yArr3 = valid3D.stream().mapToDouble(i -> track.y[i]).toArray();
                    double[] zArr3 = valid3D.stream().mapToDouble(i -> result.z[i]).toArray();
                    int[]    tArr3 = valid3D.stream().mapToInt(i -> track.frame[i]).toArray();

                    dir3D.toFile().mkdirs();
                    NpyExporter.write3DTrack(xArr3, yArr3, zArr3, tArr3,
                            dir3D.resolve(String.format("track_%05d.npy", trackIdInt)));
                    exported3D++;
                    did3D = true;
                } else if (!dropReasons3D.isEmpty()) {
                    skipped3DNoValidPoints++;
                }
            }

            reportLines.add(buildTrackReportLine(trackId, n, config.exportNpy,
                    did2D, valid2D.size(), dropReasons2D,
                    did3D, valid3D.size(), dropReasons3D,
                    config.exportResultsTable, config.exportRoiSet));

            // ── Accumulate for Fiji export — only points with a real position (invalid-XY
            //    points can't be placed on the image at all); NaN-Z points are still included
            //    (position known, depth blank), same as before.
            if (config.exportResultsTable || config.exportRoiSet) {
                for (int i : valid2D) {
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
                "Exported 2D=%d, 3D=%d | "
                // 3D's shortfall can come from invalid X/Y alone, Z alone, or both — a 3D point
                // needs valid X/Y AND valid Z — so this is NOT specifically "no valid Z".
                + "Skipped: 2D(noValidPoints)=%d, 3D(noValidPoints)=%d | "
                + "Dropped points: invalidXY=%d, missingFrame=%d, outOfBounds=%d, unmappedIndex=%d",
                exported2D, exported3D,
                skipped2DNoValidPoints, skipped3DNoValidPoints,
                droppedInvalidXY, droppedMissingFrame, droppedOutOfBounds, droppedUnmapped);

        // ── Per-track report (Fiji Log view is capped for readability)
        logPerTrackReport(reportLines);
        IJ.log("[TrackExportManager] " + summaryLine);

        // ── Full, uncapped report written to disk (methods/labels included since the
        //    file lives under this method combo's own output folder)
        writeReportFile(outDir, result, reportLines, summaryLine);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Builds one report line for a track that made it past the whole-track filters,
     *  covering 2D and 3D independently since each is now gated on its own valid-point count,
     *  plus a trailing note when Results Table CSV and/or ROI export are active — those
     *  formats aren't split into 2D/3D (a single flat table with an optional Z column), so
     *  they'd otherwise go unmentioned whenever npy itself is off, reading as if nothing at
     *  all was exported for the track even though it may have landed in those formats fine. */
    private static String buildTrackReportLine(
            String trackId, int totalPoints, boolean npyEnabled,
            boolean did2D, int valid2DCount, Map<String, Integer> dropReasons2D,
            boolean did3D, int valid3DCount, Map<String, Integer> dropReasons3D,
            boolean resultsTableEnabled, boolean roiSetEnabled) {

        String twoDPart = buildDimensionPart(
                "2D", npyEnabled, did2D, valid2DCount, totalPoints, dropReasons2D);
        String threeDPart = buildDimensionPart(
                "3D", npyEnabled, did3D, valid3DCount, totalPoints, dropReasons3D);

        String line = String.format("Track %-10s %s | %s", trackId, twoDPart, threeDPart);

        if (resultsTableEnabled || roiSetEnabled) {
            StringBuilder formats = new StringBuilder();
            if (resultsTableEnabled) formats.append("Results Table");
            if (roiSetEnabled) {
                if (formats.length() > 0) formats.append("+");
                formats.append("ROI");
            }
            // Results Table/ROI use the same valid-X/Y point set as 2D npy (they never
            // needed Z), so valid2DCount is the accurate "how many points made it in" count
            // regardless of whether npy itself is enabled.
            line += String.format(" | %s: %d/%d pt", formats, valid2DCount, totalPoints);
        }

        return line;
    }

    private static String buildDimensionPart(
            String label, boolean npyEnabled, boolean wrote,
            int validCount, int totalPoints, Map<String, Integer> dropReasons) {

        if (!npyEnabled) return label + " ✗ (npy export off)";

        if (dropReasons.isEmpty()) {
            return String.format("%s ✓ (%d pt) — all points valid", label, totalPoints);
        }

        String breakdown = describeDropReasons(dropReasons);
        int dropped = totalPoints - validCount;
        return wrote
                ? String.format("%s ✓ (%d/%d pt) — dropped %d: %s", label, validCount, totalPoints, dropped, breakdown)
                : String.format("%s ✗ (0/%d valid) — dropped %d: %s",
                        label, totalPoints, dropped, breakdown);
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
