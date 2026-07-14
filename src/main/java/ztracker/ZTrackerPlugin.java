package ztracker;

import ij.IJ;
import ij.plugin.PlugIn;
import ztracker.core.ZAggregator;
import ztracker.core.ZExtractor;
import ztracker.core.ZSampler;
import ztracker.export.TrackExportManager;
import ztracker.io.TiffStackLoader;
import ztracker.io.TrackCsvLoader;
import ztracker.io.ZMappingLoader;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;
import ztracker.ui.ZTrackerDialog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fiji plugin entry point for the 3D Z-coordinate extractor.
 *
 * <p>Registered in {@code plugins.config} as:
 * <pre>Plugins>ZTracker, "3D Z-Coordinate Extractor", ztracker.ZTrackerPlugin</pre>
 *
 * <p>This class is intentionally thin — it only orchestrates the pipeline
 * by delegating to specialised classes in {@code io}, {@code core},
 * {@code export}, and {@code ui}. No business logic lives here.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Collect parameters via the dialog wizard ({@link ZTrackerDialog})</li>
 *   <li>Load inputs: JSON mapping, TIFF stack, tracking CSV</li>
 *   <li>Auto-detect CSV columns, show confirmation dialog</li>
 *   <li>Validate frame alignment</li>
 *   <li>Extract Z coordinates</li>
 *   <li>Export results in the chosen formats</li>
 * </ol>
 */
public class ZTrackerPlugin implements PlugIn {

    @Override
    public void run(String arg) {
        IJ.log("========================================");
        IJ.log("  ZTracker — 3D Z-Coordinate Extractor");
        IJ.log("========================================");

        ZTrackerDialog dialog = new ZTrackerDialog();

        // ── Step 1–2: Collect file paths and CSV format ───────────────────────
        // (dialog.run() drives all steps, but we need to interleave loading
        //  between steps 2 and 3, so we run the first two steps here)
        if (!dialog.runSteps1And2()) return;

        // ── Load Z mapping ────────────────────────────────────────────────────
        Map<Integer, Double> zMapping;
        try {
            zMapping = ZMappingLoader.load(dialog.jsonFile.toPath());
        } catch (Exception e) {
            IJ.error("ZTracker", "Failed to load Z-mapping JSON:\n" + e.getMessage());
            return;
        }

        // ── Load TIFF stack ───────────────────────────────────────────────────
        TiffStackLoader.LoadedStack stack;
        try {
            IJ.showStatus("Loading TIFF stack…");
            stack = TiffStackLoader.load(dialog.tiffFolder);
        } catch (Exception e) {
            IJ.error("ZTracker", "Failed to load TIFF stack:\n" + e.getMessage());
            return;
        }

        // ── Auto-detect CSV columns (Step 3 pre-processing) ───────────────────
        TrackCsvLoader.ColumnConfig detectedCols;
        try {
            detectedCols = TrackCsvLoader.detectColumns(
                    dialog.csvFile.toPath(), dialog.csvConfig);
        } catch (Exception e) {
            IJ.error("ZTracker", "Failed to read CSV headers:\n" + e.getMessage());
            return;
        }

        // Pass detected columns to dialog for user confirmation (Step 3)
        dialog.columnConfig = detectedCols;

        // ── Step 3: Column confirmation ───────────────────────────────────────
        if (!dialog.runStep3Columns()) return;

        // ── Load CSV data ─────────────────────────────────────────────────────
        TrackData trackData;
        try {
            IJ.showStatus("Loading tracking CSV…");
            trackData = TrackCsvLoader.load(
                    dialog.csvFile.toPath(), dialog.csvConfig, dialog.columnConfig);
        } catch (Exception e) {
            IJ.error("ZTracker", "Failed to load tracking CSV:\n" + e.getMessage());
            return;
        }

        // ── Steps 4–6: Frame offset, methods, export config ───────────────────
        // Step 4 validates + logs the alignment for the confirmed offset, so no
        // separate FrameAligner.validate() call is needed here.
        dialog.setLoadedData(trackData, stack);
        if (!dialog.runSteps4To6()) return;

        // ── Extract Z coordinates ─────────────────────────────────────────────
        IJ.showStatus("Extracting Z coordinates…");
        IJ.log("\n[ZTrackerPlugin] Starting Z extraction…");

        boolean multiMethod = dialog.sampleAllMethods || dialog.aggregateAllMethods;

        List<ZSampler.Method> samplingMethods = dialog.sampleAllMethods
                ? Arrays.asList(ZSampler.Method.values())
                : Collections.singletonList(dialog.samplingMethod);
        List<ZAggregator.Method> aggregationMethods = dialog.aggregateAllMethods
                ? Arrays.asList(ZAggregator.Method.values())
                : Collections.singletonList(dialog.aggregationMethod);

        List<ZExtractor.MethodCombo> combos = ZExtractor.extractAll(
                trackData, stack, zMapping, dialog.frameOffset,
                samplingMethods, aggregationMethods);

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
            IJ.error("ZTracker", "Export failed:\n" + e.getMessage());
            return;
        }

        IJ.showStatus("ZTracker complete.");
        IJ.showProgress(1.0);
        IJ.log("\n[ZTrackerPlugin] Done. Output written to: "
                + dialog.outputDir.getAbsolutePath());
        IJ.log("========================================\n");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
                "[ZTrackerPlugin] Results: %d / %d valid (%.1f%%)",
                valid, total, pct));
        if (validCount > 0) {
            IJ.log(String.format(
                    "[ZTrackerPlugin] Z range: %.2f – %.2f µm (mean %.2f µm)",
                    minZ, maxZ, meanZ));
        }
        if (result.missingFrameCount > 0 || result.outOfBoundsCount > 0) {
            IJ.log(String.format(
                    "[ZTrackerPlugin] %d detection(s) had no TIFF frame to sample; "
                    + "%d detection(s) had a frame but fell outside its image bounds "
                    + "(check the detection's X/Y against the TIFF dimensions)",
                    result.missingFrameCount, result.outOfBoundsCount));
        }
        if (result.invalidXYCount > 0) {
            IJ.log(String.format(
                    "[ZTrackerPlugin] %d detection(s) had a missing/unparseable X or Y "
                    + "and were never sampled (check the tracking CSV)",
                    result.invalidXYCount));
        }
    }
}
