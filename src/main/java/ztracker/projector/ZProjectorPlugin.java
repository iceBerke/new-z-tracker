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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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

    /**
     * A run of digits — used to pick out a label's <em>last</em> one, which is the timepoint
     * index under the same rule {@code TiffStackLoader.extractFrameNumber} applies (p10.4).
     */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    @Override
    public void run(String arg) {
        IJ.log("========================================");
        IJ.log("  ZTracker — Z-Projection + Origin Map");
        IJ.log("========================================");

        ZProjectorDialog dialog = new ZProjectorDialog();
        if (!dialog.showDialog()) return;
        ZProjectorDialog.Config cfg = dialog.getConfig();

        // Marks the run boundary for the scanner's once-per-run note about sub-folders it did not
        // read as Z layers. A bare static signal carrying no data — the alternative, threading a
        // collector up through ProjectionSource, would put type-specific state on the boundary
        // that keeps every check below type-independent. Unconditional on purpose: the stack input
        // type simply never populates it, so there is no per-type branch here either.
        ProjectionInputScanner.resetIgnoredSubFolderReport();

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
        // Kept so a run that produces nothing can state the reason in its dialog rather than
        // sending the user to the Log — see the unitsDone == 0 branch below.
        List<String> failures = new ArrayList<>();
        for (ZProjector.Mode mode : cfg.modes) {
            for (File datasetDir : datasets) {
                try {
                    timepointsSkipped += processDataset(datasetDir, cfg, mode);
                    unitsDone++;
                } catch (Exception e) {
                    IJ.log("[ZProjector] SKIPPED " + mode + " for dataset '"
                            + datasetDir.getName() + "': " + e.getMessage());
                    failures.add(mode + " '" + datasetDir.getName() + "': " + e.getMessage());
                }
            }
        }

        // A run that wrote nothing is a failure, not a partial success: "complete" / "Done."
        // would contradict its own 0-count, and a user who picks one folder and never opens the
        // Log would see a plugin that silently did nothing. So this case gets its own wording
        // and a dialog, following the noDatasetsMessage precedent above. Note SKIPPED stays the
        // per-dataset verb — it is accurate in a batch run where other datasets succeeded; only
        // the run-level framing changes, and only when nothing at all was produced.
        if (unitsDone == 0) {
            IJ.showStatus("Z-Projection failed — nothing was written.");
            IJ.showProgress(1.0); // >= 1 clears the bar (ij ProgressBar.show), not "shows 100%"
            IJ.log(String.format("\n[ZProjector] FAILED — nothing was written."
                            + " 0 of %d (dataset × projection) unit(s) produced output."
                            + " Intended output folder: %s",
                    unitsTotal, cfg.outputDir.getAbsolutePath()));
            IJ.log("========================================\n");
            IJ.error("ZTracker — Z-Projection failed", writeNothingMessage(unitsTotal, failures));
            return;
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

    /** Longest dialog line before wrapping — see {@link #wrapForDialog}. */
    private static final int DIALOG_WRAP_COLUMNS = 90;

    /** How many failure reasons the dialog spells out before deferring to the Log. */
    private static final int MAX_FAILURES_IN_DIALOG = 3;

    /**
     * The dialog text for a run that produced no output at all — self-contained, carrying the
     * actual reason(s) rather than telling the user to check the Log.
     *
     * <p>That is deliberate: {@code IJ.error} is <b>modal</b>, so while it is up the user cannot
     * scroll the Log window. A dialog saying "see the Log" while blocking the Log would be worse
     * than no dialog at all.
     *
     * <p>Only the first {@link #MAX_FAILURES_IN_DIALOG} reasons are spelled out — a batch run
     * with forty failed datasets would otherwise produce an unreadable dialog — and the count of
     * the rest is stated explicitly rather than silently dropped.
     */
    static String writeNothingMessage(int unitsTotal, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Nothing was written.")
          .append("\n \n0 of ").append(unitsTotal)
          .append(" (dataset × projection) unit(s) produced output.");
        int shown = Math.min(failures.size(), MAX_FAILURES_IN_DIALOG);
        if (shown > 0) {
            sb.append("\n \n").append(failures.size() == 1 ? "Reason:" : "Reasons:");
            for (int i = 0; i < shown; i++) {
                sb.append("\n \n").append(failures.get(i));
            }
            if (failures.size() > shown) {
                sb.append("\n \n…and ").append(failures.size() - shown)
                  .append(" more — the full list is in the Log window.");
            }
        }
        return wrapForDialog(sb.toString());
    }

    /**
     * Hard-wraps at word boundaries, preserving existing line breaks.
     *
     * <p>Needed because ImageJ's {@code MessageDialog} renders through {@code MultiLineLabel},
     * which splits on {@code '\n'} only — a lone {@code StringTokenizer(text, "\n")}, no word
     * wrapping (verified in ij 1.54f) — so a 200-character line becomes a dialog wider than the
     * screen. Blank lines are kept as a single space, since {@code StringTokenizer} skips truly
     * empty tokens and would otherwise collapse them.
     */
    private static String wrapForDialog(String text) {
        StringBuilder out = new StringBuilder();
        String[] paragraphs = text.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            if (p > 0) out.append('\n');
            String paragraph = paragraphs[p];
            if (paragraph.trim().isEmpty()) {
                out.append(' ');
                continue;
            }
            int lineLen = 0;
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                if (lineLen > 0 && lineLen + 1 + word.length() > DIALOG_WRAP_COLUMNS) {
                    out.append('\n');
                    lineLen = 0;
                }
                if (lineLen > 0) {
                    out.append(' ');
                    lineLen++;
                }
                out.append(word);
                lineLen += word.length();
            }
        }
        return out.toString();
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
     * Rejects a dataset whose timepoint filenames do not all carry a digit run — returning the
     * error message, or {@code null} when every name is fine.
     *
     * <p><b>A timepoint's identity is the number in its filename.</b> A digit-free name means the
     * file states no position in the sequence, so the dataset has no timepoint ordering at all:
     * that is malformed input, and this refuses it outright rather than producing output from it.
     *
     * <p>Checked over {@link ProjectionSource#timepointLabels()} <em>before any projection or
     * output</em>, and reporting <em>every</em> offending name at once — mirroring
     * {@code TiffStackLoader.load}, which validates all filenames up front and throws with the
     * full offender list rather than failing one file at a time (p10.2).
     *
     * <p>Deliberately takes no bit-depth arguments, so it is depth-independent <b>by
     * construction</b> rather than by policy: a name that states no timepoint is malformed
     * whatever is being written from it. The per-depth consequences are supporting detail in the
     * message, not its justification — they are symptoms of the missing index, and leading with
     * them would read as a consumer quirk to be worked around.
     */
    static String missingTimepointIndexError(List<String> timepointLabels) {
        List<String> offenders = new ArrayList<>();
        for (String label : timepointLabels) {
            if (!ANY_DIGIT.matcher(label).find()) offenders.add(label);
        }
        if (offenders.isEmpty()) return null;
        return "These timepoint files carry no timepoint index — their names contain no digits: "
                + offenders
                + "\nA timepoint's identity is the number in its filename, so a digit-free name"
                + " leaves the file with no position in the sequence and this dataset with no"
                + " timepoint ordering at all. Nothing has been written."
                + "\nRename every timepoint file to carry its index (e.g. 0001.tif, 0002.tif) and"
                + " re-run."
                + "\nFor reference, what the output would have been: the 16-bit z-origin"
                + " (z_origin_<name>) would carry no frame number and the extractor would refuse"
                + " the folder, while the 32-bit z-origin (z_origin_32bit_<name>) would have the"
                + " '32' of '32bit' read as its frame number and load under the wrong frame.";
    }

    /**
     * Rejects a dataset where two timepoint filenames claim the same position in the sequence —
     * returning the error message, or {@code null} when every index is unique.
     *
     * <p>Distinct filenames are not distinct <em>indices</em>: {@code run1_0007.tif} and
     * {@code run2_0007.tif} both resolve to timepoint 7. Two files cannot hold one position, so
     * this is the same class of malformed input as a name with no index at all, and gets the
     * same treatment — refused up front, listing every collision grouped by the index collided
     * on, mirroring {@code TiffStackLoader.load}'s p10.4 message so producer and consumer read
     * alike. Not a correctness fix: p10.4 already refuses such a folder loudly at load time.
     * This just fails in seconds instead of after a full projection run.
     *
     * <p><b>Computed on the label, which provably equals the output-name index</b> — the
     * question that matters is what index the <em>output</em> file resolves to, but every label
     * reaching here carries a digit run of its own (the digit-free check ran first), and a
     * label's digits always sit after the {@code z_origin_32bit_} prefix, so the label's last
     * run wins the last-digit-run rule for both {@code z_origin_<label>} and
     * {@code z_origin_32bit_<label>} alike. Verified against
     * {@code TiffStackLoader.extractFrameNumber}: {@code z_origin_run1_0007.tif} and
     * {@code z_origin_32bit_run1_0007.tif} both give 7.
     *
     * <p>Grouped by <b>numeric value</b>, not by the raw digit text, because mixed zero-padding
     * widths are legal and {@code z_7.tif} / {@code z_0007.tif} really do collide while
     * {@code z_0008.tif} does not (same distinction p10.4 draws). Leading zeros are stripped
     * rather than parsed, which gives {@code Integer.parseInt}'s equivalence classes without
     * risking an overflow on an absurd digit run.
     */
    static String duplicateTimepointIndexError(List<String> timepointLabels) {
        Map<String, List<String>> byIndex = new LinkedHashMap<>();
        for (String label : timepointLabels) {
            String digits = lastDigitRun(label);
            if (digits == null) continue; // digit-free: missingTimepointIndexError's business
            byIndex.computeIfAbsent(stripLeadingZeros(digits), k -> new ArrayList<>()).add(label);
        }
        List<String> collisions = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byIndex.entrySet()) {
            if (e.getValue().size() > 1) {
                collisions.add("index " + e.getKey() + " ← " + e.getValue());
            }
        }
        if (collisions.isEmpty()) return null;
        return "These timepoint files claim the same position in the sequence — they resolve to the"
                + " same timepoint index: " + collisions
                + "\nA timepoint's index is the last run of digits in its filename, so"
                + " run1_0007.tif and run2_0007.tif both read as timepoint 7, and 7.tif and"
                + " 0007.tif both read as 7 whatever the padding. Two files cannot hold one"
                + " position in the sequence. Nothing has been written."
                + "\nRename every timepoint file so each carries its own index (e.g. 0001.tif,"
                + " 0002.tif) and re-run."
                + "\nFor reference, what the output would have been: the extractor derives the"
                + " frame index the same way, so it would refuse the z-origin folder as duplicate"
                + " frame numbers.";
    }

    /** The last run of digits in {@code name}, or {@code null} if it holds none. */
    private static String lastDigitRun(String name) {
        Matcher m = DIGIT_RUN.matcher(name);
        String last = null;
        while (m.find()) last = m.group();
        return last;
    }

    /** {@code "0007"} → {@code "7"}, {@code "000"} → {@code "0"}. */
    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') i++;
        return digits.substring(i);
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
     * @throws IOException if a timepoint filename carries no timepoint index, or if two resolve
     *                     to the same index — both refused up front, before any projection or
     *                     output — or if <em>no</em> timepoint could be projected, naming the
     *                     first failure
     *
     * <p><b>Four behaviours of this loop are NOT covered by any test</b> — they are unreachable
     * without widening this method's visibility <em>and</em> {@code ZProjectorDialog.Config}'s
     * constructor, which was costed and declined. <b>Re-verify them by hand in Fiji when you
     * change this loop:</b> a dimension-skipped timepoint (1) counts into the {@code total -
     * written} return, (2) therefore reaches the run summary's WARNING, (3) never increments
     * {@code written} so it cannot read as a success, and (4) is skipped <em>before</em> the
     * bit-depth NOTE, so it neither emits one nor becomes {@code previousBitDepth}. All four are
     * p10.6's exact failure mode. See {@code docs/DECISIONS.md} for why they are left untested.
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

        // Pre-flight, before any projection or output: a timepoint whose name states no index
        // has no place in the sequence, so the dataset has no ordering and is malformed. Refuse
        // the whole dataset naming every offender, rather than skipping timepoints (a skip
        // manufactures a frame gap the extractor accepts silently, and since input naming is
        // normally uniform, skipping them all would write nothing at all).
        String nameError = missingTimepointIndexError(timepoints);
        if (nameError != null) {
            throw new IOException(nameError);
        }

        // Must stay AFTER the digit-free check, for two reasons. A digit-free name has no index
        // to compare in the first place; and its *output* index would come from the '32' of the
        // z_origin_32bit_ prefix, so it would differ between the two depths. With digit-free
        // names already refused, every label carries its own digit run, which always sits after
        // that prefix and so wins the last-digit-run rule identically for z_origin_<label> and
        // z_origin_32bit_<label> — which is precisely what makes this check depth-independent.
        // Reordering these two would break that.
        String duplicateError = duplicateTimepointIndexError(timepoints);
        if (duplicateError != null) {
            throw new IOException(duplicateError);
        }

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

            // ORDER IS LOAD-BEARING: the size skip above must stay ahead of this NOTE. Move it
            // below and a skipped timepoint starts emitting a depth NOTE and becoming
            // previousBitDepth, which is p10.6's failure mode again. NO TEST COVERS THIS —
            // re-verify by hand in Fiji if you reorder here (see the method javadoc).
            //
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
