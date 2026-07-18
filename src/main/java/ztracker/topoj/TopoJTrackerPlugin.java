package ztracker.topoj;

import ij.IJ;
import ij.plugin.PlugIn;
import ztracker.core.topoj.TopoJExtractor;
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
 * <pre>Plugins>ZTracker, "3D Z-Extractor (TopoJ / direct-Z)", ztracker.TopoJTrackerPlugin</pre>
 *
 * <p>Structurally identical to {@link ZTrackerPlugin}, minus the JSON Z-mapping: a TopoJ
 * projection pixel value <b>is</b> the Z coordinate in µm, so there is no mapping to load
 * and {@link TopoJExtractor} aggregates sampled values directly. All downstream machinery
 * (frame alignment, sampling/aggregation methods, per-point drop logic, every export
 * format) is shared with Tool 1 unchanged.
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

        // ── Step 1–2: Collect file paths and CSV format ───────────────────────
        if (!dialog.runSteps1And2()) return;

        // ── Load TopoJ float stack (pixel value = Z in µm) ────────────────────
        TopoJStackLoader.LoadedFloatStack stack;
        try {
            IJ.showStatus("Loading TopoJ Z-map stack…");
            stack = TopoJStackLoader.load(dialog.tiffFolder);
        } catch (Exception e) {
            IJ.error("TopoJ Z-Extractor", "Failed to load TopoJ TIFF stack:\n" + e.getMessage());
            return;
        }

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

        // ── Step 3: Column confirmation ───────────────────────────────────────
        if (!dialog.runStep3Columns()) return;

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

        // ── Steps 4–6: Frame offset, methods, export config ───────────────────
        dialog.setLoadedData(trackData, stack);
        if (!dialog.runSteps4To6()) return;

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
