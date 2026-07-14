# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, Install & Test

```bash
mvn install
```

Produces `target/z-tracker-v4-pN.n.jar` (a fat JAR via `maven-assembly-plugin`), copies it
automatically to the local Fiji plugins folder, and verifies both steps succeeded. The plugin
appears under **Plugins > ZTracker > 3D Z-Coordinate Extractor** after restarting Fiji.

**Version number** — controlled by `<patch.version>` in `pom.xml` line 18. Increment the
patch number (e.g. `p1.3` → `p2.0`) for a major new capability, or the minor version
(e.g. `p1.3` → `p1.4`) for iterative fixes within the same feature.

**Auto-deploy path** — machine-specific. To use on a different machine, update
`<outputDirectory>` in `pom.xml` line 120.

**Build verification** — `maven-antrun-plugin` runs after the copy and fails the build with a
clear message if either the fat JAR in `target/` or the deployed file in the Fiji plugins
folder is missing.

Most testing is manual: build, install in Fiji, and run the dialog against sample JSON / TIFF /
CSV inputs, inspecting the `.npy`, CSV, and ROI `.zip` outputs. UI/AWT code is manual-only — it
requires a live display and has no testable business logic. Core algorithmic logic
(`ztracker.core`, `ztracker.io`, `ztracker.export`) has a growing JUnit 5 suite under
`src/test/java` (test-scoped dependency only — this does not violate the no-runtime-deps rule
below). Run with `mvn test`.

`src/test/java/ztracker/core/Step4AlignmentDemo.java` is a **runnable walkthrough** (not a test —
it has a `main`, no `@Test`, so Surefire ignores it but it stays compiled against the real
`FrameAligner`). It prints `suggestOffset` / `suggestOffsetFromEnd` / `validate` results over
several CSV-vs-TIFF alignment scenarios so the step-4 suggest-and-correct behaviour can be
eyeballed. Run instructions are in the class javadoc.

`src/test/java/ztracker/core/Step5MethodsDemo.java` is the same kind of runnable walkthrough for
step 5. It samples a synthetic TIFF frame (with a planted outlier pixel) using every
`ZSampler.Method`, aggregates with every `ZAggregator.Method` to show MEDIAN's outlier robustness
vs MEAN, runs `ZExtractor.extractAll`'s sampling × aggregation cross product (collapsed from 6 to
5 combos since `SINGLE_PIXEL` only runs once regardless of aggregation method — see the Pluggable
methods section), places a
detection at the image edge to show RADIUS/FOUR_NEIGHBOR getting clipped while SINGLE_PIXEL stays
in-bounds, contrasts a genuinely out-of-bounds detection against a genuinely missing frame to show
`ExtractionResult.outOfBoundsCount` vs `missingFrameCount` being reported separately, and actually
exports two combinations via `TrackExportManager` into a temp dir to print the resulting
`<sampling>/<aggregation>/...` folder tree plus the full contents of the `export_report.txt`
file written alongside the .npy output, and finally builds a track with both an invalid-X/Y
detection and a separate invalid-Z detection to print the asymmetry side by side (X/Y drops
from both 2D and 3D; Z drops from 3D only), plants a marker pixel to prove `ZExtractor` never
samples a NaN X/Y (rather than `Math.round(Double.NaN) == 0` silently treating it as `x=0`), and
exports a track with `.npy` off but Results Table CSV on to show the report's trailing
`"Results Table: N/M pt"` segment, and finally plants a pixel value absent from the Z mapping to
contrast a detection that samples *only* that unmapped pixel (fails with
`STATUS_UNMAPPED_INDEX` — a third NaN-Z cause distinct from a missing frame or out-of-bounds
position) against one that samples it alongside mapped neighbors (`ZAggregator`'s NaN-filtering
lets the aggregate still succeed). Run instructions are in the class javadoc.

## Critical Constraints

- **Must compile to Java 8 bytecode (class file version 52).** Fiji bundles a Java 8 JVM, so a higher target causes `UnsupportedClassVersionError` at plugin load time. `pom.xml` uses `maven-compiler-plugin` with `<release>8</release>` — **never raise this target.**
- **Only use Java 8 language features.** No switch expressions, no text blocks, no `var`, no `String.formatted()`. Streams and lambdas are fine.
- **No external runtime dependencies.** The only dependency is `net.imagej:ij` (scope `provided`, supplied by Fiji). This is deliberate: JSON is parsed by regex, NumPy `.npy` files are written in pure Java, and CSV is read with `BufferedReader`/`String.split`. Do not introduce libraries like Gson, Jackson, or Apache Commons.

## What the Plugin Does

Extracts 3D Z-coordinates from 16-bit or 32-bit *indexed* TIFF projection stacks and exports cell tracks. Each TIFF pixel value is an index into a JSON Z-mapping (`index → Z in µm`). Given 2D detections from a tracking CSV (X, Y, frame, track ID), it samples pixel indices around each detection, maps them to Z, aggregates, and exports 3D tracks. It is a native-Java port of `3D_tracking_Jay_app_unified_v1.py`.

## Data Format Conventions

The pipeline's core output is `.npy` files:

- **3D export**: shape `(N, >=4)`, column order **`[X, Y, Z, T]`**.
- **2D export**: shape `(N, 3)`, column order **`[X, Y, T]`**.
- **X and Y are in PIXELS** (taken from the tracking CSV; converted to physical units downstream).
- **Z is in MICROMETERS**, pre-converted via the JSON mapping before saving.
- **T is the frame number.**

The NPY writer (`NpyExporter`) is hand-rolled pure Java targeting the **NumPy v1.0 binary format**: 6-byte magic, header dict (`'<f8'`, `fortran_order: False`, shape), 64-byte alignment padding, little-endian `float64`, C-order. It **must stay byte-compatible with `numpy.load()`** — do not change layout, padding, or dtype. `NpyExporterTest` checks this directly (magic/version/dtype/fortran_order bytes, 64-byte header alignment across several row/column counts, exact little-endian float64 data, and `write2DTrack`/`write3DTrack`'s `[X,Y,T]`/`[X,Y,Z,T]` column order) using its own independent raw-byte reader, separate from `TrackExportManagerTest`'s — so a bug shared between the writer and one hand-rolled reader can't silently pass both suites. `TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat` and `FijiPointsExporterTest`'s coordinate-preservation tests additionally confirm X/Y (and Z where applicable) survive unchanged from `TrackData` input through to `.npy` (both dimensions), Results Table CSV, and the ROI `.zip` (decoded back via `ij.io.RoiDecoder`) — catching a column swap, wrong-index bug, or accidental unit conversion in any one format.

Other outputs: `FijiPointsExporter` writes one `PointROI` per detection (named `<trackID>_f<frame>`) into a ROI Manager `.zip`, plus a results-table CSV.

## Architecture

The entry point is `ZTrackerPlugin` (implements ImageJ's `PlugIn`). It is a **thin orchestrator with no business logic** — it wires together the pipeline and the dialog, **interleaving the multi-step dialog with I/O** so each user choice is validated against loaded data before the next step is shown:

1. **Dialog steps 1–2** — collect file paths + CSV format options.
2. **Load inputs** — `ZMappingLoader` (JSON regex), `TiffStackLoader` (TIFF folder → indexed stack), `TrackCsvLoader` (auto-detect CSV columns).
3. **Dialog step 3** — user confirms detected column mapping.
4. **Load CSV** into `TrackData`.
5. **Dialog steps 4–6** — frame offset, sampling/aggregation method, export config.
6. **Validate alignment** — `FrameAligner` checks CSV-vs-TIFF frame indexing.
7. **Extract** — `ZExtractor` produces an `ExtractionResult`.
8. **Export** — `TrackExportManager` groups by track and writes outputs (no whole-track filtering — every track is exported).

### Package structure & philosophy

Keep the package layout clean — `model` / `io` / `core` / `export` / `ui`, each with a **single responsibility**. Prefer small, focused classes over large ones. Keep business logic out of `ZTrackerPlugin`.

- `ztracker` — `ZTrackerPlugin` entry point (orchestration only).
- `ztracker.ui` — `ZTrackerDialog`, the 6-step dialog wizard. **All steps are non-modal** so the ImageJ Log window stays interactive/resizable while any step is open (the Step-4 per-track table lives in the Log). Steps 1, 4, 5, and 6 are custom AWT `Dialog`s created modeless (`new Dialog(..., false)`) and block the plugin thread with a `CountDownLatch` counted down on OK/Cancel/close — Step 1 is the resizable file picker, Step 4 the live-updating frame-alignment box, Step 5 the sampling/aggregation method picker (uses a live `ItemListener` to disable the aggregation `Choice` when Sampling is `SINGLE_PIXEL` alone — see the Pluggable methods section), Step 6 the output-directory-and-format picker (same `addInputGroup` grid layout as Step 1, with a `DirectoryChooser`-backed browse button instead of `GenericDialog.addDirectoryField`). Steps 2 and 3 use `NonBlockingGenericDialog` (ImageJ's non-modal `GenericDialog`, whose `showDialog()` still blocks the caller so the existing `wasCanceled()`/`getNext*()` usage is unchanged). Plugins run off the EDT, so blocking the plugin thread doesn't freeze the UI.
- `ztracker.io` — input loaders (`ZMappingLoader`, `TiffStackLoader`, `TrackCsvLoader`).
- `ztracker.core` — extraction logic (`FrameAligner`, `ZSampler`, `ZAggregator`, `ZExtractor`).
- `ztracker.export` — output writers (`NpyExporter`, `FijiPointsExporter`, `TrackExportManager`).
- `ztracker.model` — data containers (`TrackData`, `ExtractionResult`).

Menu registration lives in `src/main/resources/plugins.config`.

### Data model: parallel arrays

Detection data is held as **parallel arrays indexed by detection position**, not as per-detection objects:

- `TrackData` — `double[] x, y`, `int[] frame`, `String[] trackId`, `double[] radius` (NaN if absent), plus the resolved column-name metadata and default radius.
- `ExtractionResult` — `double[] z` (µm; NaN when extraction fails), `double[] zStd`, `int[] numSamples`, `int[] numUnmapped`.
- `TiffStackLoader`'s loaded stack — `int[][][] pixels` indexed `[stackIndex][y][x]` (16-bit or 32-bit indices), plus a `frame → stackIndex` map and sorted frame list (gaps are supported).

When editing extraction or export code, preserve the array-parallelism invariant: all arrays share the same length and index.

### Pluggable methods (enum-dispatched)

- `ZSampler.Method` — `RADIUS` (circular disk), `FOUR_NEIGHBOR` (bilinear corners), `SINGLE_PIXEL`.
- `ZAggregator.Method` — `MEDIAN`, `MEAN` (no `MODE` — removed as an option). Standard deviation uses the **population** divisor (n, not n−1).

To add a sampling or aggregation strategy, extend the relevant enum and its dispatch.

Step 5 of the dialog lets either axis (sampling method, aggregation method) be set to **"All"**
instead of a single choice — `ZExtractor.extractAll` runs every requested combination (the full
sampling × aggregation cross product when both axes are "All") and returns a
`List<ZExtractor.MethodCombo>`. **`SINGLE_PIXEL` is an exception**: it samples exactly one pixel
per detection, so every aggregation method produces an identical result — `extractAll` runs it
only once regardless of how many aggregation methods were requested, instead of once per method.
The Step-5 UI mirrors this: it's now a custom modeless AWT dialog (like Steps 1, 4, 6) with an
`ItemListener` on the sampling `Choice` that disables the aggregation `Choice` (pinned to Median,
never actually read downstream) whenever Sampling is set to `SINGLE_PIXEL` alone — it stays
enabled when Sampling is "All", since Radius and 4-Neighbor still need it.
`ZExtractor.resolveComboOutputDir` decides each combo's export folder: `outputDir/<sampling>/<aggregation>/...`
(e.g. `outputDir/radius/median/`) for non-single-pixel combos when more than one combination was
run, but `outputDir/single_pixel/` (no aggregation subfolder) for `SINGLE_PIXEL` — collapsed for
the same reason its extraction is deduped. A single chosen method still exports flat into
`outputDir` as before. `ZTrackerPlugin` just calls this resolver; it doesn't compute paths itself.

## Known Gotchas (real bugs we've already hit)

- **AWT Label.getFont() returns null before peer creation.** Calling `getFont().deriveFont(...)` on a freshly constructed `Label` that hasn't been added to a visible container will NPE. Always null-check and fall back to `new Font(Font.DIALOG, Font.PLAIN, 12)` before deriving a style.
- **Frame indexing mismatch.** Tracking CSVs are often 0-indexed while TIFF files start at frame 1. `FrameAligner` handles a configurable offset; the most common correct value is **+1**. **Always preserve the offset confirmation/preview step** in the dialog — silent misalignment corrupts results. The offset *suggestion* is start-anchored (`suggestOffset` = firstTiff − minCsvFrame) and is only a hint — the user confirms. The offset is a single constant applied to every detection, so the correctness test is per-frame: does `frame + offset` land on an existing TIFF for every detection (`missingFrameCount`)? **Do NOT infer a bad offset from the CSV frame range being shorter than the TIFF range** — a track that covers only part of the recording is normal (a cell appears then vanishes), so an end-anchored `lastTiff − maxCsvFrame` heuristic false-alarms and was removed. Instead, `validate` reports alignment **per track** (`AlignmentReport.perTrack`: each track's frame span, detection count, and how many detections map to a missing TIFF). The step-4 UI is a single custom AWT dialog that **live-updates** as the user edits the offset: the compact in-box verdict comes from `perTrackAlignment` + `buildBoxSummary`. So the user can **verify before confirming**, the full per-track table (capped at 50 rows — each track's span plus how its first/last frame maps) is written to the Fiji log via `validate` **once per distinct offset actually evaluated** (deduped against the last-logged offset, so keystrokes within one number don't re-dump), starting with the suggested offset when the box opens; on confirm it logs the table again under a `CONFIRMED` header. The confirm checkbox default tracks `all tracks fully mapped`. The dialog is **modeless** (blocks the plugin thread with a `CountDownLatch`, not by AWT modality) so the Log window stays interactive/resizable while it is open. Per-track rows are ordered by **track id** — numerically when the ids parse as integers (so `10` sorts after `2`, not lexicographically before it), otherwise lexicographically.
- **16-bit and 32-bit indexed TIFFs.** `TiffStackLoader` stores pixels as `int[][][]`. 16-bit frames read via `ImageProcessor.getPixel(x, y)`, which already returns the correct unsigned `0–65535` value. 32-bit frames are backed by a `FloatProcessor`, so indices are read via `getf(x, y)` and rounded with `Math.round()` — using `getPixel` on a float processor truncates toward zero and can be off-by-one. Mixed bit depths within one folder are rejected with a clear error; only 16-bit and 32-bit are supported (8-bit and 24-bit RGB are rejected).
- **CSV variety.** TrackMate CSVs have a header row followed by **3 metadata rows that must be skipped**. But not all inputs are TrackMate — some come from other trackers (e.g. columns `Track n°`, `Slice n°`, 1-based frames, latin-1 encoding, no metadata rows). Keep column detection **alias-based and tolerant**, not hard-coded to TrackMate.
- **Frame-number extraction uses the LAST digit run in the filename, not the first.** `TiffStackLoader.extractFrameNumber` must not just take the first regex match — filenames can contain incidental numbers before the real frame index (e.g. `z_origin_32bit_0007.tif`, where "32" from "32bit" is not the frame number). Taking the first match collapses every file to the same detected frame.
- **Out-of-bounds position vs. missing frame are different root causes — don't conflate them.** `ZSampler` silently drops any sampled pixel outside `[0,width) x [0,height)` rather than clamping or erroring; near an image edge, RADIUS's disk and FOUR_NEIGHBOR's corners can get **clipped** (fewer samples than usual), and a detection whose (x,y) is far off-grid returns **zero** samples from every method, exactly like a genuinely missing TIFF frame does. `ZExtractor.extract` disambiguates the zero-sample case by checking `stack.frameToIdx.containsKey(csvFrame+frameOffset)`: if the frame exists but sampling still failed, it's counted in `ExtractionResult.outOfBoundsCount`; if the frame itself isn't in the stack, it's `missingFrameCount`. Both `ZExtractor`'s per-run log line and `ZTrackerPlugin.logExtractionSummary` report them separately — a bad detection X/Y (fix: check the CSV) and a frame-offset problem (fix: revisit Step 4) look identical as a symptom (NaN Z) but need different fixes, so don't re-merge these into one counter. Per-detection, the *reason* for a NaN Z (`ExtractionResult.STATUS_MISSING_FRAME` / `STATUS_OUT_OF_BOUNDS` / `STATUS_UNMAPPED_INDEX`, or `STATUS_OK`) is stored in the parallel `ExtractionResult.sampleStatus` array, so downstream code (export, logging) can explain *why* a specific point failed, not just that it did.
- **A single bad point does NOT discard its whole track — only that point is dropped, from whichever export(s) it actually breaks.** `TrackExportManager.export` used to skip the *entire* track's 3D export if any one detection had a NaN Z (and, separately, used to skip the whole track from *both* 2D and 3D if any detection had a NaN X/Y); both whole-track exclusions are gone. There is also no whole-track quality filtering at all (no minimum-length or max-Z-std cutoff) — `TrackExportManager` exports every track it's given. Per point: an invalid X/Y (missing/unparseable position) drops that point from **both** 2D and 3D — a point with no position can't be placed in either — while a NaN Z (missing TIFF frame, out-of-bounds position, or every sampled index unmapped) drops it from **3D only** (2D never depended on Z). 2D and 3D are gated independently: each is skipped for a track only if it has **zero** valid points remaining for that dimension, tracked separately as `2D(noValidPoints)` / `3D(noValidPoints)`. **Note the 3D label is not "noValidZ"** — a 3D point requires both valid X/Y *and* valid Z, so a 3D shortfall can come from X/Y drops alone (with every Z perfectly fine), Z drops alone, or a mix; calling it "noValidZ" would misattribute the cause when X/Y is actually why. Covered by `export_summaryLine_reportsNoValidPointsSkipsSeparatelyFor2DAnd3D` and `export_invalidXYAndInvalidZInSameTrack_dropReasonsDoNotConflate` (the latter also confirms the two drop-reason tallies never conflate when both occur in the same track). The invalid-X/Y drop reason lives in `ExtractionResult.STATUS_INVALID_XY` — both `ZExtractor` (see the `Math.round(NaN)` gotcha below) and `TrackExportManager` (as a defense-in-depth re-check on `track.x[i]`/`track.y[i]` directly, for `TrackData` built without going through `ZExtractor`) reference the same shared constant, rather than each having their own string. A per-track report is logged on **every** export run (not just when something's wrong) via `TrackExportManager`'s own `[TrackExportManager] ── Per-track report ──` block — one line per track covering 2D and 3D separately, capped at 50 (`MAX_TRACKS_LOGGED`, same convention as `FrameAligner`'s per-track table), ordered by track id (numeric-aware, same `compareTrackIds` convention). The same report, **uncapped**, is also written to `export_report.txt` in each export's `outDir` (so per method-combo subfolder when multiple methods run), since the Fiji Log view's cap is for on-screen readability, not data loss. **Dropping a point never renumbers the surviving frame numbers** — both 2D's and 3D's `T` column always hold each kept detection's real `track.frame[i]`, so a dropped point leaves a genuine gap in the frame sequence (e.g. `0,1,3` if frame 2 was dropped) rather than a shift/compaction (never `0,1,2`) — this is intentional and covered by dedicated tests (`export_droppedPointFromMidTrack_leavesGenuineGapInFrameNumbers_notRenumbered`, and the X/Y equivalent `export_trackWithOneBadXYPoint_dropsFromBothDimensionsButKeepsTrack`) so it doesn't regress.
- **Unparseable X/Y at CSV load time is counted and logged, not silently swallowed.** `TrackCsvLoader.load` used to catch `NumberFormatException` around X/Y/Frame parsing together with a bare `// Silently skip malformed rows` — a row with a blank, garbage, or literal `"NaN"` X/Y (Java's `Double.parseDouble("NaN")` **succeeds**, returning `Double.NaN`, so it doesn't even throw) vanished with zero visibility. X/Y parsing is now isolated via `parseCoordinate` (returns `null` for blank/unparseable/NaN) with its own `skippedBadXY` counter and log line (`"Skipped N rows with missing/unparseable X or Y."`), mirroring the existing `skippedNaN` (Frame/Track_ID) counter. Genuinely malformed Frame/radius data still falls through to a separate `skippedOther` counter.
- **The per-track report/`export_report.txt` is generated on every export run regardless of Step-6 format checkboxes, but its 2D/3D wording is npy-specific.** The per-detection classification (`valid2D`/`valid3D`, drop-reason tallies) and the report/file-writing calls all run unconditionally — none of them are gated on `config.exportNpy`/`exportResultsTable`/`exportRoiSet`. But `buildDimensionPart` prints `"2D ✗ (npy export off)"` / `"3D ✗ (npy export off)"` whenever `config.exportNpy` is false, for **every** track regardless of whether that track's points were actually fine — because the 2D/3D verdict is specifically about the `.npy` output, which never ran. Similarly, `exported2D`/`exported3D`/`skipped2DNoValidPoints`/`skipped3DNoValidPoints` in the summary line only ever increment inside the `exportNpy` block, so they stay `0` even when Results Table CSV / ROI zip succeed for every track — those two counters are npy-only, not "any export succeeded." Since Results Table CSV and ROI zip aren't split into 2D/3D (a single flat table with an optional Z column, using the same `valid2D` point set 2D npy uses), `buildTrackReportLine` appends a **separate trailing segment** — `"| Results Table+ROI: N/M pt"` — whenever either is enabled, so a track's line doesn't read as "nothing was exported" when npy is off but another format is still capturing its points. This segment is independent of the 2D/3D verdict and appears (or not) based purely on `config.exportResultsTable`/`exportRoiSet`.
- **`Math.round(Double.NaN) == 0` in Java — a silent trap for any code sampling by rounded coordinates.** `ZExtractor.extract` used to call `ZSampler.sample(stack, track.x[i], track.y[i], ...)` unconditionally, with no check for `NaN` X/Y first. Since `ZSampler.sampleSinglePixel`/`sampleRadius` round `x`/`y` via `Math.round`, a `NaN` X was silently treated as `x=0` and produced a **real, "successful" `STATUS_OK` Z value** at a phantom pixel — not a failure. `TrackExportManager`'s own `Double.isNaN(track.x[i])` check (checked on the *original* coordinate, not the sampled result) still caught and dropped these before they reached any exported file, so `.npy` output was never wrong — but `ZExtractor`'s own log line and `ExtractionResult.countValid()` overcounted "valid" detections, and any future caller of `ExtractionResult` that doesn't route through `TrackExportManager`'s filter would get bogus data. Fixed by checking `Double.isNaN(track.x[i]) || Double.isNaN(track.y[i])` at the very top of the per-detection loop, *before* calling `ZSampler.sample` at all — marks `ExtractionResult.STATUS_INVALID_XY`, increments a new `invalidXYCount`, and both `ZExtractor`'s per-run log line and `ZTrackerPlugin.logExtractionSummary` report it. Regression-tested by `extract_nanX_isNeverSampled_notTreatedAsPixelZero`, which plants a distinctive marker value at pixel `(0, y)` and asserts it's never read.

### Format parsing details

- **JSON Z-mapping** — parsed with regex `"(\d+)"\s*:\s*(-?[\d.]+(?:[eE][+-]?\d+)?)`; supports negatives, decimals, and scientific notation.
- **TIFF loading** — files are natural-sorted by the last integer in the filename (the trailing frame index), so 0- vs 1-based numbering and gaps are handled, and incidental numbers earlier in the name don't get mistaken for the frame index.
- **CSV columns** — auto-detected case-insensitively with aliases (X/POSITION_X, Y/POSITION_Y, FRAME/T/TIME/TIMEPOINT/Slice n°, TRACK_ID/ID/Track n°, RADIUS/SIZE); user can override. Rows with blank/NaN Frame or Track_ID, or blank/unparseable/NaN X or Y, are skipped (each counted and logged separately — see the "unparseable X/Y" gotcha above). Default radius is 3.5 px when no radius column exists.
- **Export filtering** — `TrackExportManager` applies no whole-track quality filtering (no minimum length, no max-Z-std cutoff); every track is exported. 2D and 3D are gated per-point (see the "single bad point" gotcha above), skipped for a track only if that dimension has zero valid points.
