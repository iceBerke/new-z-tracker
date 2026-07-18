# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, Install & Test

```bash
mvn install
```

Produces `target/z-tracker-v4-pN.n.jar` (a fat JAR via `maven-assembly-plugin`), copies it
automatically to the local Fiji plugins folder, removes any superseded `z-tracker-v4-*.jar`
there, and verifies both steps succeeded. The tools appear under **Plugins > ZTracker**
(in pipeline order: **Z-Projection + Origin Map**, **3D Z-Coordinate Extractor**, and
**3D Z-Extractor (TopoJ / direct-Z)**) after restarting Fiji.

**Version number** — controlled by `<patch.version>` in `pom.xml` line 18. Increment the
patch number (e.g. `p1.3` → `p2.0`) for a major new capability, or the minor version
(e.g. `p1.3` → `p1.4`) for iterative fixes within the same feature.

**Auto-deploy path** — machine-specific. To use on a different machine, update
`<outputDirectory>` in `pom.xml` line 126.

**Clean old JARs** — a `maven-antrun-plugin` `clean-old-jars` execution (install phase) deletes
any `z-tracker-v4-*.jar` in the Fiji plugins folder **except the current `<patch.version>`**, so
a version bump doesn't leave the previous JAR behind (which would make Fiji register the plugin
twice). It excludes the current version, so it is safe regardless of order relative to the copy —
it can never remove the freshly-built JAR.

**Build verification** — a second `maven-antrun-plugin` execution (`verify-deploy`) runs after
the copy and fails the build with a clear message if either the fat JAR in `target/` or the
deployed file in the Fiji plugins folder is missing.

Most testing is manual: build, install in Fiji, and run the dialog against sample JSON / TIFF /
CSV inputs, inspecting the `.npy`, CSV, and ROI `.zip` outputs. UI/AWT code is manual-only — it
requires a live display and has no testable business logic. Core algorithmic logic
(`ztracker.core`, `ztracker.io`, `ztracker.project`, `ztracker.export`) has a growing JUnit 5
suite under `src/test/java` (test-scoped dependency only — this does not violate the
no-runtime-deps rule below). Run with `mvn test`. Tool 1's suite is `ztracker.project.ZProjectorTest`
(projection logic: min/max selection, ties→first layer, missing-layer global-index remap — all
I/O-free), `ztracker.io.projector.ProjectionInputScannerTest` (the input scanner: numeric z-layer
sort with negatives/gaps and non-numeric folders ignored, timepoint-filename union/dedup/sort, and
— the subtle one — recording each present slice's **global** z-index when a timepoint is absent from
some layers; plus `isDataset`/`parseZ`, all via real headless TIFF I/O), and
`ztracker.export.projector.ProjectionExporterTest`, whose **seam test** writes the z-origin
TIFFs + JSON mapping and reads them back through the extractor's own `TiffStackLoader` +
`ZMappingLoader`, proving the project→extract round-trip actually holds (it also uses ImageJ's
real TIFF read/write headlessly, confirming that works in the test JVM).

`src/test/java/ztracker/core/Step4AlignmentDemo.java` is a **runnable walkthrough** (not a test —
it has a `main`, no `@Test`, so Surefire ignores it but it stays compiled against the real
`FrameAligner`). It prints `suggestOffset` / `suggestOffsetFromEnd` / `validate` results over
several CSV-vs-TIFF alignment scenarios so the step-4 suggest-and-correct behaviour can be
eyeballed. Run instructions are in the class javadoc.

`src/test/java/ztracker/core/extractor/Step5MethodsDemo.java` is the same kind of runnable walkthrough for
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
file written alongside the .npy output, then builds a track with both an invalid-X/Y
detection and a separate invalid-Z detection to print the asymmetry side by side (X/Y drops
from both 2D and 3D; Z drops from 3D only), plants a marker pixel to prove `ZExtractor` never
samples a NaN X/Y (rather than `Math.round(Double.NaN) == 0` silently treating it as `x=0`), and
exports a track with `.npy` off but Results Table CSV on to show the report's trailing
`"Results Table: N/M pt"` segment, then plants a pixel value absent from the Z mapping to
contrast a detection that samples *only* that unmapped pixel (fails with
`STATUS_UNMAPPED_INDEX` — a third NaN-Z cause distinct from a missing frame or out-of-bounds
position) against one that samples it alongside mapped neighbors (`ZAggregator`'s NaN-filtering
lets the aggregate still succeed), and finally contrasts the `PIXEL_CORNER` (the p7.0 default)
vs `PIXEL_CENTER` pixel coordinate conventions on the same sub-pixel detection (for
`SINGLE_PIXEL` and `FOUR_NEIGHBOR`) to show which pixel(s) each convention actually samples.
Run instructions are in the class javadoc.

`src/test/java/ztracker/export/Step6ExportDemo.java` is the same kind of runnable walkthrough for
step 6's export machinery — it lives in `ztracker.export`, not `ztracker.core` like the other
Step-N demos, since it's exercising `NpyExporter`/`FijiPointsExporter`/`TrackExportManager`
directly rather than dialog-step logic. It complements rather than duplicates
`ztracker.core.extractor.Step5MethodsDemo`'s export section (which shows the per-track report and
multi-method folder layout). `main` prints the input track (Track_ID, Frame, X, Y — as if loaded
from a tracking CSV) **and** a separately-labeled synthetic Z table (explicitly noted as "NOT read
from any input" — a real run computes Z via `ZExtractor`, a tracking CSV has no Z column) first
thing, before any section runs; both are then threaded through every later section as parameters
instead of each section declaring its own copy, so there's exactly one set of numbers in the whole
demo and no risk of e.g. section 3 quietly using different frame numbers than what was printed at
the top. It prints the actual bytes of an `NpyExporter`-written `.npy` file
(magic, version, header dict, the 64-byte alignment padding, and a little-endian float64 value
decoded back), confirms that padding stays aligned to a single 64-byte block across shapes from
`(1, 1)` up to `(999999999, 999999999)` since the header dict's fixed text dominates its length,
shows `write2DTrack`/`write3DTrack`'s exact `[X,Y,T]` / `[X,Y,Z,T]` column order using the same
shared input/Z, and finally exports that track through every format at once (`.npy` ×2, Results
Table CSV, ROI `.zip`) to print a side-by-side table tracing each detection's X/Y through all four
outputs, ending in an explicit `RESULT: ... MATCH` / `MISMATCH DETECTED` line — making clear that
X/Y are the only coordinates that actually originate in the input CSV, and that **this demo only
prints the comparison — it
makes no assertions itself**; the real pass/fail check is `NpyExporterTest`,
`FijiPointsExporterTest`, and
`TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat`, run via
`mvn test`. Run instructions are in the class javadoc.

## Critical Constraints

- **Must compile to Java 8 bytecode (class file version 52).** Fiji bundles a Java 8 JVM, so a higher target causes `UnsupportedClassVersionError` at plugin load time. `pom.xml` uses `maven-compiler-plugin` with `<release>8</release>` — **never raise this target.**
- **Only use Java 8 language features.** No switch expressions, no text blocks, no `var`, no `String.formatted()`. Streams and lambdas are fine.
- **No external runtime dependencies.** The only dependency is `net.imagej:ij` (scope `provided`, supplied by Fiji). This is deliberate: JSON is parsed by regex, NumPy `.npy` files are written in pure Java, and CSV is read with `BufferedReader`/`String.split`. Do not introduce libraries like Gson, Jackson, or Apache Commons.

## What the Plugin Does

The JAR registers **three tools** under `Plugins > ZTracker`:

Numbering follows **pipeline order** (produce → extract), not creation order.

**Tool 1 — Z-Projection + Origin Map (`ZProjectorPlugin`, added p8.0).** The upstream
*producer* of Tool 2's inputs. From a raw Z-stack (a folder of Z-layer sub-folders named by
their physical Z value, each holding one TIFF per timepoint), it computes a min-Z or max-Z
intensity projection and, per pixel, tracks **which Z-layer won** (`argmax`/`argmin`) as an
integer index. It writes the indexed z-origin TIFFs (16-bit and/or 32-bit, user-selectable —
default 16-bit) plus the matching `index → Z` JSON mapping — i.e. exactly what Tool 2 loads. Native-Java port of
`max_z_projection_plus_z_tracking_v2.py` / `min_z_projection_plus_z_tracking_v2.py` (the two
scripts differ only in min vs max, captured by one `ZProjector.Mode`).

**Tool 2 — 3D Z-Coordinate Extractor (`ZTrackerPlugin`).** Extracts 3D Z-coordinates from
16-bit or 32-bit *indexed* TIFF projection stacks and exports cell tracks. Each TIFF pixel
value is an index into a JSON Z-mapping (`index → Z in µm`). Given 2D detections from a
tracking CSV (X, Y, frame, track ID), it samples pixel indices around each detection, maps
them to Z, aggregates, and exports 3D tracks. Native-Java port of
`3D_tracking_Jay_app_unified_v1.py`. **Tools 1 and 2 share the indexed-TIFF and JSON-mapping
formats**, so the round-trip (project → extract) is a first-class invariant, locked in by
`ProjectionExporterTest`'s seam test (writes the outputs, reads them back through the
extractor's own `TiffStackLoader` + `ZMappingLoader`). The extractor's (Tool 2) code path was
entirely unchanged when the projector (Tool 1) was added.

**Tool 3 — 3D Z-Extractor (TopoJ / direct-Z) (`TopoJTrackerPlugin`, added p9.0).** A second
flavour of the extractor for projection images whose pixel value **is** the Z coordinate in µm
directly — e.g. Fiji's **TopoJ** height maps — rather than an index into a JSON mapping. It is
structurally Tool 2 *minus* the index → Z lookup: it loads a folder of **32-bit float** TIFFs
(`TopoJStackLoader`, values kept un-rounded; 16-bit/8-bit are rejected — those belong to Tool 2),
samples them (`TopoJSampler`, same geometry as `ZSampler`), and aggregates the sampled values
**as-is** (`TopoJExtractor`, no mapping). Native-Java port of `3D_tracking_Jay_app_v2.py` (which
used a 32-bit-float projection stack and its own crude "TIFF must start at 0" frame check). It
**deliberately supersedes** that script's old behaviour with Tool 2's improvements: the
suggest-and-confirm `FrameAligner` offset (Step 4), per-point drop (no whole-track NaN/min-length/
max-Z-std filtering), median/mean only (no mode), and all four export formats. Per the
isolate-per-tool philosophy the genuinely-different pieces are **duplicated** (loader, sampler,
extractor, dialog) so Tool 2 stays byte-for-byte untouched; the stack-agnostic contracts are
**reused** directly (`ZAggregator`, `TrackData`, `ExtractionResult`, `TrackCsvLoader`,
`TrackExportManager`, `NpyExporter`, `FijiPointsExporter`, and the `ZSampler.Method` /
`ZSampler.PixelConvention` / `ZAggregator.Method` enums). `FrameAligner` — coupled to
`TiffStackLoader.LoadedStack` but reading only its frame map, never its pixels — is reused via
`TopoJStackLoader.LoadedFloatStack.frameView()`, a pixel-less `LoadedStack` frame-index adapter.
Since there is no mapping, the shared `ExtractionResult.numUnmapped` field is always 0 for Tool 3
(`TopoJExtractor` doesn't track it — it passes a zero array at construction) and
`STATUS_UNMAPPED_INDEX` never arises; its direct-Z analogue `ExtractionResult.STATUS_NO_DATA`
(added p9.0) marks the case where pixels were sampled but every one was NaN (a no-data pixel in
the float map). `TopoJExtractorTest` / `TopoJSamplerTest` cover the identity-Z + failure-classification
logic (I/O-free), and `TopoJStackLoaderTest` proves float Z values survive load un-rounded through
ImageJ's real headless TIFF read/write, that non-32-bit inputs are rejected, and that the frame
index is read from whatever integer the filename **ends with** — any prefix, any zero-padding width
(`frame7.tif` / `topoj_0007.tif` / `height_map_00000100.tif`), rejecting names that don't end with
an integer (see the frame-number gotcha below).
`ExtractorEquivalenceTest` (in `ztracker.core.topoj`) is the **cross-tool parity proof**: it builds
an indexed stack + JSON map and the equivalent float stack (each pixel = its mapped Z, NaN if
unmapped), then asserts `ZExtractor` and `TopoJExtractor` produce identical `z`/`zStd`/`numSamples`
and missing/OOB/invalid tallies across every sampling × aggregation × convention combo — the one
allowed divergence (`numUnmapped`, and `STATUS_UNMAPPED_INDEX`↔`STATUS_NO_DATA`) asserted explicitly.
`ExtractorEquivalenceDemo` is the runnable, no-assertions walkthrough of the same comparison.

## Data Format Conventions

The pipeline's core output is `.npy` files:

- **3D export**: shape `(N, >=4)`, column order **`[X, Y, Z, T]`**.
- **2D export**: shape `(N, 3)`, column order **`[X, Y, T]`**.
- **X and Y are in PIXELS** (taken from the tracking CSV; converted to physical units downstream).
- **Z is in MICROMETERS**, pre-converted via the JSON mapping before saving (the indexed extractor, Tool 2) — or read straight from the float pixel value, which already is Z in µm (Tool 3 / TopoJ).
- **T is the frame number.**

The NPY writer (`NpyExporter`) is hand-rolled pure Java targeting the **NumPy v1.0 binary format**: 6-byte magic, header dict (`'<f8'`, `fortran_order: False`, shape), 64-byte alignment padding, little-endian `float64`, C-order. It **must stay byte-compatible with `numpy.load()`** — do not change layout, padding, or dtype. `NpyExporterTest` checks this directly (magic/version/dtype/fortran_order bytes, 64-byte header alignment across several row/column counts, exact little-endian float64 data, and `write2DTrack`/`write3DTrack`'s `[X,Y,T]`/`[X,Y,Z,T]` column order) using its own independent raw-byte reader, separate from `TrackExportManagerTest`'s — so a bug shared between the writer and one hand-rolled reader can't silently pass both suites. `TrackExportManagerTest.export_preservesInputXYCoordinatesIdenticallyAcrossEveryFormat` and `FijiPointsExporterTest`'s coordinate-preservation tests additionally confirm **X/Y** — the only coordinates actually present in the input CSV — survive unchanged through to `.npy` (both dimensions), Results Table CSV, and the ROI `.zip` (decoded back via `ij.io.RoiDecoder`), catching a column swap, wrong-index bug, or accidental unit conversion in any one format. Z is not an "input" in this sense — it's produced by `ZExtractor`, not read from the CSV — so these tests just confirm the already-computed Z value is written to the correct column/field, not that it round-trips from anywhere.

Other outputs: `FijiPointsExporter` writes one `PointROI` per detection (named `<trackID>_f<frame>`) into an XY ROI Manager `.zip`, plus a results-table CSV. It can also write XZ/YZ ROI `.zip` sets — `(X px, Z µm)`/`(Y px, Z µm)` per detection, Z unconverted, only for detections with a valid Z — via `ij.io.RoiEncoder` directly rather than through the on-screen `RoiManager`, which avoids a Swing list-rendering race the on-screen manager hit when all three ROI formats were written back-to-back (see README's p6.1 changelog entry).

## Architecture

There are **three `PlugIn` entry points**, all thin orchestrators with no business logic.

`ZProjectorPlugin` (Tool 1, added p8.0) is the simpler one: show `ZProjectorDialog` → resolve
the dataset(s) (single = the picked folder; batch = each sub-folder that scans as a dataset,
skipping the tool's own `min_z`/`max_z` output roots) → for each dataset, `ProjectionInputScanner`
discovers z-layers/timepoints, then **streams one timepoint at a time** (RAM-friendly, matching
the Python script): load its z-stack, `ZProjector.project` computes the projection + z-origin
index map, `ProjectionExporter` writes the z-origin TIFF(s) at the selected bit depth(s) (16-bit
and/or 32-bit — default 16-bit; a single depth ~halves the per-timepoint write work), the matching
JSON mapping(s), and the 8-bit raw projection (always written). If 16-bit is chosen alone and an
index exceeds the uint16 range there's no 32-bit fallback and the timepoint gets no z-origin file
(logged). The dialog can request **both** projections (Max-Z *and*
Min-Z); the plugin loops projection × dataset. Output nesting depends on scope: **batch** groups
each dataset under a projection-type folder (`<out>/max_z/max_z_<dataset>/`), while **single**
drops that redundant grouping level and writes `<out>/max_z_<dataset>/` directly (only one
dataset). Either way the `max_z`/`min_z` prefix keeps both projections' folders from colliding.
It reuses no `ZTrackerPlugin`/`ZTrackerDialog` code (the shared
folder-picker layout is deliberately **duplicated**, not extracted, so the extractor UI is
untouched) but does reuse the `io`/`model` format contracts so its output loads straight back
into Tool 2.

`ZTrackerPlugin` (Tool 2) wires together the extraction pipeline and dialog, **interleaving the
multi-step dialog with I/O** so each user choice is validated against loaded data before the next step is shown:

1. **Dialog steps 1–2** — collect file paths + CSV format options.
2. **Load inputs** — `ZMappingLoader` (JSON regex), `TiffStackLoader` (TIFF folder → indexed stack), `TrackCsvLoader` (auto-detect CSV columns).
3. **Dialog step 3** — user confirms detected column mapping.
4. **Load CSV** into `TrackData`.
5. **Dialog steps 4–6** — frame offset, sampling/aggregation method, export config.
6. **Validate alignment** — `FrameAligner` checks CSV-vs-TIFF frame indexing.
7. **Extract** — `ZExtractor` produces an `ExtractionResult`.
8. **Export** — `TrackExportManager` groups by track and writes outputs (no whole-track filtering — every track is exported).

`TopoJTrackerPlugin` (Tool 3, added p9.0) is Tool 2's orchestrator with the JSON-mapping load
removed: Steps 1–2 collect the **32-bit float TIFF folder** + CSV (no JSON), `TopoJStackLoader`
loads the float stack, Steps 3–6 run exactly as Tool 2's, then `TopoJExtractor.extractAll`
produces the `ExtractionResult`s (sampled float = Z, no lookup) and the **same**
`TrackExportManager` / `ZExtractor.resolveComboOutputDir` handle export. It reuses no
`ZTrackerPlugin`/`ZTrackerDialog` code (the dialog is a deliberate **duplicate**, `TopoJTrackerDialog`,
so Tool 2's dialog is untouched) but shares every stack-agnostic contract.

### Package structure & philosophy

Keep the package layout clean — `model` / `io` / `core` / `project` / `export` / `ui`, each with a **single responsibility**. Within `io` / `core` / `export` / `ui` and the root `ztracker` package, **tool-exclusive** classes are further grouped into per-tool subpackages — `projector` (Tool 1), `extractor` (Tool 2), `topoj` (Tool 3) — while classes **shared** across tools stay at the responsibility-package root. (`project` and `model` have no subpackages: `project` is entirely Tool 1's, and `model` is entirely shared.) A couple of `topoj → extractor` references remain by design, since Tool 3 deliberately reuses some of Tool 2's types (`TiffStackLoader.LoadedStack`, `ZExtractor.MethodCombo`/`resolveComboOutputDir`). Prefer small, focused classes over large ones. Keep business logic out of the entry-point classes.

- `ztracker` — entry points, one per tool subpackage: `projector.ZProjectorPlugin` (Tool 1), `extractor.ZTrackerPlugin` (Tool 2), `topoj.TopoJTrackerPlugin` (Tool 3) (orchestration only).
- `ztracker.ui` — dialogs, one per tool subpackage: `extractor.ZTrackerDialog` — the 6-step dialog wizard. **All steps are non-modal** so the ImageJ Log window stays interactive/resizable while any step is open (the Step-4 per-track table lives in the Log). Steps 1, 4, 5, and 6 are custom AWT `Dialog`s created modeless (`new Dialog(..., false)`) and block the plugin thread with a `CountDownLatch` counted down on OK/Cancel/close — Step 1 is the resizable file picker, Step 4 the live-updating frame-alignment box, Step 5 the sampling/aggregation/pixel-convention method picker (uses a live `ItemListener` to disable the aggregation `Choice` when Sampling is `SINGLE_PIXEL` alone — see the Pluggable methods section — plus a third `Choice` for `ZSampler.PixelConvention`, Corner listed first as the default, no "All" option), Step 6 the output-directory-and-format picker (same `addInputGroup` grid layout as Step 1, with a `DirectoryChooser`-backed browse button instead of `GenericDialog.addDirectoryField`). Steps 2 and 3 use `NonBlockingGenericDialog` (ImageJ's non-modal `GenericDialog`, whose `showDialog()` still blocks the caller so the existing `wasCanceled()`/`getNext*()` usage is unchanged). Plugins run off the EDT, so blocking the plugin thread doesn't freeze the UI.
  `topoj.TopoJTrackerDialog` (Tool 3) is a deliberate **duplicate** of `ZTrackerDialog` (so Tool 2's
  dialog stays byte-for-byte unchanged), with one substantive difference: **Step 1 collects only
  the TIFF folder + CSV** — there is no JSON-mapping picker. Steps 2–6 are identical, and Step 4
  reuses `FrameAligner` against `LoadedFloatStack.frameView()`.
  `projector.ZProjectorDialog` (Tool 1) is a single modeless AWT `Dialog` in the same style — a scope
  `Choice` (single/batch) asked **first**, then input/output `DirectoryChooser` pickers via a
  **copy** of `addInputGroup` (duplicated here on purpose so `ZTrackerDialog` is byte-for-byte
  unchanged; the input folder needs no description since scope already frames it), then a
  projection `Choice` (Max-Z / Min-Z / **Both**) and a Z-origin bit-depth `Choice` (16-bit /
  32-bit / **Both**, default 16-bit). There is no raw-projection toggle — the 8-bit raw is always
  written. `Config.modes` is a `List<ZProjector.Mode>` (one entry, or both); `Config.write16Bit`/
  `write32Bit` carry the bit-depth selection (at least one always true).
- `ztracker.io` — input loaders. Shared at root: `TrackCsvLoader`. `extractor.ZMappingLoader` + `extractor.TiffStackLoader` (indexed loaders, Tool 2); `topoj.TopoJStackLoader` (Tool 3's 32-bit float stack); `projector.ProjectionInputScanner` (Tool 1's z-layer/timepoint folder structure).
- `ztracker.core` — extraction logic. Shared at root: `FrameAligner` + `ZAggregator`. `extractor.ZSampler` + `extractor.ZExtractor` (Tool 2); `topoj.TopoJSampler` + `topoj.TopoJExtractor` (Tool 3 — duplicates of the sampler/extractor geometry over a float stack, values taken as Z directly).
- `ztracker.project` — projection logic (`ZProjector`: I/O-free min/max projection + per-pixel z-origin index map). Tool 1's counterpart to `core`; no subpackage since it's entirely Tool 1's.
- `ztracker.export` — output writers. Shared at root: `NpyExporter`, `FijiPointsExporter`, `TrackExportManager`. `projector.ProjectionExporter` (Tool 1's z-origin TIFFs + JSON mappings + raw projection).
- `ztracker.model` — data containers (`TrackData`, `ExtractionResult`) — shared, at root.

Menu registration for all three tools lives in `src/main/resources/plugins.config`.

### Data model: parallel arrays

Detection data is held as **parallel arrays indexed by detection position**, not as per-detection objects:

- `TrackData` — `double[] x, y`, `int[] frame`, `String[] trackId`, `double[] radius` (NaN if absent), plus the resolved column-name metadata and default radius.
- `ExtractionResult` — `double[] z` (µm; NaN when extraction fails), `double[] zStd`, `int[] numSamples`, `int[] numUnmapped`, plus per-detection/per-run metadata (`sampleStatus`, `samplingMethod`, `aggregationMethod`, `pixelConvention`, and the `missingFrameCount`/`outOfBoundsCount`/`invalidXYCount` tallies — see below).
- `TiffStackLoader`'s loaded stack — `int[][][] pixels` indexed `[stackIndex][y][x]` (16-bit or 32-bit indices), plus a `frame → stackIndex` map and sorted frame list (gaps are supported).

When editing extraction or export code, preserve the array-parallelism invariant: all arrays share the same length and index.

### Pluggable methods (enum-dispatched)

- `ZSampler.Method` — `RADIUS` (circular disk), `FOUR_NEIGHBOR` (bilinear corners), `SINGLE_PIXEL`.
- `ZAggregator.Method` — `MEDIAN`, `MEAN` (no `MODE` — removed as an option). Standard deviation uses the **population** divisor (n, not n−1).
- `ZSampler.PixelConvention` — `PIXEL_CORNER` (integer `i` = pixel `i`'s top-left corner,
  `[i, i+1)`, center at `i+0.5` — the **default**) or `PIXEL_CENTER` (integer `i` = pixel `i`'s
  center directly, `[i-0.5, i+0.5)` — the switchable alternate, and this plugin's original
  behavior). Unlike `Method`/`ZAggregator.Method`, this has no "All" option — always exactly
  one, threaded as a required parameter (no silent-default overload) through
  `ZSampler.sample`/`ZExtractor.extract`/`extractAll`/`ExtractionResult`. See the "pixel
  coordinate convention" gotcha below.

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
- **Frame-number extraction uses the LAST digit run in the filename, not the first.** `TiffStackLoader.extractFrameNumber` (Tool 2) must not just take the first regex match — filenames can contain incidental numbers before the real frame index (e.g. `z_origin_32bit_0007.tif`, where "32" from "32bit" is not the frame number). Taking the first match collapses every file to the same detected frame. **Tool 3 (`TopoJStackLoader`) does this more strictly on purpose** — for filename flexibility it anchors the frame index to the integer the base name (extension stripped) **ends with** (`(\d+)$`): any prefix, any zero-padding width (not hard-coded — `frame7.tif`, `topoj_0007.tif`, `height_map_00000100.tif` all work). Because it anchors to the end rather than "last run anywhere", it does **not** silently fall back to frame 0 for a name with no trailing integer (which would let several such files clobber each other in `frameToIdx`); it **rejects** them with a clear message up front. Tool 2's looser behavior is deliberately left byte-for-byte unchanged (isolate-per-tool).
- **Out-of-bounds position vs. missing frame are different root causes — don't conflate them.** `ZSampler` silently drops any sampled pixel outside `[0,width) x [0,height)` rather than clamping or erroring; near an image edge, RADIUS's disk and FOUR_NEIGHBOR's corners can get **clipped** (fewer samples than usual), and a detection whose (x,y) is far off-grid returns **zero** samples from every method, exactly like a genuinely missing TIFF frame does. `ZExtractor.extract` disambiguates the zero-sample case by checking `stack.frameToIdx.containsKey(csvFrame+frameOffset)`: if the frame exists but sampling still failed, it's counted in `ExtractionResult.outOfBoundsCount`; if the frame itself isn't in the stack, it's `missingFrameCount`. Both `ZExtractor`'s per-run log line and `ZTrackerPlugin.logExtractionSummary` report them separately — a bad detection X/Y (fix: check the CSV) and a frame-offset problem (fix: revisit Step 4) look identical as a symptom (NaN Z) but need different fixes, so don't re-merge these into one counter. Per-detection, the *reason* for a NaN Z (`ExtractionResult.STATUS_MISSING_FRAME` / `STATUS_OUT_OF_BOUNDS` / `STATUS_UNMAPPED_INDEX`, or `STATUS_OK`) is stored in the parallel `ExtractionResult.sampleStatus` array, so downstream code (export, logging) can explain *why* a specific point failed, not just that it did.
- **A single bad point does NOT discard its whole track — only that point is dropped, from whichever export(s) it actually breaks.** `TrackExportManager.export` used to skip the *entire* track's 3D export if any one detection had a NaN Z (and, separately, used to skip the whole track from *both* 2D and 3D if any detection had a NaN X/Y); both whole-track exclusions are gone. There is also no whole-track quality filtering at all (no minimum-length or max-Z-std cutoff) — `TrackExportManager` exports every track it's given. Per point: an invalid X/Y (missing/unparseable position) drops that point from **both** 2D and 3D — a point with no position can't be placed in either — while a NaN Z (missing TIFF frame, out-of-bounds position, or every sampled index unmapped) drops it from **3D only** (2D never depended on Z). 2D and 3D are gated independently: each is skipped for a track only if it has **zero** valid points remaining for that dimension, tracked separately as `2D(noValidPoints)` / `3D(noValidPoints)`. **Note the 3D label is not "noValidZ"** — a 3D point requires both valid X/Y *and* valid Z, so a 3D shortfall can come from X/Y drops alone (with every Z perfectly fine), Z drops alone, or a mix; calling it "noValidZ" would misattribute the cause when X/Y is actually why. Covered by `export_summaryLine_reportsNoValidPointsSkipsSeparatelyFor2DAnd3D` and `export_invalidXYAndInvalidZInSameTrack_dropReasonsDoNotConflate` (the latter also confirms the two drop-reason tallies never conflate when both occur in the same track). The invalid-X/Y drop reason lives in `ExtractionResult.STATUS_INVALID_XY` — both `ZExtractor` (see the `Math.round(NaN)` gotcha below) and `TrackExportManager` (as a defense-in-depth re-check on `track.x[i]`/`track.y[i]` directly, for `TrackData` built without going through `ZExtractor`) reference the same shared constant, rather than each having their own string. A per-track report is logged on **every** export run (not just when something's wrong) via `TrackExportManager`'s own `[TrackExportManager] ── Per-track report ──` block — one line per track covering 2D and 3D separately, capped at 50 (`MAX_TRACKS_LOGGED`, same convention as `FrameAligner`'s per-track table), ordered by track id (numeric-aware, same `compareTrackIds` convention). The same report, **uncapped**, is also written to `export_report.txt` in each export's `outDir` (so per method-combo subfolder when multiple methods run), since the Fiji Log view's cap is for on-screen readability, not data loss. **Dropping a point never renumbers the surviving frame numbers** — both 2D's and 3D's `T` column always hold each kept detection's real `track.frame[i]`, so a dropped point leaves a genuine gap in the frame sequence (e.g. `0,1,3` if frame 2 was dropped) rather than a shift/compaction (never `0,1,2`) — this is intentional and covered by dedicated tests (`export_droppedPointFromMidTrack_leavesGenuineGapInFrameNumbers_notRenumbered`, and the X/Y equivalent `export_trackWithOneBadXYPoint_dropsFromBothDimensionsButKeepsTrack`) so it doesn't regress.
- **Unparseable X/Y at CSV load time is counted and logged, not silently swallowed.** `TrackCsvLoader.load` used to catch `NumberFormatException` around X/Y/Frame parsing together with a bare `// Silently skip malformed rows` — a row with a blank, garbage, or literal `"NaN"` X/Y (Java's `Double.parseDouble("NaN")` **succeeds**, returning `Double.NaN`, so it doesn't even throw) vanished with zero visibility. X/Y parsing is now isolated via `parseCoordinate` (returns `null` for blank/unparseable/NaN) with its own `skippedBadXY` counter and log line (`"Skipped N rows with missing/unparseable X or Y."`), mirroring the existing `skippedNaN` (Frame/Track_ID) counter. Genuinely malformed Frame/radius data still falls through to a separate `skippedOther` counter.
- **The per-track report/`export_report.txt` is generated on every export run regardless of Step-6 format checkboxes, but its 2D/3D wording is npy-specific.** The per-detection classification (`valid2D`/`valid3D`, drop-reason tallies) and the report/file-writing calls all run unconditionally — none of them are gated on `config.exportNpy`/`exportResultsTable`/`exportRoiSet`. But `buildDimensionPart` prints `"2D ✗ (npy export off)"` / `"3D ✗ (npy export off)"` whenever `config.exportNpy` is false, for **every** track regardless of whether that track's points were actually fine — because the 2D/3D verdict is specifically about the `.npy` output, which never ran. Similarly, `exported2D`/`exported3D`/`skipped2DNoValidPoints`/`skipped3DNoValidPoints` in the summary line only ever increment inside the `exportNpy` block, so they stay `0` even when Results Table CSV / ROI zip succeed for every track — those two counters are npy-only, not "any export succeeded." Since Results Table CSV and ROI zip aren't split into 2D/3D (a single flat table with an optional Z column, using the same `valid2D` point set 2D npy uses), `buildTrackReportLine` appends a **separate trailing segment** — `"| Results Table+ROI: N/M pt"` — whenever either is enabled, so a track's line doesn't read as "nothing was exported" when npy is off but another format is still capturing its points. This segment is independent of the 2D/3D verdict and appears (or not) based purely on `config.exportResultsTable`/`exportRoiSet`.
- **The pixel coordinate convention default flip (p7.0) can change in/out-of-bounds status near frame edges.** `ZSampler.PixelConvention.PIXEL_CORNER` (floor to the containing cell) is now the Step-5 default, replacing the plugin's original `PIXEL_CENTER` (round to nearest) behavior — Center remains fully available as the alternate. Near zero or negative coordinates the two conventions can disagree about whether a detection is even in-bounds: `x=-0.4` rounds to `0` under Center (in-bounds) but floors to `-1` under Corner (out-of-bounds) — `Math.round` and `Math.floor` diverge for any negative non-integer value. **This is not a bug to "fix" by matching one convention's bounds behavior to the other** — it's an inherent, expected consequence of the two conventions disagreeing about which pixel a point belongs to, so don't special-case it away. If a user reports detections newly failing with `STATUS_OUT_OF_BOUNDS` after upgrading, the fix is switching the Step-5 convention back to Center (if that matches their tracker's own coordinate convention), not patching `ZSampler`'s bounds check.
- **`Math.round(Double.NaN) == 0` in Java — a silent trap for any code sampling by rounded coordinates.** `ZExtractor.extract` used to call `ZSampler.sample(stack, track.x[i], track.y[i], ...)` unconditionally, with no check for `NaN` X/Y first. Since `ZSampler.sampleSinglePixel`/`sampleRadius` round `x`/`y` via `Math.round`, a `NaN` X was silently treated as `x=0` and produced a **real, "successful" `STATUS_OK` Z value** at a phantom pixel — not a failure. `TrackExportManager`'s own `Double.isNaN(track.x[i])` check (checked on the *original* coordinate, not the sampled result) still caught and dropped these before they reached any exported file, so `.npy` output was never wrong — but `ZExtractor`'s own log line and `ExtractionResult.countValid()` overcounted "valid" detections, and any future caller of `ExtractionResult` that doesn't route through `TrackExportManager`'s filter would get bogus data. Fixed by checking `Double.isNaN(track.x[i]) || Double.isNaN(track.y[i])` at the very top of the per-detection loop, *before* calling `ZSampler.sample` at all — marks `ExtractionResult.STATUS_INVALID_XY`, increments a new `invalidXYCount`, and both `ZExtractor`'s per-run log line and `ZTrackerPlugin.logExtractionSummary` report it. Regression-tested by `extract_nanX_isNeverSampled_notTreatedAsPixelZero`, which plants a distinctive marker value at pixel `(0, y)` and asserts it's never read.

### Format parsing details

- **JSON Z-mapping** — parsed with regex `"(\d+)"\s*:\s*(-?[\d.]+(?:[eE][+-]?\d+)?)`; supports negatives, decimals, and scientific notation.
- **TIFF loading** — files are natural-sorted by the last integer in the filename (the trailing frame index), so 0- vs 1-based numbering and gaps are handled, and incidental numbers earlier in the name don't get mistaken for the frame index.
- **CSV columns** — auto-detected case-insensitively with aliases (X/POSITION_X, Y/POSITION_Y, FRAME/T/TIME/TIMEPOINT/Slice n°, TRACK_ID/ID/Track n°, RADIUS/SIZE); user can override. Rows with blank/NaN Frame or Track_ID, or blank/unparseable/NaN X or Y, are skipped (each counted and logged separately — see the "unparseable X/Y" gotcha above). Default radius is 3.5 px when no radius column exists.
- **Export filtering** — `TrackExportManager` applies no whole-track quality filtering (no minimum length, no max-Z-std cutoff); every track is exported. 2D and 3D are gated per-point (see the "single bad point" gotcha above), skipped for a track only if that dimension has zero valid points.
