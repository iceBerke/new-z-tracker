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
import java.util.regex.Pattern;

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

    /**
     * "Does this name carry a frame number?" — the same test {@code TiffStackLoader} applies
     * to every file in a z-origin folder before it will load it (p10.2).
     */
    private static final Pattern ANY_DIGIT = Pattern.compile("\\d");

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
        int timepointsSkipped = 0;
        for (ZProjector.Mode mode : cfg.modes) {
            for (File datasetDir : datasets) {
                try {
                    timepointsSkipped += processDataset(datasetDir, cfg, mode);
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
        // A partial run must not read as a clean one: a dropped timepoint becomes a legal
        // frame gap downstream, which the extractor accepts without comment.
        if (timepointsSkipped > 0) {
            IJ.log("[ZProjector] WARNING: " + timepointsSkipped + " timepoint(s) were skipped and"
                    + " are missing from the output — see the 'skipped timepoint' lines above.");
        }
        if (unitsDone < unitsTotal) {
            IJ.log("[ZProjector] WARNING: " + (unitsTotal - unitsDone)
                    + " of " + unitsTotal + " unit(s) produced no output at all.");
        }
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

    // ── Cross-timepoint / output-name checks ───────────────────────────────────

    /**
     * Describes how a timepoint's projected frame differs in size from the dataset's first
     * written timepoint, or {@code null} when they match.
     *
     * <p>Unlike the cross-timepoint bit-depth change — which only logs a NOTE, because each
     * timepoint is projected independently and the z-origin output stores layer indices —
     * a size change is <b>not survivable downstream</b>. {@code TiffStackLoader} sizes its
     * pixel array from the first frame and rejects the whole folder if any later frame
     * disagrees (p10.1), so writing the odd-sized timepoint would make the <em>entire</em>
     * z-origin folder unloadable by Tool 2. Dropping just that timepoint leaves a frame gap,
     * which Tool 2 accepts. Hence the caller skips rather than warns.
     *
     * <p>The message names the reference size <em>and</em> where it came from, because the
     * reference is merely the first timepoint that happened to be written — it is not verified
     * against anything, so it can perfectly well be the outlier itself. See
     * {@link #dimensionSkipSummary}, which says so outright once the skips outnumber the keeps.
     */
    static String dimensionMismatch(String label, int width, int height,
                                    String refLabel, int refWidth, int refHeight) {
        if (width == refWidth && height == refHeight) return null;
        return "projects to " + width + "x" + height + ", but this dataset's reference size is "
                + refWidth + "x" + refHeight + ", taken from '" + refLabel + "' — the first"
                + " timepoint that was written. Every timepoint of one dataset must be the same"
                + " size: the extractor (Tool 2) refuses a z-origin folder whose frames disagree,"
                + " so writing this one would make the whole folder unloadable.";
    }

    /**
     * The per-dataset note closing a run that dropped timepoints for size, or {@code null}
     * when none were dropped.
     *
     * <p>Exists because the reference size is unverified: if the <em>first written</em>
     * timepoint is the odd-sized one, every later timepoint mismatches it and the run reports
     * a huge skip count while the single surviving timepoint is the actual problem. A user
     * reading "99 skipped" must not have to infer that. So once the skips are the majority,
     * this says plainly that the reference is the more likely culprit.
     */
    static String dimensionSkipSummary(int skippedForSize, int total, String refLabel,
                                       int refWidth, int refHeight) {
        if (skippedForSize <= 0) return null;
        String note = skippedForSize + " of " + total + " timepoint(s) skipped for not matching the"
                + " reference size " + refWidth + "x" + refHeight + " from '" + refLabel + "'.";
        if (skippedForSize * 2 >= total) {
            note += " That is most of this dataset, which usually means '" + refLabel + "' is"
                  + " itself the odd one out — it is the reference only because it was written"
                  + " first, not because it was checked against anything. Verify it before"
                  + " trusting the timepoint(s) that were kept.";
        }
        return note;
    }

    /**
     * Warns about a timepoint whose name carries no digits, or {@code null} when it does.
     *
     * <p>The z-origin filenames are {@code <prefix> + <timepoint name>}, so a digit-less
     * input name produces output the extractor cannot map to its real frame — differently per
     * depth, which is why both are spelled out rather than summarised as "no frame number":
     *
     * <ul>
     *   <li><b>16-bit</b> {@code z_origin_<name>} has no digits at all, so Tool 2 rejects the
     *       whole folder up front (p10.2).</li>
     *   <li><b>32-bit</b> {@code z_origin_32bit_<name>} <em>does</em> carry a digit run — the
     *       {@code 32} of {@code 32bit} — which the last-digit-run rule reads as frame 32. That
     *       is only refused when something <em>else</em> also resolves to 32 (a second
     *       digit-less name, or a genuine {@code 0032.tif}), as a duplicate (p10.4). Otherwise
     *       the folder loads with no error whatsoever and this timepoint simply sits at frame
     *       32 — the <b>worst</b> of the three outcomes, because nothing reports it, so the
     *       message states it first and as its own consequence.</li>
     * </ul>
     *
     * <p>Reported, not fixed: renaming the user's input is their call, and the projection
     * itself is perfectly good.
     */
    static String digitlessNameWarning(String filename, boolean write16, boolean write32) {
        if (ANY_DIGIT.matcher(filename).find()) return null;
        StringBuilder sb = new StringBuilder();
        // Not "carries no frame number" — the 32-bit name does carry one (the '32'), just the
        // wrong one, and the per-depth lines below have to be free to say so.
        sb.append("timepoint name '").append(filename).append("' contains no digits, so the")
          .append(" extractor cannot map its z-origin output to the right frame:");
        if (write16) {
            sb.append("\n  'z_origin_").append(filename).append("' — the extractor (Tool 2)")
              .append(" takes the frame index from the last run of digits in the filename,")
              .append(" finds none, and refuses the whole z_origin folder.");
        }
        if (write32) {
            sb.append("\n  'z_origin_32bit_").append(filename).append("' — this name DOES carry a")
              .append(" digit run: the '32' of '32bit'. The extractor takes the last digit run as")
              .append(" the frame index, so it reads this timepoint as frame 32. Alongside")
              .append(" normally-named timepoints the folder then loads with NO error at all and")
              .append(" this timepoint silently occupies frame 32 — worse than a refusal, because")
              .append(" nothing reports it and the Z coordinates come out attached to the wrong")
              .append(" frame. It is refused only if something else also resolves to 32 (a second")
              .append(" digit-less timepoint, or a real 0032.tif), as a duplicate frame number.");
        }
        sb.append("\n  Rename the input timepoint file to carry a frame index (e.g. 0001.tif)")
          .append(" and re-run.");
        return sb.toString();
    }

    /**
     * The one-line form of {@link #digitlessNameWarning}, for every offending timepoint after
     * the first in a dataset — or {@code null} when the name carries a digit.
     *
     * <p>Input naming is normally uniform, so the realistic case is <em>every</em> timepoint
     * lacking digits. Repeating the four-line explanation 100 times would bury the rest of the
     * run log, so the explanation is logged once per dataset and the remaining timepoints get
     * this line: the same facts still apply, they are just not restated. Says nothing the full
     * warning does not.
     */
    static String digitlessNameBrief(String filename, boolean write16, boolean write32) {
        if (ANY_DIGIT.matcher(filename).find()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("timepoint name '").append(filename).append("' also contains no digits → ");
        if (write16) sb.append('\'').append("z_origin_").append(filename).append('\'');
        if (write16 && write32) sb.append(", ");
        if (write32) sb.append('\'').append("z_origin_32bit_").append(filename).append('\'');
        sb.append(" (same as above).");
        return sb.toString();
    }

    /**
     * Projects one dataset for one mode.
     *
     * <p>Output folders and the {@code z_layer_mapping*.json} are created on the first
     * successfully projected timepoint, so a dataset that fails outright leaves nothing behind
     * rather than an empty tree plus an orphan mapping — which would look like a valid
     * extractor input folder.
     *
     * @return how many timepoints were skipped (0 when everything was written)
     * @throws IOException if <em>no</em> timepoint could be projected, naming the first failure
     */
    private static int processDataset(File datasetDir, ZProjectorDialog.Config cfg, ZProjector.Mode mode)
            throws Exception {
        ProjectionSource source = openSource(datasetDir, cfg);

        String modeFolder = (mode == ZProjector.Mode.MAX_Z) ? "max_z" : "min_z";
        String rawPrefix  = modeFolder + "_projection_";

        File datasetOutDir = resolveDatasetOutDir(cfg.outputDir, modeFolder, datasetDir.getName(), cfg.batch);
        File rawDir = new File(datasetOutDir, "raw");
        File z16Dir = new File(datasetOutDir, "z_origin");
        File z32Dir = new File(datasetOutDir, "z_origin_32bit");

        List<String> zLayerNames = source.zLayerNames();
        List<String> timepoints  = source.timepointLabels();

        IJ.log(String.format("[ZProjector] %s '%s': %d z-layers (%s … %s), %d timepoint(s) → %s",
                mode, datasetDir.getName(), zLayerNames.size(),
                zLayerNames.get(0), zLayerNames.get(zLayerNames.size() - 1),
                timepoints.size(), datasetOutDir.getAbsolutePath()));

        int total = timepoints.size();
        int written = 0;
        int skipped16 = 0;
        int previousBitDepth = -1;
        // Size of the first successfully written timepoint — every later one must match it.
        int refWidth = -1, refHeight = -1;
        String refLabel = null;
        int skippedForSize = 0;
        // The digit-less-name explanation is logged once per dataset, not once per timepoint.
        boolean digitlessExplained = false;
        String firstFailure = null;
        for (int t = 0; t < total; t++) {
            String filename = timepoints.get(t);
            IJ.showStatus("Projecting " + mode + " " + datasetDir.getName() + " — " + filename);
            IJ.showProgress(t, total);

            ProjectionSource.Projected projected;
            try {
                projected = source.projectTimepoint(mode, filename);
            } catch (Exception e) {
                if (firstFailure == null) firstFailure = filename + ": " + e.getMessage();
                IJ.log("[ZProjector]   skipped timepoint '" + filename + "': " + e.getMessage());
                continue;
            }

            int height = projected.result.zOriginIndex.length;
            int width  = height == 0 ? 0 : projected.result.zOriginIndex[0].length;

            // A size change between timepoints, unlike a depth change, cannot be written out
            // and flagged: it would make the whole z-origin folder unloadable by Tool 2 rather
            // than just this timepoint. Skip it (leaving a frame gap, which Tool 2 accepts) and
            // let the run summary's WARNING report the loss. Checked before the depth NOTE so a
            // dropped timepoint neither emits one nor becomes the "previous" depth.
            if (refWidth >= 0) {
                String mismatch = dimensionMismatch(filename, width, height,
                        refLabel, refWidth, refHeight);
                if (mismatch != null) {
                    IJ.log("[ZProjector]   skipped timepoint '" + filename + "': " + mismatch);
                    skippedForSize++;
                    continue;
                }
            }

            // Depths may differ between timepoints without corrupting anything — each
            // timepoint is projected on its own, and the z-origin output is layer indices —
            // so this is worth flagging, not failing.
            if (previousBitDepth >= 0 && projected.sourceBitDepth != previousBitDepth) {
                IJ.log("[ZProjector]   NOTE: '" + filename + "' is " + projected.sourceBitDepth
                        + "-bit, previous timepoint was " + previousBitDepth + "-bit."
                        + " Z-origin output is unaffected (it stores layer indices), but check"
                        + " if this is intentional.");
            }
            previousBitDepth = projected.sourceBitDepth;

            // Output folders and the mapping are created on first success, so a dataset that
            // fails outright leaves no empty tree + orphan JSON looking like valid Tool 2 input.
            if (written == 0) {
                if (cfg.write16Bit) z16Dir.mkdirs();
                if (cfg.write32Bit) z32Dir.mkdirs();
                rawDir.mkdirs(); // raw projection is always written
                ProjectionExporter.writeMappings(datasetOutDir, source.zValues(),
                        cfg.write16Bit, cfg.write32Bit);
                refWidth  = width;
                refHeight = height;
                refLabel  = filename;
            }
            written++;

            // The output names are '<prefix><timepoint name>', so a digit-less input name
            // yields output the extractor cannot map to its real frame. Report it at the point
            // of writing — the projection is fine, only the name is unusable downstream. The
            // full explanation is logged once per dataset (input naming is normally uniform, so
            // all 100 timepoints of a dataset typically offend); the rest get one line each.
            String nameWarning = digitlessExplained
                    ? digitlessNameBrief(filename, cfg.write16Bit, cfg.write32Bit)
                    : digitlessNameWarning(filename, cfg.write16Bit, cfg.write32Bit);
            if (nameWarning != null) {
                IJ.log("[ZProjector]   WARNING: " + nameWarning);
                digitlessExplained = true;
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

        // Nothing written means the dataset failed, however many timepoints were attempted —
        // report it as a failure naming the first cause, rather than "done" over empty output.
        if (written == 0) {
            throw new IOException("No timepoint could be projected (" + total + " attempted). "
                    + "First failure — " + firstFailure);
        }

        String skipNote = "";
        if (skipped16 > 0) {
            skipNote = cfg.write32Bit
                    ? " (" + skipped16 + " without a 16-bit z-origin — 32-bit still written)"
                    : " (" + skipped16 + " with NO z-origin — indices exceed uint16 and 32-bit was not selected)";
        }
        IJ.log(String.format("[ZProjector] %s '%s' done: %d of %d timepoint(s) written%s%s.",
                mode, datasetDir.getName(), written, total,
                written < total ? ", " + (total - written) + " skipped" : "", skipNote));
        // The reference size is unverified — it is just the first timepoint written — so a large
        // skip count may mean the reference, not the skipped timepoints, is the outlier.
        String sizeNote = dimensionSkipSummary(skippedForSize, total, refLabel, refWidth, refHeight);
        if (sizeNote != null) {
            IJ.log("[ZProjector]   WARNING: " + sizeNote);
        }
        return total - written;
    }
}
