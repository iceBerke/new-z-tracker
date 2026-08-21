package ztracker.topoj;

import ij.IJ;
import ij.plugin.PlugIn;
import ztracker.core.topoj.TopoJExtractor;
import ztracker.core.topoj.TopoJStackDecoder;
import ztracker.core.ZAggregator;
import ztracker.core.extractor.ZExtractor;
import ztracker.core.extractor.ZSampler;
import ztracker.export.TrackExportManager;
import ztracker.io.topoj.TopoJStackLoader;
import ztracker.io.TrackCsvLoader;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;
import ztracker.ui.topoj.TopoJTrackerDialog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fiji plugin entry point for Tool 3 — the TopoJ / direct-Z coordinate extractor.
 *
 * <p>Registered in {@code plugins.config} as:
 * <pre>Plugins>ZTracker, "3D Z-Extractor (TopoJ / direct-Z)", ztracker.topoj.TopoJTrackerPlugin</pre>
 *
 * <p>Structurally Tool 2's orchestrator with the JSON Z-mapping replaced by a decode. A TopoJ
 * pixel holds an <b>encoded slice counter</b>, not a depth, so instead of loading a mapping
 * this plugin collects four calibration numbers in Step 2 and hands them to
 * {@link ztracker.core.topoj.TopoJStackDecoder}, which validates every pixel and converts the
 * whole stack to physical Z in place — before any sampling. {@link TopoJExtractor} therefore
 * aggregates values that already are Z, with no per-sample lookup. All downstream machinery
 * (frame alignment, sampling/aggregation methods, per-point drop logic, every export
 * format) is shared with Tool 2 unchanged.
 *
 * <p>This class is intentionally thin — it only orchestrates the pipeline. No business
 * logic lives here.
 */
public class TopoJTrackerPlugin implements PlugIn {

    @Override
    public void run(String arg) {
        IJ.log("========================================");
        IJ.log("  ZTracker — TopoJ / direct-Z Extractor");
        IJ.log("========================================");

        TopoJTrackerDialog dialog = new TopoJTrackerDialog();

        // ── Step 1–3: Collect file paths, Z calibration, and CSV format ───────
        if (!dialog.runSteps1To3()) return;

        // ── Load TopoJ float stack (pixel value = TopoJ's encoded slice counter) ──
        TopoJStackLoader.LoadedFloatStack stack;
        try {
            IJ.showStatus("Loading TopoJ Z-map stack…");
            stack = TopoJStackLoader.load(dialog.tiffFolder);
        } catch (Exception e) {
            IJ.error("TopoJ Z-Extractor", "Failed to load TopoJ TIFF stack:\n" + e.getMessage());
            return;
        }

        // ── Decode the stack in place: encoded slice counter → physical Z (µm) ──
        // The catch is IllegalArgumentException specifically, and wraps only this call.
        // TopoJStackDecoder throws it to mean "the declared parameters do not match this
        // data" — a user error with a message written for the user. Catching
        // RuntimeException, or wrapping a wider block, would relabel a genuine bug as bad
        // input and send the user to re-check parameters that were right.
        TopoJStackDecoder.Report decodeReport;
        try {
            IJ.showStatus("Decoding TopoJ values to Z…");
            decodeReport = TopoJStackDecoder.decode(stack, dialog.zConversion);
        } catch (IllegalArgumentException e) {
            IJ.error("TopoJ Z-Extractor — decode failed", e.getMessage());
            IJ.log("[TopoJTrackerPlugin] Decode refused the stack; nothing was converted.");
            IJ.log("[TopoJTrackerPlugin] " + e.getMessage());
            return;
        }
        logDecodeReport(decodeReport, dialog);

        // ── Auto-detect CSV columns (Step 3 pre-processing) ───────────────────
        TrackCsvLoader.ColumnConfig detectedCols;
        try {
            detectedCols = TrackCsvLoader.detectColumns(
                    dialog.csvFile.toPath(), dialog.csvConfig);
        } catch (Exception e) {
            IJ.error("TopoJ Z-Extractor", "Failed to read CSV headers:\n" + e.getMessage());
            return;
        }

        dialog.columnConfig = detectedCols;

        // ── Step 4: Column confirmation ───────────────────────────────────────
        if (!dialog.runStep4Columns()) return;

        // ── Load CSV data ─────────────────────────────────────────────────────
        TrackData trackData;
        try {
            IJ.showStatus("Loading tracking CSV…");
            trackData = TrackCsvLoader.load(
                    dialog.csvFile.toPath(), dialog.csvConfig, dialog.columnConfig);
        } catch (Exception e) {
            IJ.error("TopoJ Z-Extractor", "Failed to load tracking CSV:\n" + e.getMessage());
            return;
        }

        // ── Steps 5–7: Frame offset, methods, export config ───────────────────
        dialog.setLoadedData(trackData, stack);
        if (!dialog.runSteps5To7()) return;

        // ── Extract Z coordinates ─────────────────────────────────────────────
        IJ.showStatus("Extracting Z coordinates…");
        IJ.log("\n[TopoJTrackerPlugin] Starting Z extraction…");

        boolean multiMethod = dialog.sampleAllMethods || dialog.aggregateAllMethods;

        List<ZSampler.Method> samplingMethods = dialog.sampleAllMethods
                ? Arrays.asList(ZSampler.Method.values())
                : Collections.singletonList(dialog.samplingMethod);
        List<ZAggregator.Method> aggregationMethods = dialog.aggregateAllMethods
                ? Arrays.asList(ZAggregator.Method.values())
                : Collections.singletonList(dialog.aggregationMethod);

        List<ZExtractor.MethodCombo> combos = TopoJExtractor.extractAll(
                trackData, stack, dialog.frameOffset,
                samplingMethods, aggregationMethods, dialog.pixelConvention);

        for (ZExtractor.MethodCombo combo : combos) {
            logExtractionSummary(combo.result);
        }

        // ── Export ────────────────────────────────────────────────────────────
        IJ.showStatus("Exporting tracks…");
        try {
            for (ZExtractor.MethodCombo combo : combos) {
                java.nio.file.Path outDir = ZExtractor.resolveComboOutputDir(
                        dialog.outputDir.toPath(), combo, multiMethod);

                TrackExportManager.export(
                        trackData,
                        combo.result,
                        dialog.exportConfig,
                        outDir,
                        "" // no method tag — method identity is encoded in the folder path
                );
            }
        } catch (Exception e) {
            IJ.error("TopoJ Z-Extractor", "Export failed:\n" + e.getMessage());
            return;
        }

        IJ.showStatus("TopoJ Z-Extractor complete.");
        IJ.showProgress(1.0);
        IJ.log("\n[TopoJTrackerPlugin] Done. Output written to: "
                + dialog.outputDir.getAbsolutePath());
        IJ.log("========================================\n");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Puts the whole decode on the record: what every pixel classified as, how the unassigned
     * sentinel is distributed across frames, and whether the declared slice count is
     * corroborated by the data. Written before extraction starts, because after it the numbers
     * are only recoverable by decoding again.
     */
    private static void logDecodeReport(TopoJStackDecoder.Report r, TopoJTrackerDialog d) {
        IJ.log(String.format(
                "[TopoJDecode] Parameters: zFirst=%.3f µm | zStep=%.3f µm | nSlices=%d | encodingScale=%.3f",
                d.zFirst, d.zStep, d.nSlices, d.encodingScale));
        IJ.log(String.format(
                "[TopoJDecode] %,d px decoded: %,d valid | %,d unassigned (1.0) | "
                + "%,d below-threshold (-1.0) | %,d no-data (NaN) | %,d invalid",
                r.totalPixels(), r.validCount, r.unassignedCount,
                r.belowThresholdCount, r.nanCount, r.invalidCount));

        if (!r.perFrame.isEmpty()) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
            for (TopoJStackDecoder.Report.FrameSentinel f : r.perFrame) {
                min = Math.min(min, f.unassignedPercent);
                max = Math.max(max, f.unassignedPercent);
                sum += f.unassignedPercent;
            }
            IJ.log(String.format(
                    "[TopoJDecode] Unassigned per frame over %d frames: min %.3f%% | max %.3f%% | mean %.3f%%",
                    r.perFrame.size(), min, max, sum / r.perFrame.size()));
        }

        IJ.log(String.format(
                "[TopoJDecode] Slice count: implied minimum %s vs declared %d — %s",
                r.impliedMinimumSliceCount.isPresent()
                        ? String.valueOf(r.impliedMinimumSliceCount.getAsInt())
                        : "none (no decodable pixel)",
                r.declaredSliceCount,
                r.sliceCountAgrees() ? "agree" : "DO NOT agree"));
        if (!r.sliceCountAgrees() && r.impliedMinimumSliceCount.isPresent()) {
            IJ.log("[TopoJDecode]   The data implies fewer slices than declared, which means no "
                    + "pixel anywhere was brightest at source slice 1. That is legitimate for a "
                    + "surface that never reaches the top of the acquired range — but if the "
                    + "slice count is simply wrong, every Z in this run is shifted by a constant "
                    + "and nothing else will reveal it.");
        }

        String warning = buildAmbiguityWarning(r);
        if (!warning.isEmpty()) {
            for (String line : warning.split("\n")) IJ.log("[TopoJDecode] " + line);
        }
    }

    /**
     * The warning for the case where {@code 1.0} means two different depths at once, or an
     * <b>empty string</b> when the sentinel cannot collide under these parameters and there is
     * nothing to warn about. Pure string building, kept separate from the logging so the wording
     * can be read in one piece — and so the decision of <i>whether</i> to warn lives here rather
     * than at the call site, where a second copy of the condition could drift out of step with
     * this one.
     *
     * <p>It has to be loud and complete: the visible symptom is only that one extreme of the Z
     * range is missing, which is exactly the kind of absence nobody notices. So it states both
     * readings the value could carry, how much data was dropped rather than guessed, what that
     * costs downstream, and the one action that removes the ambiguity for good.
     */
    static String buildAmbiguityWarning(TopoJStackDecoder.Report r) {
        // Also load-bearing, not just an early return: the Ambiguity accessors below throw when
        // there is no collision, so this guard is what makes them safe to read.
        if (!r.ambiguity.isAmbiguous()) return "";

        double pct = r.totalPixels() == 0 ? 0.0 : 100.0 * r.unassignedCount / r.totalPixels();
        return String.format(
                "⚠ WARNING — the value 1.0 is AMBIGUOUS under these parameters.%n"
                + "  TopoJ writes 1.0 into any pixel it never assigned (brightest source slice = 0),%n"
                + "  and does NOT scale that literal by the voxel depth. With this encoding scale,%n"
                + "  1.0 is ALSO the legitimate encoding of source slice %d.%n"
                + "  As the sentinel it means Z = %.3f µm; as slice %d it means Z = %.3f µm.%n"
                + "  Nothing in the file distinguishes them, so %,d of %,d pixels (%.2f%%) were%n"
                + "  DISCARDED as no-data rather than guessed.%n"
                + "  Consequences: detections landing on those pixels report no depth, and slice %d's%n"
                + "  depth cannot appear anywhere in this run — the Z range will look short at one end.%n"
                + "  FIX: set the source stack's voxel depth in Fiji (Image > Properties > Voxel depth)%n"
                + "  before running TopoJ, then re-run TopoJ and give that value as the encoding scale.",
                r.ambiguity.collidingSliceIndex(),
                r.ambiguity.sentinelCandidateZ(),
                r.ambiguity.collidingSliceIndex(),
                r.ambiguity.sliceCandidateZ(),
                r.unassignedCount, r.totalPixels(), pct,
                r.ambiguity.collidingSliceIndex());
    }

    private static void logExtractionSummary(ExtractionResult result) {
        int valid = result.countValid();
        int total = result.size();
        double pct = total > 0 ? 100.0 * valid / total : 0.0;

        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE, sumZ = 0.0;
        int validCount = 0;
        for (double v : result.z) {
            if (!Double.isNaN(v)) {
                minZ = Math.min(minZ, v);
                maxZ = Math.max(maxZ, v);
                sumZ += v;
                validCount++;
            }
        }
        double meanZ = validCount > 0 ? sumZ / validCount : Double.NaN;

        IJ.log(String.format(
                "[TopoJTrackerPlugin] Results: %d / %d valid (%.1f%%)",
                valid, total, pct));
        if (validCount > 0) {
            IJ.log(String.format(
                    "[TopoJTrackerPlugin] Z range: %.2f – %.2f µm (mean %.2f µm)",
                    minZ, maxZ, meanZ));
        }
        if (result.missingFrameCount > 0 || result.outOfBoundsCount > 0) {
            IJ.log(String.format(
                    "[TopoJTrackerPlugin] %d detection(s) had no TIFF frame to sample; "
                    + "%d detection(s) had a frame but fell outside its image bounds "
                    + "(check the detection's X/Y against the TIFF dimensions)",
                    result.missingFrameCount, result.outOfBoundsCount));
        }
        if (result.invalidXYCount > 0) {
            IJ.log(String.format(
                    "[TopoJTrackerPlugin] %d detection(s) had a missing/unparseable X or Y "
                    + "and were never sampled (check the tracking CSV)",
                    result.invalidXYCount));
        }
    }
}
