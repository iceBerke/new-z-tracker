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
vs MEAN, runs `ZExtractor.extractAll`'s full sampling × aggregation cross product, places a
detection at the image edge to show RADIUS/FOUR_NEIGHBOR getting clipped while SINGLE_PIXEL stays
in-bounds, contrasts a genuinely out-of-bounds detection against a genuinely missing frame to show
`ExtractionResult.outOfBoundsCount` vs `missingFrameCount` being reported separately, and actually
exports two combinations via `TrackExportManager` into a temp dir to print the resulting
`<sampling>/<aggregation>/...` folder tree. Run instructions are in the class javadoc.

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

The NPY writer (`NpyExporter`) is hand-rolled pure Java targeting the **NumPy v1.0 binary format**: 6-byte magic, header dict (`'<f8'`, `fortran_order: False`, shape), 64-byte alignment padding, little-endian `float64`, C-order. It **must stay byte-compatible with `numpy.load()`** — do not change layout, padding, or dtype.

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
8. **Export** — `TrackExportManager` groups by track, filters, and writes outputs.

### Package structure & philosophy

Keep the package layout clean — `model` / `io` / `core` / `export` / `ui`, each with a **single responsibility**. Prefer small, focused classes over large ones. Keep business logic out of `ZTrackerPlugin`.

- `ztracker` — `ZTrackerPlugin` entry point (orchestration only).
- `ztracker.ui` — `ZTrackerDialog`, the 6-step dialog wizard. **All steps are non-modal** so the ImageJ Log window stays interactive/resizable while any step is open (the Step-4 per-track table lives in the Log). Steps 1 and 4 are custom AWT `Dialog`s created modeless (`new Dialog(..., false)`) and block the plugin thread with a `CountDownLatch` counted down on OK/Cancel/close — Step 1 is the resizable file picker, Step 4 the live-updating frame-alignment box. Steps 2, 3, 5, 6 use `NonBlockingGenericDialog` (ImageJ's non-modal `GenericDialog`, whose `showDialog()` still blocks the caller so the existing `wasCanceled()`/`getNext*()` usage is unchanged). Plugins run off the EDT, so blocking the plugin thread doesn't freeze the UI.
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
`List<ZExtractor.MethodCombo>`. `ZTrackerPlugin` exports each combo to its own subfolder,
`outputDir/<sampling>/<aggregation>/...` (e.g. `outputDir/radius/median/`), only when more than
one combination was run — a single chosen method still exports flat into `outputDir` as before.

## Known Gotchas (real bugs we've already hit)

- **AWT Label.getFont() returns null before peer creation.** Calling `getFont().deriveFont(...)` on a freshly constructed `Label` that hasn't been added to a visible container will NPE. Always null-check and fall back to `new Font(Font.DIALOG, Font.PLAIN, 12)` before deriving a style.
- **Frame indexing mismatch.** Tracking CSVs are often 0-indexed while TIFF files start at frame 1. `FrameAligner` handles a configurable offset; the most common correct value is **+1**. **Always preserve the offset confirmation/preview step** in the dialog — silent misalignment corrupts results. The offset *suggestion* is start-anchored (`suggestOffset` = firstTiff − minCsvFrame) and is only a hint — the user confirms. The offset is a single constant applied to every detection, so the correctness test is per-frame: does `frame + offset` land on an existing TIFF for every detection (`missingFrameCount`)? **Do NOT infer a bad offset from the CSV frame range being shorter than the TIFF range** — a track that covers only part of the recording is normal (a cell appears then vanishes), so an end-anchored `lastTiff − maxCsvFrame` heuristic false-alarms and was removed. Instead, `validate` reports alignment **per track** (`AlignmentReport.perTrack`: each track's frame span, detection count, and how many detections map to a missing TIFF). The step-4 UI is a single custom AWT dialog that **live-updates** as the user edits the offset: the compact in-box verdict comes from `perTrackAlignment` + `buildBoxSummary`. So the user can **verify before confirming**, the full per-track table (capped at 50 rows — each track's span plus how its first/last frame maps) is written to the Fiji log via `validate` **once per distinct offset actually evaluated** (deduped against the last-logged offset, so keystrokes within one number don't re-dump), starting with the suggested offset when the box opens; on confirm it logs the table again under a `CONFIRMED` header. The confirm checkbox default tracks `all tracks fully mapped`. The dialog is **modeless** (blocks the plugin thread with a `CountDownLatch`, not by AWT modality) so the Log window stays interactive/resizable while it is open. Per-track rows are ordered by **track id** — numerically when the ids parse as integers (so `10` sorts after `2`, not lexicographically before it), otherwise lexicographically.
- **16-bit and 32-bit indexed TIFFs.** `TiffStackLoader` stores pixels as `int[][][]`. 16-bit frames read via `ImageProcessor.getPixel(x, y)`, which already returns the correct unsigned `0–65535` value. 32-bit frames are backed by a `FloatProcessor`, so indices are read via `getf(x, y)` and rounded with `Math.round()` — using `getPixel` on a float processor truncates toward zero and can be off-by-one. Mixed bit depths within one folder are rejected with a clear error; only 16-bit and 32-bit are supported (8-bit and 24-bit RGB are rejected).
- **CSV variety.** TrackMate CSVs have a header row followed by **3 metadata rows that must be skipped**. But not all inputs are TrackMate — some come from other trackers (e.g. columns `Track n°`, `Slice n°`, 1-based frames, latin-1 encoding, no metadata rows). Keep column detection **alias-based and tolerant**, not hard-coded to TrackMate.
- **Frame-number extraction uses the LAST digit run in the filename, not the first.** `TiffStackLoader.extractFrameNumber` must not just take the first regex match — filenames can contain incidental numbers before the real frame index (e.g. `z_origin_32bit_0007.tif`, where "32" from "32bit" is not the frame number). Taking the first match collapses every file to the same detected frame.
- **Out-of-bounds position vs. missing frame are different root causes — don't conflate them.** `ZSampler` silently drops any sampled pixel outside `[0,width) x [0,height)` rather than clamping or erroring; near an image edge, RADIUS's disk and FOUR_NEIGHBOR's corners can get **clipped** (fewer samples than usual), and a detection whose (x,y) is far off-grid returns **zero** samples from every method, exactly like a genuinely missing TIFF frame does. `ZExtractor.extract` disambiguates the zero-sample case by checking `stack.frameToIdx.containsKey(csvFrame+frameOffset)`: if the frame exists but sampling still failed, it's counted in `ExtractionResult.outOfBoundsCount`; if the frame itself isn't in the stack, it's `missingFrameCount`. Both `ZExtractor`'s per-run log line and `ZTrackerPlugin.logExtractionSummary` report them separately — a bad detection X/Y (fix: check the CSV) and a frame-offset problem (fix: revisit Step 4) look identical as a symptom (NaN Z) but need different fixes, so don't re-merge these into one counter.

### Format parsing details

- **JSON Z-mapping** — parsed with regex `"(\d+)"\s*:\s*(-?[\d.]+(?:[eE][+-]?\d+)?)`; supports negatives, decimals, and scientific notation.
- **TIFF loading** — files are natural-sorted by the last integer in the filename (the trailing frame index), so 0- vs 1-based numbering and gaps are handled, and incidental numbers earlier in the name don't get mistaken for the frame index.
- **CSV columns** — auto-detected case-insensitively with aliases (X/POSITION_X, Y/POSITION_Y, FRAME/T/TIME/TIMEPOINT/Slice n°, TRACK_ID/ID/Track n°, RADIUS/SIZE); user can override. Rows with blank/NaN Frame or Track_ID are skipped. Default radius is 3.5 px when no radius column exists.
- **Export filtering** — `TrackExportManager` applies a minimum track length (default 3 frames), an optional max-Z-std threshold, and separates 2D vs 3D output (excluding NaN-Z detections from 3D).
