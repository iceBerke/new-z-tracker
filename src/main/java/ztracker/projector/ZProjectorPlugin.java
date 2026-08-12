package ztracker.projector;

import ij.IJ;
import ij.plugin.PlugIn;
import ztracker.export.projector.ProjectionExporter;
import ztracker.io.projector.FolderProjectionSource;
import ztracker.io.projector.ProjectionInputScanner;
import ztracker.io.projector.ProjectionSource;
import ztracker.io.projector.ProjectionStackScanner;
import ztracker.project.ZProjector;
import ztracker.ui.projector.ZProjectorDialog;

import java.io.File;
import java.io.IOException;
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
 *
 * <p>Two input layouts are supported, chosen in the dialog and differing only in where the
 * images and Z values come from — {@link FolderProjectionSource} (one sub-folder per Z depth,
 * Z from the folder names) and {@link ProjectionStackScanner} (one multi-slice TIFF per
 * timepoint, Z from the slice labels). Both arrive here as a {@link ProjectionSource}, so
 * everything downstream of the scan — projection, export, output layout — is shared.
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
            IJ.error("ZTracker", noDatasetsMessage(cfg));
            return;
        }

        String depthStr = (cfg.write16Bit && cfg.write32Bit) ? "16+32-bit"
                        : cfg.write16Bit ? "16-bit" : "32-bit";
        IJ.log(String.format("[ZProjector] Projection(s): %s | Input: %s | Scope: %s | Z-origin: %s | %d dataset(s)",
                cfg.modes, cfg.stackInput ? "TIFF stacks" : "Z-layer sub-folders",
                cfg.batch ? "batch" : "single", depthStr, datasets.size()));

        // Each requested projection × each dataset is one unit of work. Running both
        // projections writes into separate max_z / min_z output trees, so they never collide.
        int unitsDone = 0;
        int unitsTotal = datasets.size() * cfg.modes.size();
        for (ZProjector.Mode mode : cfg.modes) {
            for (File datasetDir : datasets) {
                try {
                    processDataset(datasetDir, cfg, mode);
                    unitsDone++;
                } catch (Exception e) {
                    IJ.log("[ZProjector] SKIPPED " + mode + " for dataset '"
                            + datasetDir.getName() + "': " + e.getMessage());
                }
            }
        }

        IJ.showStatus("Z-Projection complete.");
        IJ.showProgress(1.0);
        IJ.log(String.format("\n[ZProjector] Done. %d / %d (dataset × projection) unit(s) processed. Output under: %s",
                unitsDone, unitsTotal, cfg.outputDir.getAbsolutePath()));
        IJ.log("========================================\n");
    }

    // ── Orchestration helpers ──────────────────────────────────────────────────

    /**
     * Single scope → the input folder itself is the dataset. Batch scope → each
     * sub-folder that scans as a valid dataset for the chosen input type (numeric z-layer
     * sub-folders, or TIFF stacks sitting directly inside), skipping the tool's own
     * {@code min_z}/{@code max_z} output roots.
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
                boolean isDataset = cfg.stackInput
                        ? ProjectionStackScanner.isDataset(sub)
                        : ProjectionInputScanner.isDataset(sub);
                if (isDataset) out.add(sub);
            }
        }
        return out;
    }

    /** The "nothing to do" message, worded for the chosen input type and scope. */
    static String noDatasetsMessage(ZProjectorDialog.Config cfg) {
        String path = cfg.inputDir.getAbsolutePath();
        if (cfg.stackInput) {
            return cfg.batch
                    ? "No datasets containing TIFF stacks found under:\n" + path
                    : "No .tif stacks found in:\n" + path;
        }
        return cfg.batch
                ? "No datasets with numeric Z-layer sub-folders found under:\n" + path
                : "No numeric Z-layer sub-folders found in:\n" + path;
    }

    /** Opens a dataset in whichever layout the user selected. */
    private static ProjectionSource openSource(File datasetDir, ZProjectorDialog.Config cfg)
            throws IOException {
        return cfg.stackInput
                ? ProjectionStackScanner.scanDataset(datasetDir)
                : FolderProjectionSource.scanDataset(datasetDir);
    }

    /**
     * Resolves a dataset's output folder (which holds {@code raw/}, {@code z_origin/},
     * {@code z_origin_32bit/} and the {@code z_layer_mapping*.json} files).
     *
     * <ul>
     *   <li><b>Batch:</b> {@code <outputDir>/<modeFolder>/<modeFolder>_<datasetName>/} — the extra
     *       {@code <modeFolder>/} level groups the many datasets tidily.</li>
     *   <li><b>Single:</b> {@code <outputDir>/<modeFolder>_<datasetName>/} — there is only one
     *       dataset, so that grouping level is redundant and dropped.</li>
     * </ul>
     *
     * Either way the {@code <modeFolder>} prefix on the dataset folder name keeps the two
     * projection types (max_z_* / min_z_*) from colliding when Both is chosen.
     */
    static File resolveDatasetOutDir(File outputDir, String modeFolder, String datasetName, boolean batch) {
        String datasetFolderName = modeFolder + "_" + datasetName;
        return batch
                ? new File(new File(outputDir, modeFolder), datasetFolderName)
                : new File(outputDir, datasetFolderName);
    }

    private static void processDataset(File datasetDir, ZProjectorDialog.Config cfg, ZProjector.Mode mode)
            throws Exception {
        ProjectionSource source = openSource(datasetDir, cfg);

        String modeFolder = (mode == ZProjector.Mode.MAX_Z) ? "max_z" : "min_z";
        String rawPrefix  = modeFolder + "_projection_";

        File datasetOutDir = resolveDatasetOutDir(cfg.outputDir, modeFolder, datasetDir.getName(), cfg.batch);
        File rawDir = new File(datasetOutDir, "raw");
        File z16Dir = new File(datasetOutDir, "z_origin");
        File z32Dir = new File(datasetOutDir, "z_origin_32bit");
        if (cfg.write16Bit) z16Dir.mkdirs();
        if (cfg.write32Bit) z32Dir.mkdirs();
        rawDir.mkdirs(); // raw projection is always written

        List<String> zLayerNames = source.zLayerNames();
        List<String> timepoints  = source.timepointLabels();

        ProjectionExporter.writeMappings(datasetOutDir, source.zValues(), cfg.write16Bit, cfg.write32Bit);
        IJ.log(String.format("[ZProjector] %s '%s': %d z-layers (%s … %s), %d timepoint(s) → %s",
                mode, datasetDir.getName(), zLayerNames.size(),
                zLayerNames.get(0), zLayerNames.get(zLayerNames.size() - 1),
                timepoints.size(), datasetOutDir.getAbsolutePath()));

        int total = timepoints.size();
        int skipped16 = 0;
        for (int t = 0; t < total; t++) {
            String filename = timepoints.get(t);
            IJ.showStatus("Projecting " + mode + " " + datasetDir.getName() + " — " + filename);
            IJ.showProgress(t, total);

            ProjectionSource.Projected projected;
            try {
                projected = source.projectTimepoint(mode, filename);
            } catch (Exception e) {
                IJ.log("[ZProjector]   skipped timepoint '" + filename + "': " + e.getMessage());
                continue;
            }

            ZProjector.Result result = projected.result;

            if (cfg.write16Bit) {
                boolean wrote16 = ProjectionExporter.write16BitOrigin(z16Dir, filename, result.zOriginIndex);
                if (!wrote16) {
                    skipped16++;
                    String fallback = cfg.write32Bit
                            ? "32-bit still written"
                            : "NO z-origin written for this timepoint — re-run with 32-bit or Both";
                    IJ.log("[ZProjector]   16-bit z-origin skipped for '" + filename
                            + "': an index exceeds the uint16 range (" + ProjectionExporter.UINT16_MAX
                            + ") — " + fallback);
                }
            }
            if (cfg.write32Bit) {
                ProjectionExporter.write32BitOrigin(z32Dir, filename, result.zOriginIndex);
            }
            ProjectionExporter.writeRaw(rawDir, rawPrefix, filename,
                    result.projection, projected.sourceBitDepth);
        }
        IJ.showProgress(1.0);

        String skipNote = "";
        if (skipped16 > 0) {
            skipNote = cfg.write32Bit
                    ? " (" + skipped16 + " without a 16-bit z-origin — 32-bit still written)"
                    : " (" + skipped16 + " with NO z-origin — indices exceed uint16 and 32-bit was not selected)";
        }
        IJ.log(String.format("[ZProjector] %s '%s' done: %d timepoint(s)%s.",
                mode, datasetDir.getName(), total, skipNote));
    }
}
