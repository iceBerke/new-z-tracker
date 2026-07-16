package ztracker;

import ij.IJ;
import ij.plugin.PlugIn;
import ztracker.export.ProjectionExporter;
import ztracker.io.ProjectionInputScanner;
import ztracker.io.ProjectionInputScanner.DatasetScan;
import ztracker.io.ProjectionInputScanner.TimepointStack;
import ztracker.project.ZProjector;
import ztracker.ui.ZProjectorDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fiji plugin entry point for the Z-Projection + Origin-Map tool — the upstream
 * <em>producer</em> of the indexed-TIFF + JSON-mapping inputs that
 * {@link ZTrackerPlugin} consumes.
 *
 * <p>Registered in {@code plugins.config} as:
 * <pre>Plugins&gt;ZTracker, "Z-Projection + Origin Map", ztracker.ZProjectorPlugin</pre>
 *
 * <p>Native-Java port of {@code max_z_projection_plus_z_tracking_v2.py} /
 * {@code min_z_projection_plus_z_tracking_v2.py}. Like {@link ZTrackerPlugin}, this class
 * is a thin orchestrator — the real work lives in {@code ProjectionInputScanner} (io),
 * {@code ZProjector} (project), and {@code ProjectionExporter} (export).
 *
 * <p>Pipeline: collect parameters → resolve the dataset(s) → for each dataset, scan its
 * z-layers/timepoints, write the JSON mappings, then stream each timepoint through
 * {@link ZProjector} and write the 16-/32-bit z-origin TIFFs (+ optional raw projection).
 */
public class ZProjectorPlugin implements PlugIn {

    @Override
    public void run(String arg) {
        IJ.log("========================================");
        IJ.log("  ZTracker — Z-Projection + Origin Map");
        IJ.log("========================================");

        ZProjectorDialog dialog = new ZProjectorDialog();
        if (!dialog.showDialog()) return;
        ZProjectorDialog.Config cfg = dialog.getConfig();

        // Resolve the dataset folder(s) to process.
        List<File> datasets = resolveDatasets(cfg);
        if (datasets.isEmpty()) {
            IJ.error("ZTracker", cfg.batch
                    ? "No datasets with numeric Z-layer sub-folders found under:\n"
                        + cfg.inputDir.getAbsolutePath()
                    : "No numeric Z-layer sub-folders found in:\n" + cfg.inputDir.getAbsolutePath());
            return;
        }

        IJ.log(String.format("[ZProjector] Mode: %s | Scope: %s | %d dataset(s)",
                cfg.mode, cfg.batch ? "batch" : "single", datasets.size()));

        int datasetsDone = 0;
        for (File datasetDir : datasets) {
            try {
                processDataset(datasetDir, cfg);
                datasetsDone++;
            } catch (Exception e) {
                IJ.log("[ZProjector] SKIPPED dataset '" + datasetDir.getName() + "': " + e.getMessage());
            }
        }

        IJ.showStatus("Z-Projection complete.");
        IJ.showProgress(1.0);
        IJ.log(String.format("\n[ZProjector] Done. %d / %d dataset(s) processed. Output written under: %s",
                datasetsDone, datasets.size(), cfg.outputDir.getAbsolutePath()));
        IJ.log("========================================\n");
    }

    // ── Orchestration helpers ──────────────────────────────────────────────────

    /**
     * Single scope → the input folder itself is the dataset. Batch scope → each
     * sub-folder that scans as a valid dataset (numeric z-layers), skipping the tool's
     * own {@code min_z}/{@code max_z} output roots.
     */
    private static List<File> resolveDatasets(ZProjectorDialog.Config cfg) {
        if (!cfg.batch) {
            return Collections.singletonList(cfg.inputDir);
        }
        List<File> out = new ArrayList<>();
        File[] subs = cfg.inputDir.listFiles(File::isDirectory);
        if (subs != null) {
            Arrays.sort(subs);
            for (File sub : subs) {
                String name = sub.getName();
                if (name.equals("min_z") || name.equals("max_z")) continue; // our own output roots
                if (ProjectionInputScanner.isDataset(sub)) out.add(sub);
            }
        }
        return out;
    }

    private static void processDataset(File datasetDir, ZProjectorDialog.Config cfg) throws Exception {
        DatasetScan scan = ProjectionInputScanner.scanDataset(datasetDir);

        String modeFolder = (cfg.mode == ZProjector.Mode.MAX_Z) ? "max_z" : "min_z";
        String rawPrefix  = modeFolder + "_projection_";

        // Output tree: <outputDir>/<modeFolder>/<modeFolder>_<datasetName>/{raw,z_origin,z_origin_32bit}
        File datasetOutDir = new File(new File(cfg.outputDir, modeFolder),
                modeFolder + "_" + datasetDir.getName());
        File rawDir = new File(datasetOutDir, "raw");
        File z16Dir = new File(datasetOutDir, "z_origin");
        File z32Dir = new File(datasetOutDir, "z_origin_32bit");
        z16Dir.mkdirs();
        z32Dir.mkdirs();
        if (cfg.writeRaw) rawDir.mkdirs();

        ProjectionExporter.writeMappings(datasetOutDir, scan.zValues);
        IJ.log(String.format("[ZProjector] '%s': %d z-layers (%s … %s), %d timepoint(s) → %s",
                datasetDir.getName(), scan.zLayerNames.size(),
                scan.zLayerNames.get(0), scan.zLayerNames.get(scan.zLayerNames.size() - 1),
                scan.timepointFilenames.size(), datasetOutDir.getAbsolutePath()));

        int total = scan.timepointFilenames.size();
        int skipped16 = 0;
        for (int t = 0; t < total; t++) {
            String filename = scan.timepointFilenames.get(t);
            IJ.showStatus("Projecting " + datasetDir.getName() + " — " + filename);
            IJ.showProgress(t, total);

            TimepointStack ts;
            try {
                ts = ProjectionInputScanner.loadTimepoint(scan, filename);
            } catch (Exception e) {
                IJ.log("[ZProjector]   skipped timepoint '" + filename + "': " + e.getMessage());
                continue;
            }

            ZProjector.Result result = ZProjector.project(cfg.mode, ts.slices, ts.globalZIndex);

            boolean wrote16 = ProjectionExporter.write16BitOrigin(z16Dir, filename, result.zOriginIndex);
            if (!wrote16) {
                skipped16++;
                IJ.log("[ZProjector]   16-bit z-origin skipped for '" + filename
                        + "': an index exceeds the uint16 range (" + ProjectionExporter.UINT16_MAX + ")");
            }
            ProjectionExporter.write32BitOrigin(z32Dir, filename, result.zOriginIndex);

            if (cfg.writeRaw) {
                ProjectionExporter.writeRaw(rawDir, rawPrefix, filename,
                        result.projection, ts.sourceBitDepth);
            }
        }
        IJ.showProgress(1.0);

        IJ.log(String.format("[ZProjector] '%s' done: %d timepoint(s)%s.",
                datasetDir.getName(), total,
                skipped16 > 0 ? " (" + skipped16 + " without a 16-bit z-origin — 32-bit still written)" : ""));
    }
}
