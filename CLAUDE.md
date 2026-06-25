# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, Install & Test

```bash
mvn clean package
```

Produces `target/ZTracker_Fiji.jar`, a fat JAR (via `maven-assembly-plugin`) suitable for single-file Fiji distribution. Install by copying the JAR into `Fiji.app/plugins/` and restarting Fiji; the plugin then appears under **Plugins > ZTracker > 3D Z-Coordinate Extractor**.

Optional auto-deploy: uncomment the `maven-resources-plugin` block in `pom.xml`, set the `Fiji.app/plugins` path, and `mvn install` copies the JAR there automatically.

There is **no automated test suite**. Testing is manual: build, install in Fiji, and run the dialog against sample JSON / TIFF / CSV inputs, inspecting the `.npy`, CSV, and ROI `.zip` outputs.

## Critical Constraints

- **Must compile to Java 8 bytecode (class file version 52).** Fiji bundles a Java 8 JVM, so a higher target causes `UnsupportedClassVersionError` at plugin load time. `pom.xml` uses `maven-compiler-plugin` with `<release>8</release>` — **never raise this target.**
- **Only use Java 8 language features.** No switch expressions, no text blocks, no `var`, no `String.formatted()`. Streams and lambdas are fine.
- **No external runtime dependencies.** The only dependency is `net.imagej:ij` (scope `provided`, supplied by Fiji). This is deliberate: JSON is parsed by regex, NumPy `.npy` files are written in pure Java, and CSV is read with `BufferedReader`/`String.split`. Do not introduce libraries like Gson, Jackson, or Apache Commons.

## What the Plugin Does

Extracts 3D Z-coordinates from 16-bit *indexed* TIFF projection stacks and exports cell tracks. Each TIFF pixel value is an index into a JSON Z-mapping (`index → Z in µm`). Given 2D detections from a tracking CSV (X, Y, frame, track ID), it samples pixel indices around each detection, maps them to Z, aggregates, and exports 3D tracks. It is a native-Java port of `3D_tracking_Jay_app_unified_v1.py`.

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
- `ztracker.ui` — `ZTrackerDialog`, the 6-step `GenericDialog` wizard.
- `ztracker.io` — input loaders (`ZMappingLoader`, `TiffStackLoader`, `TrackCsvLoader`).
- `ztracker.core` — extraction logic (`FrameAligner`, `ZSampler`, `ZAggregator`, `ZExtractor`).
- `ztracker.export` — output writers (`NpyExporter`, `FijiPointsExporter`, `TrackExportManager`).
- `ztracker.model` — data containers (`TrackData`, `ExtractionResult`).

Menu registration lives in `src/main/resources/plugins.config`.

### Data model: parallel arrays

Detection data is held as **parallel arrays indexed by detection position**, not as per-detection objects:

- `TrackData` — `double[] x, y`, `int[] frame`, `String[] trackId`, `double[] radius` (NaN if absent), plus the resolved column-name metadata and default radius.
- `ExtractionResult` — `double[] z` (µm; NaN when extraction fails), `double[] zStd`, `int[] numSamples`, `int[] numUnmapped`.
- `TiffStackLoader`'s loaded stack — `short[][][] pixels` indexed `[stackIndex][y][x]` (16-bit unsigned indices), plus a `frame → stackIndex` map and sorted frame list (gaps are supported).

When editing extraction or export code, preserve the array-parallelism invariant: all arrays share the same length and index.

### Pluggable methods (enum-dispatched)

- `ZSampler.Method` — `RADIUS` (circular disk), `FOUR_NEIGHBOR` (bilinear corners), `SINGLE_PIXEL`.
- `ZAggregator.Method` — `MEDIAN`, `MEAN`, `MODE`. Standard deviation uses the **population** divisor (n, not n−1).

To add a sampling or aggregation strategy, extend the relevant enum and its dispatch.

## Known Gotchas (real bugs we've already hit)

- **Frame indexing mismatch.** Tracking CSVs are often 0-indexed while TIFF files start at frame 1. `FrameAligner` handles a configurable offset; the most common correct value is **+1**. **Always preserve the offset confirmation/preview step** in the dialog — silent misalignment corrupts results.
- **Unsigned 16-bit pixels.** TIFF Z-index values can exceed 32767, which overflows a signed Java `short`. Pixel reads **MUST mask with `& 0xFFFF`** to read them as unsigned. Do not "simplify" this away.
- **CSV variety.** TrackMate CSVs have a header row followed by **3 metadata rows that must be skipped**. But not all inputs are TrackMate — some come from other trackers (e.g. columns `Track n°`, `Slice n°`, 1-based frames, latin-1 encoding, no metadata rows). Keep column detection **alias-based and tolerant**, not hard-coded to TrackMate.

### Format parsing details

- **JSON Z-mapping** — parsed with regex `"(\d+)"\s*:\s*(-?[\d.]+(?:[eE][+-]?\d+)?)`; supports negatives, decimals, and scientific notation.
- **TIFF loading** — files are natural-sorted by the leading integer in the filename, so 0- vs 1-based numbering and gaps are handled.
- **CSV columns** — auto-detected case-insensitively with aliases (X/POSITION_X, Y/POSITION_Y, FRAME/T/TIME/Slice n°, TRACK_ID/ID/Track n°, RADIUS/SIZE); user can override. Rows with blank/NaN Frame or Track_ID are skipped. Default radius is 3.5 px when no radius column exists.
- **Export filtering** — `TrackExportManager` applies a minimum track length (default 3 frames), an optional max-Z-std threshold, and separates 2D vs 3D output (excluding NaN-Z detections from 3D).
