# ZTracker Fiji Plugin

A Fiji/ImageJ plugin that extracts Z-coordinates from 16-bit or 32-bit indexed
TIFF projection stacks and exports 3D cell tracks. Ports the functionality of
`3D_tracking_Jay_app_unified_v1.py` into a native Fiji plugin.

---

## Project structure

```
ZTracker_Fiji/
├── pom.xml
└── src/main/
    ├── java/ztracker/
    │   ├── ZTrackerPlugin.java          ← plugin entry point
    │   ├── ui/ZTrackerDialog.java       ← 6-step dialog wizard, all non-modal (Steps 1, 4, 5, 6: custom AWT; Steps 2, 3: NonBlockingGenericDialog) so the Log stays usable
    │   ├── io/
    │   │   ├── ZMappingLoader.java      ← JSON index→Z parsing (no external lib)
    │   │   ├── TiffStackLoader.java     ← TIFF folder loader with frame→index map
    │   │   └── TrackCsvLoader.java      ← TrackMate CSV parser + column auto-detect
    │   ├── core/
    │   │   ├── FrameAligner.java        ← CSV-to-TIFF offset suggestion + per-track alignment reporting
    │   │   ├── ZSampler.java            ← radius / 4-neighbor / single-pixel sampling
    │   │   ├── ZAggregator.java         ← median / mean aggregation
    │   │   └── ZExtractor.java          ← orchestrates sampling + mapping + aggregation; `extractAll` runs the sampling × aggregation cross product (Single Pixel deduped to one run); `resolveComboOutputDir` picks each combo's export folder
    │   ├── export/
    │   │   ├── NpyExporter.java         ← writes [X,Y,Z,T] .npy (pure Java, no Python)
    │   │   ├── FijiPointsExporter.java  ← Results Table CSV + ROI Manager .zip
    │   │   └── TrackExportManager.java  ← groups by track, dispatches to exporters
    │   └── model/
    │       ├── TrackData.java           ← parallel arrays for all CSV detections
    │       └── ExtractionResult.java    ← per-detection Z result + quality stats
    └── resources/plugins.config         ← Fiji menu registration
```

---

## Build & Deploy (IntelliJ + Maven)

### Prerequisites
- JDK 8 or later
- Maven 3.6+ (bundled with IntelliJ)
- Internet access to resolve `net.imagej:ij` from the SciJava repository

### Steps

1. **Open the project** in IntelliJ: `File > Open` → select `ZTracker_Fiji/` folder.
   IntelliJ will detect `pom.xml` automatically.

2. **Build and deploy**:
   - Open the Maven tool window (`View > Tool Windows > Maven`)
   - Run `Lifecycle > install`
   - Or in the terminal: `mvn install`

3. **Output**: `target/z-tracker-v4-pN.n.jar` — automatically copied to the local
   Fiji plugins folder and verified. Restart Fiji to load the updated plugin.

### Versioning

The JAR filename is controlled by `<patch.version>` in `pom.xml` line 18:
- Increment the patch number (e.g. `p1.3` → `p2.0`) for a major new capability.
- Increment the minor version (e.g. `p1.3` → `p1.4`) for iterative fixes within the same feature.

### Adapting to a different machine

The Fiji plugins path is machine-specific. Update `<outputDirectory>` in `pom.xml` line 120
to point to your local `Fiji.app/plugins/` folder.

---

## Installation in Fiji

The build step handles deployment automatically — no manual copy needed.
After `mvn install` completes successfully, restart Fiji (or `Help > Refresh Menus`).
The plugin appears under `Plugins > ZTracker > 3D Z-Coordinate Extractor`.

---

## Usage

The plugin runs as a 6-step dialog wizard:

| Step | What you configure |
|------|--------------------|
| 1 | Z-mapping JSON, TIFF projection folder, tracking CSV (TrackMate and other formats) |
| 2 | CSV format (header row, skip rows, default radius) |
| 3 | Column names (auto-detected, editable) |
| 4 | CSV-to-TIFF frame offset — a live-updating box shows a per-track verdict as you type; a suggested offset is pre-filled, and the full per-track table (each track's span + how its first/last frame maps) is written to the Log for verification |
| 5 | Sampling method (Radius / 4-Neighbor / Single Pixel / **All**) + aggregation (Median / Mean / **All**) — aggregation is disabled when Sampling is Single Pixel alone, since aggregating one sample is a no-op |
| 6 | Output directory, export formats |

Either Step-5 axis can be set to **All** instead of a single choice, running the
sampling × aggregation cross product (`ZExtractor.extractAll`). **Single Pixel is an
exception**: it samples exactly one pixel per detection, so Median and Mean of that one
value are identical — it only runs (and exports) once regardless of how many aggregation
methods were requested, collapsing what would otherwise be a 3×2 = 6-combo run down to 5.
Each combination is exported to its own `outputDir/<sampling>/<aggregation>/` subfolder —
except Single Pixel, which collapses to `outputDir/single_pixel/` with no aggregation
subfolder; a single chosen method still exports flat into `outputDir` as before.

### How sampling works, precisely

- **Radius** samples every pixel within a circular disk (`radius` from the CSV or the
  Step-2 default) around the detection, rounded to the nearest pixel center.
- **4-Neighbor** samples the 4 bilinear corner pixels around the sub-pixel position
  (`floor(x)/ceil(x)` × `floor(y)/ceil(y)`).
- **Single Pixel** rounds the sub-pixel `(x, y)` to the **nearest** integer pixel first
  (`Math.round`), *then* checks whether that rounded pixel is in bounds — a detection is
  only rejected for being out of the image after rounding, never for having a non-integer
  coordinate.

For all three methods, any sampled pixel that falls outside `[0, width) × [0, height)` is
silently **dropped, not clamped** — near an image edge, Radius's disk and 4-Neighbor's
corners can end up with fewer samples than usual; Single Pixel either succeeds normally or
returns zero samples (never partial).

### Export formats

| Format | File | Use in Fiji |
|--------|------|-------------|
| `.npy` | `tracks_2D/track_XXXXX.npy`, `tracks_3D/track_XXXXX.npy` | Python downstream pipeline |
| Results Table CSV | `fiji/results_table.csv` | `Analyze > Import > Results…` |
| ROI set | `fiji/track_rois.zip` | ROI Manager `More >> Open…` |
| Export report | `export_report.txt` | Full, uncapped per-track breakdown of what was kept/dropped and why (see below) |

**Output directory layout.** With a single sampling/aggregation method chosen in Step 5,
everything exports flat into `outputDir`:

```
outputDir/
├── tracks_2D/track_00001.npy, track_00002.npy, ...   (if .npy enabled)
├── tracks_3D/track_00001.npy, track_00002.npy, ...   (if .npy enabled)
├── fiji/results_table.csv, track_rois.zip             (if Results Table / ROI set enabled)
└── export_report.txt
```

With either Step-5 axis set to **All**, each sampling × aggregation combination gets its
own `<sampling>/<aggregation>/` subfolder (each an independent copy of the layout above)
— except Single Pixel, which collapses to a bare `single_pixel/` folder with no
aggregation subfolder, since aggregating exactly one sample makes the aggregation method
meaningless:

```
outputDir/
├── radius/
│   ├── median/tracks_2D/, tracks_3D/, fiji/, export_report.txt
│   └── mean/tracks_2D/, tracks_3D/, fiji/, export_report.txt
├── four_neighbor/
│   ├── median/...
│   └── mean/...
└── single_pixel/tracks_2D/, tracks_3D/, fiji/, export_report.txt
```

### What happens to a bad detection (missing frame, out-of-bounds, or bad X/Y)

There is no whole-track quality filtering (no minimum track length, no max-Z-std cutoff)
— every track is exported. A track is **never discarded wholesale** for one bad
detection — only the specific bad point is dropped, and only from the export(s) it
actually breaks:

- **Invalid X/Y** (a detection whose X *or* Y — either one alone is enough — is missing,
  unparseable, or a literal `"NaN"` in the source CSV) drops that point from **both** 2D
  and 3D, since a point with no position can't be placed in either. This is caught in two
  places: `TrackCsvLoader` already skips such rows at CSV-load time (logged and counted
  separately from the Frame/Track_ID skip), and `TrackExportManager` re-checks defensively
  at export time.
- **A NaN Z** (missing TIFF frame, position/footprint out of image bounds, or every
  sampled pixel unmapped in the JSON) drops that point from **3D only** — 2D never
  depended on Z, so it's unaffected.
- Each dimension (2D, 3D) is skipped for a track only if it has **zero valid points
  remaining** for that dimension — 2D and 3D are gated independently, so 3D can be
  skipped while 2D still exports fine, or vice versa.
- **Dropping a point never renumbers the surviving frame numbers.** The `T` column always
  holds each kept detection's real original frame number, so a dropped point leaves a
  genuine gap in the sequence (e.g. `0,1,3` if frame 2 was dropped) — never a shift or
  compaction (never `0,1,2`). This is intentional: it distinguishes "this cell wasn't
  trackable here" from "the recording only ran this long."
- A per-track report is logged to the Fiji Log on **every** export run — regardless of
  which Step-6 formats you actually chose — (capped at 50 rows for on-screen readability)
  and written in full, uncapped, to `export_report.txt` alongside the `.npy` output — one
  line per track, showing 2D and 3D independently, e.g.:
  `Track A  2D ✓ (2/3 pt) — dropped 1: 1 invalid X/Y | 3D ✓ (1/3 pt) — dropped 2: 1 invalid X/Y, 1 missing frame`
- **The 2D/3D verdict is specifically about the `.npy` output.** If you leave the `.npy`
  checkbox unchecked in Step 6, every track's line reads
  `2D ✗ (npy export off) | 3D ✗ (npy export off)` — that's *not* a sign nothing was
  exported, it's just that this report has nothing to say about `.npy` since it never ran.
  Results Table CSV and ROI zip are tracked independently: whenever either is enabled, the
  line gets a trailing segment naming which format(s) and how many points made it in, e.g.
  `Track A  2D ✗ (npy export off) | 3D ✗ (npy export off) | Results Table: 3/3 pt` — so a
  track that only went into the CSV/ROI (with npy off) doesn't misleadingly look like a
  total export failure.
- A missing TIFF frame and an out-of-bounds position produce the identical symptom (NaN
  Z, zero samples) but need different fixes — a bad detection X/Y (check the CSV) vs. a
  frame-offset problem (revisit Step 4) — so they're counted and reported separately
  rather than lumped together as "missing frames."

---

## Input file requirements

Same as the Python pipeline:

- **JSON mapping**: `{"0": -600.0, "1": -599.0, ...}` (string keys, float values)
- **TIFF stack**: 16-bit unsigned integer or 32-bit indexed, one file per timepoint, numeric filenames (all frames must share the same bit depth)
- **CSV**: TrackMate or other tracker formats (alias-based column auto-detection); required columns: X, Y, Frame, Track_ID

---

## Output `.npy` format

Files are NumPy float64, C-order, no header row:

- `tracks_2D/track_XXXXX.npy` → columns `[X, Y, T]`
- `tracks_3D/track_XXXXX.npy` → columns `[X, Y, Z, T]`

Fully compatible with the existing Python smoothing and visualization scripts.

---

## Patch history

| Version | Description |
|---------|-------------|
| p1.0 | Auto-deploy enabled via `maven-resources-plugin`; versioned JAR filename introduced (`z-tracker-v4-pN.n`) |
| p1.1 | Fixed auto-deploy filename mismatch; suppressed duplicate thin JAR (`ZTracker_Fiji-1.0.0.jar`) |
| p1.2 | Added post-install build verification via `maven-antrun-plugin`; fixed missing version tags on Maven plugins |
| p1.3 | Eliminated all assembly warnings (`<attach>false</attach>`, skipped local repo install); updated CLAUDE.md and README |
| p2.0 | Replaced Step 1 `GenericDialog` with a custom resizable AWT dialog; file fields now stretch horizontally on window resize |
| p2.1 | Reworked Step 1 layout: each input shows a bold title, format description, `...` browse button, and selected path below |
| p2.2 | Fixed browse button rendering (emoji → `...`) and NPE from `Label.getFont()` returning null before native peer creation |
| p3.0 | Step 1 label/description tweaks: renamed "TIFF projection folder" to "Z-origin TIFF projection folder"; trimmed JSON description |
| p3.1 | Added 32-bit indexed TIFF support (`TiffStackLoader` now stores `int[][][]` pixels); fixed frame-number extraction to use the trailing digit run instead of the first (was misdetecting filenames like `z_origin_32bit_0007.tif`); introduced JUnit 5 test suite (`ZSamplerTest`, `TiffStackLoaderTest`) |
| p3.2 | Reworded Step 2 header text; fixed `TrackCsvLoader` alias lists to actually include `Track n°`/`Slice n°` as documented; added `TrackCsvLoaderTest` |
| p3.3 | Added a start/end range-consistency cross-check to frame alignment (superseded in p3.4); added `FrameAlignerTest` |
| p3.4 | Replaced the range-consistency heuristic (it false-alarmed on tracks that cover only part of the recording) with **per-track** alignment reporting (`AlignmentReport.perTrack`); correctness stays per-frame (`missingFrameCount`) |
| p3.5 | Step-4 confirmation reflects validation across **all** tracks: scope line + a checkbox that defaults off (and relabels) when any track has missing frames |
| p3.6 | Reworked Step 4 into a single **live-updating custom AWT dialog** (verdict + checkbox update as you type); compact `buildBoxSummary` verdict; full per-track table logged |
| p3.7 | Log the full per-track table **before** confirm too (once per distinct offset evaluated, deduped), not only on OK; `CONFIRMED` header on the final record |
| p3.8 | Made the Step-4 dialog **modeless** (blocks the plugin thread with a latch, not by AWT modality) so the Log stays interactive/resizable; order per-track rows by **track id** (numeric-aware, so `10` sorts after `2`). Later follow-up: extracted `buildPerTrackTable` for direct test coverage (no runtime change) |
| p3.9 | Made **all** dialog steps non-modal so the Log stays interactive/resizable throughout: Step 1 converted to a modeless custom dialog (latch-blocked like Step 4); Steps 2, 3, 5, 6 switched to `NonBlockingGenericDialog` |
| p4.0 | Step 5 can run **All** sampling and/or aggregation methods (`ZExtractor.extractAll`, full cross product, one output subfolder per combo); removed the Mode aggregation option entirely |
| p4.1 | Disambiguated "TIFF frame missing" vs "frame exists but sampled position/footprint out of bounds" — separate `ExtractionResult.missingFrameCount`/`outOfBoundsCount`, both logged |
| p4.2 | A track is no longer discarded from 3D wholesale for one NaN-Z detection — only that point is dropped, keeping the rest; 3D is skipped only if too few valid points remain. Per-track report logged every export run |
| p4.3 | Per-track report also written in full (uncapped) to `export_report.txt` alongside the `.npy` output; added a test locking in that a dropped point leaves a genuine frame-number gap, never a renumbered/compacted sequence |
| p4.4 | Applied the same keep-track/drop-point treatment to invalid X/Y (previously excluded the whole track); `TrackCsvLoader` now counts and logs unparseable/blank/NaN X or Y rows instead of silently dropping them |
| p4.5 | Fixed a misleading `3D(insufficientValidZ)` summary label (a 3D shortfall can come from X/Y drops alone, not just Z) to `3D(insufficientValidPoints)`; closed test gaps confirming invalid-X/Y and invalid-Z drop reasons don't conflate when both occur in the same track |
| p4.6 | Fixed a latent bug: `ZExtractor` never checked for NaN X/Y before sampling, and `Math.round(Double.NaN) == 0` in Java meant a NaN X was silently treated as `x=0`, producing a bogus "valid" Z at a phantom pixel instead of failing (final `.npy` output was unaffected — `TrackExportManager` already caught it downstream — but `ZExtractor`'s own log/counts overcounted). Now checked up front with a new `ExtractionResult.STATUS_INVALID_XY`/`invalidXYCount`, shared with `TrackExportManager` instead of a separate local constant |
| p4.7 | Per-track report now notes when Results Table CSV / ROI zip are enabled, even with `.npy` off — previously a track's line read `2D ✗ (npy export off) \| 3D ✗ (npy export off)` regardless of whether its points landed fine in another format, misleadingly reading as a total export failure |
| p5.0 | Rebuilt Step 6 as a custom AWT dialog matching Step 1's file-picker style (`DirectoryChooser` browse button + aligned grid) instead of `GenericDialog.addDirectoryField`, fixing unrendered "──" dash glyphs; removed whole-track quality filtering (minimum length, max Z std dev) from both the UI and `TrackExportManager` — every track is now exported, with 2D/3D gated per-dimension only when it has zero valid points |
| p5.1 | Synced README.md with the p5.0 changes (it still described the removed filters and old Step 6 dialog) and added an ASCII output-tree diagram documenting the actual on-disk export layout, which wasn't shown anywhere before |
| p5.2 | `ZExtractor.extractAll` now runs `SINGLE_PIXEL` only once regardless of how many aggregation methods are requested — aggregating exactly one sample makes Median/Mean identical, so running both was pure duplicate work; `resolveComboOutputDir` collapses its export folder to `single_pixel/` (no aggregation subfolder) to match. Step 5 is now a custom AWT dialog (like Steps 1, 4, 6) that disables the aggregation choice whenever Sampling is Single Pixel alone |
| p5.3 | Added `NpyExporterTest` (previously zero direct tests on the hand-rolled `.npy` writer despite CLAUDE.md's byte-compatibility requirement) covering magic/version/dtype bytes, 64-byte header alignment, and `[X,Y,T]`/`[X,Y,Z,T]` column order; added coordinate-preservation tests confirming X/Y — the only coordinates actually present in the input CSV (Z is computed by `ZExtractor`, not read from the CSV) — survive unchanged through every export format: 2D npy, 3D npy, Results Table CSV, and ROI `.zip` (decoded via `ij.io.RoiDecoder`) |
| p5.4 | Doc wording fix: clarified that only X/Y come from the input CSV — Z is produced by `ZExtractor`, not "preserved from input" — after p5.3's docs implied otherwise |
| p5.5 | Added `Step6ExportDemo.java`, a runnable walkthrough of the `.npy` byte format (magic/version/header dict/64-byte alignment) and a side-by-side trace of one track's X/Y through every export format at once (2D npy, 3D npy, Results Table CSV, ROI zip), complementing `Step5MethodsDemo`'s export section rather than duplicating it |
