# ZTracker Fiji Plugin

A Fiji/ImageJ plugin with **three tools** under `Plugins > ZTracker`, covering the pipeline:

1. **Z-Projection + Origin Map** — *step 1 (produce)*: builds the indexed TIFF projections and
   their JSON Z-mappings from a raw Z-stack (min-Z or max-Z projection with per-pixel z-origin
   tracking). Ports `max_z_projection_plus_z_tracking_v2.py` /
   `min_z_projection_plus_z_tracking_v2.py`.
2. **3D Z-Coordinate Extractor** — *step 2 (extract)*: reads those 16-bit or 32-bit indexed TIFF
   projection stacks and, given 2D tracks, extracts the Z-coordinate for every detection and
   exports 3D cell tracks. Ports `3D_tracking_Jay_app_unified_v1.py`.
3. **3D Z-Extractor (TopoJ / direct-Z)** — *step 2, alternative*: the same extraction for
   projection images whose **pixel value is the Z depth in µm directly** (32-bit float, e.g.
   Fiji's **TopoJ** output) — no index, no JSON mapping. Ports `3D_tracking_Jay_app_v2.py`,
   superseding that script's old frame-check/filtering with the current `FrameAligner` offset and
   per-point drop logic.

Tools 1 and 2 are a matched pair: step 1's `z_origin/` folder + `z_layer_mapping.json` are exactly
what step 2 reads as input, so you generate projections in Fiji and feed them straight into
extraction. Tool 3 is a drop-in variant of step 2 for direct-Z (TopoJ) images — same CSV, frame
offset, sampling, and export, only the depth source differs (float pixel vs. indexed pixel + JSON).

---

## Project structure

Tool-exclusive classes live in per-tool subpackages — `projector` (Tool 1), `extractor`
(Tool 2), `topoj` (Tool 3) — while classes shared across tools stay at the
responsibility-package root (`io` / `core` / `export` / `model`).

```
ZTracker_Fiji/
├── pom.xml
└── src/main/
    ├── java/ztracker/
    │   ├── projector/ZProjectorPlugin.java       ← Tool 1 entry point (Z-Projection + Origin Map)
    │   ├── extractor/ZTrackerPlugin.java          ← Tool 2 entry point (indexed 3D Z-extractor)
    │   ├── topoj/TopoJTrackerPlugin.java          ← Tool 3 entry point (TopoJ / direct-Z; Tool 2 minus the JSON lookup)
    │   ├── ui/
    │   │   ├── projector/ZProjectorDialog.java    ← Tool 1 dialog (modeless AWT; addInputGroup pickers, duplicated so ZTrackerDialog is untouched)
    │   │   ├── extractor/ZTrackerDialog.java      ← Tool 2 6-step wizard, all non-modal (Steps 1,4,5,6 custom AWT; 2,3 NonBlockingGenericDialog)
    │   │   └── topoj/TopoJTrackerDialog.java      ← Tool 3 wizard; duplicate of ZTrackerDialog minus the Step-1 JSON picker
    │   ├── io/
    │   │   ├── TrackCsvLoader.java                ← shared: TrackMate CSV parser + column auto-detect
    │   │   ├── extractor/ZMappingLoader.java      ← Tool 2: JSON index→Z parsing (no external lib)
    │   │   ├── extractor/TiffStackLoader.java     ← Tool 2: indexed TIFF folder loader with frame→index map (int pixels)
    │   │   ├── topoj/TopoJStackLoader.java        ← Tool 3: 32-bit float TIFF loader (Z in µm, un-rounded); frameView() adapts to LoadedStack for FrameAligner reuse
    │   │   └── projector/ProjectionInputScanner.java ← Tool 1: discovers z-layer/timepoint folders; streams one timepoint at a time
    │   ├── core/
    │   │   ├── FrameAligner.java                  ← shared: CSV-to-TIFF offset suggestion + per-track alignment reporting
    │   │   ├── ZAggregator.java                   ← shared: median / mean aggregation
    │   │   ├── extractor/ZSampler.java            ← Tool 2: radius / 4-neighbor / single-pixel sampling
    │   │   ├── extractor/ZExtractor.java          ← Tool 2: sampling + mapping + aggregation; extractAll cross product; resolveComboOutputDir
    │   │   ├── topoj/TopoJSampler.java            ← Tool 3: same geometry over a float stack; sampled value is Z directly
    │   │   └── topoj/TopoJExtractor.java          ← Tool 3: identity Z (no index→Z lookup); reuses ZExtractor.MethodCombo/resolveComboOutputDir
    │   ├── project/
    │   │   └── ZProjector.java                    ← Tool 1: min/max projection + per-pixel z-origin index map (I/O-free)
    │   ├── export/
    │   │   ├── NpyExporter.java                   ← shared: writes [X,Y,Z,T] .npy (pure Java, no Python)
    │   │   ├── FijiPointsExporter.java            ← shared: Results Table CSV + ROI Manager .zip
    │   │   ├── TrackExportManager.java            ← shared: groups by track, dispatches to exporters
    │   │   └── projector/ProjectionExporter.java  ← Tool 1: 16/32-bit z-origin TIFFs + JSON mappings + 8-bit raw projection
    │   └── model/
    │       ├── TrackData.java                     ← shared: parallel arrays for all CSV detections
    │       └── ExtractionResult.java              ← shared: per-detection Z result + quality stats
    └── resources/plugins.config                   ← Fiji menu registration (all three tools)
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
   Fiji plugins folder, with any superseded `z-tracker-v4-*.jar` there removed (so a
   version bump never leaves an old JAR behind to load the plugin twice), then verified.
   Restart Fiji to load the updated plugin.

### Versioning

The JAR filename is controlled by `<patch.version>` in `pom.xml` line 18:
- Increment the patch number (e.g. `p1.3` → `p2.0`) for a major new capability.
- Increment the minor version (e.g. `p1.3` → `p1.4`) for iterative fixes within the same feature.

### Adapting to a different machine

The Fiji plugins path is machine-specific. Update `<outputDirectory>` in `pom.xml` line 126
to point to your local `Fiji.app/plugins/` folder.

---

## Installation in Fiji

The build step handles deployment automatically — no manual copy needed.
After `mvn install` completes successfully, restart Fiji (or `Help > Refresh Menus`).
All three tools appear under `Plugins > ZTracker` (in pipeline order):
- `Z-Projection + Origin Map` — step 1 (produce the projections)
- `3D Z-Coordinate Extractor` — step 2 (extract Z from indexed TIFF + JSON)
- `3D Z-Extractor (TopoJ / direct-Z)` — step 2, alternative (extract Z from 32-bit float TopoJ images)

---

## User guide for colleagues

A short, non-technical guide for end users lives in `docs/`:
`ZTracker_User_Guide.pdf` (hand to colleagues), plus `.txt` and `.html` copies.

**All three are generated from a single source — never edit them by hand.**
Edit `docs/ZTracker_User_Guide.md` (plain Markdown), then run:

```bash
node docs/build-guide.mjs
```

This overwrites the `.html`, `.txt`, and `.pdf` together so they can never drift
apart. The script has **no dependencies** — it uses a small built-in Markdown
parser and Microsoft Edge (headless) for the PDF. If Edge isn't found it still
writes the `.html`/`.txt` and just skips the PDF.

---

## Pipeline step 1 — Z-Projection + Origin Map

Produces the indexed TIFF projections + JSON Z-mapping that the extractor (step 2) consumes,
from a raw Z-stack. Native-Java port of `max_z_projection_plus_z_tracking_v2.py` /
`min_z_projection_plus_z_tracking_v2.py` (the two scripts differ only in min vs max, captured
here by one `ZProjector.Mode`).

### Input layout

A **dataset** is a folder whose sub-folders are named by their physical Z value, each
containing one `.tif` per timepoint (the filename is the timepoint id, shared across layers).

**Scope = Single dataset** — you select the dataset folder itself:

```
<input folder>/                 ← select this
├── -300/                       ← one sub-folder per Z-layer, named by its Z value (µm)
│   ├── frame_0001.tif          ← one TIFF per timepoint (filename = timepoint id)
│   ├── frame_0002.tif
│   └── ...
├── -299/
│   ├── frame_0001.tif
│   └── ...
└── ...
```

**Scope = Batch** — you select a parent folder that holds several such datasets; each
sub-folder that contains numeric Z-layer folders is processed as its own dataset (the tool's
own `min_z`/`max_z` output folders are skipped):

```
<input folder>/                 ← select this
├── datasetA/                   ← a full dataset, laid out exactly as above
│   ├── -300/   frame_0001.tif, ...
│   ├── -299/   ...
│   └── ...
├── datasetB/
│   └── ...
└── ...
```

Sub-folder names are parsed as numbers and sorted numerically (negatives and gaps are fine).
A timepoint absent from some layers is supported — only the present layers are stacked, and
the winner's **global** layer index (its position in the full sorted list) is what gets recorded.

### The dialog

A single modeless AWT dialog (same folder pickers as the extractor's Step 1 / Step 6),
in top-to-bottom order:

| Field | Choices |
|-------|---------|
| Scope | **Single dataset** (the input folder is the dataset above) / **Batch** (the input folder holds many such datasets) — asked first, so it's clear what the input folder should point at |
| Input folder | The folder matching the chosen scope. |
| Output folder | Where the output tree is written. |
| Projection | **Max-Z** (brightest pixel per position wins) / **Min-Z** (darkest wins) / **Both** |
| Z-origin bit depth | **16-bit** (default — smaller & faster; indices up to 65,535) / **32-bit** (always safe) / **Both** |

The 8-bit raw projection is **always written** (it's cheap and handy for a quick look);
there's no toggle for it. Choosing **Both** projections runs Max-Z *and* Min-Z, writing a full
`max_z/…` tree and a full `min_z/…` tree side by side — in single or batch mode — which never
collide since they live under different top folders.

The **Z-origin bit depth** choice controls which indexed TIFFs (and matching JSON mapping) are
written: pick a single depth to roughly halve the per-timepoint write work and keep the output
focused. **16-bit is the default** — its 65,535-index ceiling covers any realistic Z-layer
count. Only pick **32-bit** if you truly have more layers than that (astronomically unlikely) or
a downstream tool that requires 32-bit; **Both** reproduces the original always-write-both
behavior. If 16-bit is chosen *alone* and some index exceeds 65,535, that timepoint gets no
z-origin file at all (no 32-bit fallback) — the Log flags it clearly so you can re-run with 32-bit.

### What it computes

For each timepoint, pixel-by-pixel: the min/max intensity **projection**, and a **z-origin
index map** — for each pixel, which Z-layer won (`argmax`/`argmin`), stored as the integer
index into the sorted layer list.

> **Note — tie-breaking.** When two or more layers share the exact same extreme intensity at a
> pixel, the **first (lowest-index, i.e. shallowest) layer wins**. This is not a new choice made
> by the plugin — it's inherited from the original Python scripts, where `np.argmax`/`np.argmin`
> return the *first* occurrence of the max/min. The Java port reproduces it exactly (a strict
> `>` / `<` comparison, so a later equal value never displaces the first) and locks it in with a
> test. Exact ties are rare in real 16/32-bit intensity data, but can occur in flat or saturated
> regions — where the recorded depth becomes the shallowest tied layer.

### Output layout

The complete unit is one **per-dataset folder** named `<type>_<datasetName>/` (e.g.
`max_z_rec15/`) containing everything below. Where that folder sits depends on the scope.

**Scope = Single dataset** — the per-dataset folder goes straight into the output folder (there's
only one dataset, so no extra grouping level):

```
<output folder>/
└── max_z_<datasetName>/                      ← for Min-Z this is min_z_<datasetName>/
    ├── raw/
    │   ├── max_z_projection_<name>.tif       ← 8-bit preview (always written; extractor ignores)
    │   └── ...                                 (one per timepoint <name>)
    ├── z_origin/
    │   ├── z_origin_<name>.tif               ← 16-bit indexed  ┐ these two folders + a JSON
    │   └── ...                                                 │ are the EXTRACTOR's inputs
    ├── z_origin_32bit/                                         │
    │   ├── z_origin_32bit_<name>.tif         ← 32-bit indexed  ┘
    │   └── ...
    ├── z_layer_mapping.json                  ← {"0": -300.0, "1": -299.0, ...}  (index → Z µm)
    └── z_layer_mapping_32bit.json            ← identical copy, paired with the 32-bit set
```

**Scope = Batch** — the per-dataset folders are grouped one level deeper, under a projection-type
folder (`max_z/` or `min_z/`), so the many datasets stay tidy:

```
<output folder>/
├── max_z/
│   ├── max_z_datasetA/     raw/  z_origin/  z_origin_32bit/  z_layer_mapping*.json
│   └── max_z_datasetB/     raw/  z_origin/  z_origin_32bit/  z_layer_mapping*.json
└── min_z/                                    ← only present if you chose Min-Z or Both
    ├── min_z_datasetA/     ...
    └── min_z_datasetB/     ...
```

**Choosing Both** just adds the other projection type's folders alongside — `min_z_<dataset>/`
next to `max_z_<dataset>/` (Single), or a `min_z/` tree next to the `max_z/` tree (Batch). They
never collide, since the `max_z`/`min_z` prefix keeps them apart.

**Feeding the extractor (step 2):** point its *Z-origin TIFF folder* at a `z_origin/` (or
`z_origin_32bit/`) folder, and its *Z-mapping JSON* at the sibling `z_layer_mapping.json` (or
`z_layer_mapping_32bit.json`) — pick one bit depth and use its matching JSON.

- Only the selected **Z-origin bit depth**'s folder(s) and JSON mapping appear: 16-bit →
  `z_origin/` + `z_layer_mapping.json`; 32-bit → `z_origin_32bit/` + `z_layer_mapping_32bit.json`;
  Both → all of the above (as drawn). The `raw/` preview is always present.
- The **16-bit** z-origin TIFF is skipped for a timepoint (with a logged note) if any index
  exceeds the uint16 range (65535) — refusing the silent `uint16` wrap the script guards against.
  When 32-bit is also selected it's written as a fallback; when 16-bit is the *only* depth, that
  timepoint gets no z-origin file and the Log says so.
- This produce → consume seam is covered by `ProjectionExporterTest`, which writes these outputs
  and reads them back through the extractor's own `TiffStackLoader` + `ZMappingLoader`.

---

## Pipeline step 2 — 3D Z-Coordinate Extractor

The extractor runs as a 6-step dialog wizard:

| Step | What you configure |
|------|--------------------|
| 1 | Z-mapping JSON, TIFF projection folder, tracking CSV (TrackMate and other formats) |
| 2 | CSV format (header row, skip rows, default radius) |
| 3 | Column names (auto-detected, editable) |
| 4 | CSV-to-TIFF frame offset — a live-updating box shows a per-track verdict as you type; a suggested offset is pre-filled, and the full per-track table (each track's span + how its first/last frame maps) is written to the Log for verification |
| 5 | Sampling method (Radius / 4-Neighbor / Single Pixel / **All**) + aggregation (Median / Mean / **All**) — aggregation is disabled when Sampling is Single Pixel alone, since aggregating one sample is a no-op — plus pixel coordinate convention (Corner **[default]** / Center, no "All" option) |
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

#### Pixel coordinate convention

Step 5 lets you pick how integer X/Y coordinates relate to the pixel grid — there is no
single universally "correct" convention, so both are available:

- **Corner** (`ZSampler.PixelConvention.PIXEL_CORNER`) — **the default.** Integer `i` marks
  pixel `i`'s top-left corner, so pixel `i` spans `[i, i+1)` and its center is actually at
  `i+0.5`. This is the convention common in 2D tracking tools.
- **Center** (`ZSampler.PixelConvention.PIXEL_CENTER`) — the switchable alternate, and this
  plugin's original behavior. Integer `i` marks pixel `i`'s center directly, so pixel `i`
  spans `[i-0.5, i+0.5)`.

Only one is active at a time — there is no "All" option for this setting (unlike Sampling/
Aggregation Method), since a detection can't be sampled under two coordinate systems at once.

**This is not just a rounding-mode detail — it changes bounds checking too.** Near zero or
negative coordinates, the two conventions can disagree about whether a detection is even
in-bounds: `x=-0.4` rounds to `0` under Center (in-bounds) but floors to `-1` under Corner
(out-of-bounds). If you switch conventions on an existing dataset, some detections near a
frame edge may newly flip between a valid Z and `STATUS_OUT_OF_BOUNDS`.

#### The three sampling methods, under each convention

- **Single Pixel** samples the one pixel *containing* `(x, y)`: **rounds** to the nearest
  integer under Center (`Math.round`), or **floors** to the containing cell under Corner
  (`Math.floor`) — then checks whether that pixel is in bounds. A detection is only rejected
  for being out of the image after this step, never for having a non-integer coordinate.

  ```
  Center:  (x=2.3, y=1.4) rounds to pixel (2, 1)          [Math.round]
  Corner:  (x=2.7, y=1.6) floors to pixel (2, 1)          [Math.floor]

       x→ 0   1   2   3
     y↓ ┌───┬───┬───┬───┐
      0 │   │   │   │   │
        ├───┼───┼───┼───┤
      1 │   │   │ ● │   │   ← sampled pixel (2,1) either way (different input, same pixel)
        ├───┼───┼───┼───┤
      2 │   │   │   │   │
        └───┴───┴───┴───┘
  ```

- **4-Neighbor** samples the 4 pixels whose *centers* bracket the sub-pixel position. Under
  Center, integer coordinates already are pixel centers, so that's `floor(x)/ceil(x)` ×
  `floor(y)/ceil(y)`. Under Corner, pixel centers sit at `i+0.5`, so bracketing needs the
  standard bilinear half-pixel shift: `floor(x-0.5)`/`floor(x-0.5)+1` on each axis.

  ```
  Center:  (x=2.3, y=1.4) → floor(x)=2, ceil(x)=3 / floor(y)=1, ceil(y)=2

       x→ 0   1   2   3
     y↓ ┌───┬───┬───┬───┐
      0 │   │   │   │   │
        ├───┼───┼───┼───┤
      1 │   │   │ ○ │ ○ │   ← floor(y)=1
        ├───┼───┼───┼───┤
      2 │   │   │ ○ │ ○ │   ← ceil(y)=2
        └───┴───┴───┴───┘
               ↑   ↑
          floor(x)=2  ceil(x)=3

  Corner:  (x=2.7, y=1.6) → floor(x-0.5)=2, +1=3 / floor(y-0.5)=1, +1=2

       x→ 0   1   2   3   4
     y↓ ┌───┬───┬───┬───┬───┐
      0 │   │   │   │   │   │
        ├───┼───┼───┼───┼───┤
      1 │   │   │ ○ │ ○ │   │   ← floor(y-0.5)=1
        ├───┼───┼───┼───┼───┤
      2 │   │   │ ○ │ ○ │   │   ← floor(y-0.5)+1=2
        └───┴───┴───┴───┴───┘
               ↑   ↑
      floor(x-0.5)=2  +1=3
  ```

- **Radius** samples every pixel within a circular disk (`radius` from the CSV or the
  Step-2 default) — same disk-mask loop either way, just anchored on the pixel *containing*
  `(x, y)`: `round(x), round(y)` under Center, `floor(x), floor(y)` under Corner (not true
  continuous distance from the exact sub-pixel position — this is an existing approximation,
  unchanged by which convention is active).

  ```
  Center:  (x=2, y=2), radius=1.5 px → anchored on round(2)=2, round(2)=2

       x→ 0   1   2   3   4
     y↓ ┌───┬───┬───┬───┬───┐
      0 │   │   │ · │   │   │
        ├───┼───┼───┼───┼───┤
      1 │   │ · │ · │ · │   │
        ├───┼───┼───┼───┼───┤
      2 │ · │ · │ ● │ · │ · │   ← ● = anchor, · = sampled
        ├───┼───┼───┼───┼───┤
      3 │   │ · │ · │ · │   │
        ├───┼───┼───┼───┼───┤
      4 │   │   │ · │   │   │
        └───┴───┴───┴───┴───┘

  Corner:  (x=2.6, y=2.6), radius=1.5 px → anchored on floor(2.6)=2, floor(2.6)=2 (same
  disk shape as above — under Center, this same (2.6, 2.6) would instead anchor on
  round(2.6)=3, round(2.6)=3, shifting the whole disk one pixel down and right).
  ```

For all three methods, any sampled pixel that falls outside `[0, width) × [0, height)` is
silently **dropped, not clamped** — `width`/`height` are the loaded TIFF stack's actual pixel
dimensions (from `TiffStackLoader`), not an independent constant, so the boundary is exactly
the size of the TIFF frame the detection's `x`/`y` are being sampled against. Near an image
edge, Radius's disk and 4-Neighbor's corners can end up with fewer samples than usual; Single
Pixel either succeeds normally or returns zero samples (never partial).

### Export formats

| Format | File | Use in Fiji |
|--------|------|-------------|
| `.npy` | `tracks_2D/track_XXXXX.npy`, `tracks_3D/track_XXXXX.npy` | Python downstream pipeline |
| Results Table CSV | `fiji/results_table.csv` | `Analyze > Import > Results…` |
| ROI set (XY) | `fiji/track_rois.zip` | ROI Manager `More >> Open…` |
| ROI set (XZ) | `fiji/track_rois_XZ.zip` | ROI Manager `More >> Open…` — points at `(X px, Z µm)`, only detections with a valid Z |
| ROI set (YZ) | `fiji/track_rois_YZ.zip` | ROI Manager `More >> Open…` — points at `(Y px, Z µm)`, only detections with a valid Z |
| Export report | `export_report.txt` | Full, uncapped per-track breakdown of what was kept/dropped and why (see below) |

The XZ/YZ ROI sets plot Z **in µm, unconverted** against X/Y in pixels — they only overlay
sensibly on an XZ/YZ projection image whose own pixel size matches the Z spacing.

**Output directory layout.** With a single sampling/aggregation method chosen in Step 5,
everything exports flat into `outputDir`:

```
outputDir/
├── tracks_2D/track_00001.npy, track_00002.npy, ...           (if .npy enabled)
├── tracks_3D/track_00001.npy, track_00002.npy, ...           (if .npy enabled)
├── fiji/results_table.csv, track_rois.zip,                    (if Results Table / ROI set enabled)
│        track_rois_XZ.zip, track_rois_YZ.zip                 (if XZ / YZ ROI set enabled)
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
- **Reported frame numbers stay in the CSV's convention, even when a frame offset is applied.**
  The Step-4 CSV↔TIFF offset (e.g. `+1` for a 0-indexed CSV against 1-based TIFFs) is used
  **only** to locate which TIFF slice to sample (`track.frame[i] + offset`) — it is **never**
  written into any output. Every output reports each detection's **raw CSV frame**
  (`track.frame[i]`): the 2D/3D `.npy` `T` column, the Results Table CSV frame column, and the
  ROI names (`<trackID>_f<frame>`, including the XZ/YZ sets). So a detection at CSV frame `0`
  that sampled TIFF slice `1` is still reported as `T=0` everywhere. This is **consistent across
  every output file** and holds for both the indexed extractor (Tool 2) and the TopoJ / direct-Z
  extractor (Tool 3), since both share the same export path — keeping the results traceable
  straight back to the input CSV rather than to the internal TIFF slice numbering.
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

> **Note on tool numbers:** since **p9.1**, tools are numbered in **pipeline order** — Tool 1 = Z-Projection generator, Tool 2 = indexed 3D Z-extractor, Tool 3 = TopoJ / direct-Z extractor. Entries below from **before p9.1** used creation order (Tool 1 = extractor, Tool 2 = generator), so a "Tool N" in an older entry refers to the numbering as it stood then.

| Version | Description |
|---------|-------------|
| p9.4 | **Tool 3 (TopoJ / direct-Z) filename flexibility.** `TopoJStackLoader` now takes each frame index from the integer the filename **ends with** — any prefix, any zero-padding width, none of it hard-coded (`frame7.tif`, `topoj_0007.tif`, `height_map_00000100.tif` all resolve correctly). It strips the extension and anchors the match to the end of the base name (`(\d+)$`) instead of Tool 2's "last digit run anywhere", and — because anchoring can genuinely find no number — **rejects** any file that doesn't end with an integer with a clear message, rather than silently collapsing it to frame 0 (where several such files would clobber each other in `frameToIdx`). Tool 2's `TiffStackLoader` is deliberately left byte-for-byte unchanged (isolate-per-tool). New `TopoJStackLoaderTest` cases cover arbitrary prefix/padding and the reject-no-trailing-integer path |
| p9.3 | Reordered the Fiji menu registration (`plugins.config`) to **pipeline order** — Z-Projection + Origin Map (Tool 1), then 3D Z-Coordinate Extractor (Tool 2), then 3D Z-Extractor (TopoJ / direct-Z, Tool 3). Previously the generator (Tool 1, the first pipeline step) was registered last, so the actual Fiji menu contradicted every doc's stated pipeline order. Also documented `ProjectionInputScannerTest` in CLAUDE.md's Tool 1 test-suite description. No behavior change beyond the menu ordering; docs (CLAUDE.md, README, user guide) all verified consistent in pipeline order |
| p9.2 | Added `ExtractorEquivalenceTest` + `ExtractorEquivalenceDemo` (in `ztracker.core.topoj`): a cross-tool parity proof that the indexed extractor (Tool 2) and the direct-Z/TopoJ extractor (Tool 3) run the identical protocol — build an indexed stack + JSON map and the equivalent float stack (pixel = mapped Z, NaN if unmapped), then assert `z`/`zStd`/`numSamples` and the missing/OOB/invalid tallies match across every sampling × aggregation × convention combo, with the one allowed divergence (`numUnmapped`, and the `unmapped index`↔`no data` status label) asserted explicitly; the demo prints the same comparison for eyeballing. Also removed the always-zero `numUnmapped` local from `TopoJExtractor` (it now passes a zero array at construction — the shared `ExtractionResult` field still serves Tool 2) |
| p9.1 | Internal reorganization + doc renumber (no behavior change; all 129 tests pass unchanged). Tool-exclusive classes moved into per-tool subpackages — `projector` (Tool 1), `extractor` (Tool 2), `topoj` (Tool 3) — under `ztracker`, `ui`, `io`, `core`, and `export`; classes shared across tools stay at the responsibility-package root (`FrameAligner`, `ZAggregator`, `TrackCsvLoader`, `NpyExporter`, `FijiPointsExporter`, `TrackExportManager`, `TrackData`, `ExtractionResult`). `project` (all Tool 1) and `model` (all shared) keep no subpackage. `plugins.config` class paths and all imports updated; a few `topoj → extractor` references remain by design (Tool 3 reuses `TiffStackLoader.LoadedStack` and `ZExtractor.MethodCombo`/`resolveComboOutputDir`). Docs (CLAUDE.md, README) renumbered to pipeline order to match the new folder names |
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
| p5.6 | Moved `Step6ExportDemo.java`/`_output.txt` from `ztracker.core` to `ztracker.export` (it exercises `NpyExporter`/`FijiPointsExporter`/`TrackExportManager` directly, not dialog-step logic); made the X/Y cross-format comparison section unmissable — per-row `MATCH`/`MISMATCH!!` labels, a final `RESULT:` banner line, and an explicit note that the demo only prints the comparison and makes no assertions itself (the real pass/fail check is in `NpyExporterTest`/`FijiPointsExporterTest`/`TrackExportManagerTest`, run via `mvn test`) |
| p5.7 | `Step6ExportDemo` now prints the input track (Track_ID, Frame, X, Y) first thing in `main`, before any section runs, instead of only showing it implicitly inside section 4's comparison table — so the reader has a fixed reference point to check every later value against |
| p5.8 | Fixed a stale line-number reference: `<outputDirectory>` moved to `pom.xml` line 126 at some point, but the "Adapting to a different machine" section and the antrun build-verification failure message both still said line 120 — found during a full README-vs-code audit (everything else in README checked out accurate) |
| p5.9 | Fixed `Step6ExportDemo`'s section 3, which quietly declared its own local X/Y/Z/frame arrays (with different frame numbers than the input printed at the top) instead of reusing the shared input — every section now takes the same input/Z as parameters, and `main` prints Z as its own explicitly-labeled "NOT read from any input" table alongside the input track, instead of Z being buried inline in section 4's code |
| p5.10 | Added `ZMappingLoaderTest` (index→Z JSON regex parser had zero direct tests) covering negatives, decimals, scientific notation, multi-digit keys, and the empty/no-valid-entries `IllegalArgumentException` cases — found via a full test-suite-vs-code audit confirming every other test file accurately reflects current behavior, with no stale references to removed concepts |
| p6.0 | Added optional **XZ/YZ ROI set export** (`fiji/track_rois_XZ.zip`, `fiji/track_rois_YZ.zip`) — `FijiPointsExporter.writeXZRoiSet`/`writeYZRoiSet` plot `(X px, Z µm)`/`(Y px, Z µm)` per detection, Z left unconverted (no pixel conversion); only detections with a valid (non-NaN) Z are included, since a NaN Z has nothing to plot on that axis. Two new `ExportConfig` flags and Step-6 checkboxes, off by default; the per-track report gets a trailing `XZ ROI+YZ ROI: N/M pt` segment mirroring the existing Results Table/ROI one |
| p6.1 | Fixed a Swing/AWT rendering race in Fiji's ROI Manager list widget (`ArrayIndexOutOfBoundsException` from its renderer painting a row the list model had already dropped), surfaced by p6.0: running all three ROI formats in one export hammered `RoiManager.reset()`/`addRoi()`/save three times back-to-back on the same visible on-screen manager, faster than it could repaint. `writeXZRoiSet`/`writeYZRoiSet` now write their `.zip` directly via `ij.io.RoiEncoder`, bypassing the on-screen `RoiManager` entirely — architecturally more correct too, since X-vs-Z/Y-vs-Z points would look like nonsense image coordinates if added to the same interactive list as the real XY overlay. `writeRoiSet` (XY) is unchanged, still using `RoiManager` as before |
| p6.2 | Reworded the Step-6 ROI checkboxes to a consistent `Export <plane> ROI point set .zip (<coords>)` style across XY/XZ/YZ; dropped the XY-only "Fiji ROI Manager" mention since all three ROI zips are equally openable there |
| p9.0 | Added a **third tool**, `3D Z-Extractor (TopoJ / direct-Z)` (`TopoJTrackerPlugin`), for projection images whose pixel value **is** the Z depth in µm directly (32-bit float, e.g. Fiji's TopoJ output) — no index, no JSON mapping. A native-Java port of `3D_tracking_Jay_app_v2.py` that deliberately supersedes that script's crude "TIFF must start at 0" check and whole-track NaN/min-length/max-Z-std filtering with the current `FrameAligner` offset and per-point drop logic. New classes duplicate only the genuinely-different pieces so Tools 1/2 stay byte-for-byte untouched: `io/TopoJStackLoader` (loads 32-bit float TIFFs un-rounded; rejects 16/8-bit; exposes `LoadedFloatStack.frameView()`, a pixel-less `LoadedStack` adapter so `FrameAligner` is reused unchanged), `core/TopoJSampler` (same sampling geometry as `ZSampler`, values taken as Z), `core/TopoJExtractor` (identity Z, no lookup), and `ui/TopoJTrackerDialog` (Tool 1's wizard minus the JSON picker). Everything else — CSV handling, Steps 2–6, all export formats, per-track report — is shared. New `ExtractionResult.STATUS_NO_DATA` marks an all-NaN (no-data) sample, the direct-Z analogue of `STATUS_UNMAPPED_INDEX`. New tests: `TopoJSamplerTest`, `TopoJExtractorTest`, `TopoJStackLoaderTest` (float round-trip via real headless TIFF I/O + 32-bit-only enforcement) |
| p8.3 | Z-Projection **Z-origin bit depth** choice (16-bit / 32-bit / **Both**), defaulting to **16-bit** — a single-depth run writes only that depth's `z_origin*` TIFFs and matching JSON mapping, roughly halving per-timepoint write work (previously both were always written). `Config` carries `write16Bit`/`write32Bit`; `ProjectionExporter.writeMappings` takes the two flags so it doesn't leave an orphan mapping. When 16-bit is chosen alone and an index exceeds 65,535 there's no 32-bit fallback — the Log flags the timepoint clearly. New `ProjectionExporterTest` case covers selective mapping output |
| p8.2 | Z-Projection output layout: in **Single** scope the redundant projection-type grouping level is dropped — the per-dataset folder now sits directly in the output folder (`<out>/max_z_<dataset>/…` instead of `<out>/max_z/max_z_<dataset>/…`). **Batch** is unchanged (`<out>/max_z/max_z_<dataset>/…`), since it groups many datasets. Both projections still separate cleanly via the `max_z`/`min_z` prefix. Also clarified the input/output folder trees in the README |
| p8.1 | Z-Projection dialog refinements: **Scope is asked first** (before the input folder, so it's clear what to select) and the input-folder description was dropped as redundant; added a **Both** projection option that runs Max-Z *and* Min-Z into their separate `max_z/`/`min_z/` output trees (single or batch); and the 8-bit raw projection is **always written** now (removed the toggle). `Config` carries a `List<ZProjector.Mode>`; the plugin loops projection × dataset. `ZProjector`/`ProjectionInputScanner`/`ProjectionExporter` unchanged |
| p8.0 | Added a **second tool**, `Z-Projection + Origin Map` (`ZProjectorPlugin`), the upstream producer of the extractor's inputs — a native-Java port of `max_z`/`min_z_projection_plus_z_tracking_v2.py`. New packages/classes: `project/ZProjector` (I/O-free core min/max projection + per-pixel z-origin index map, ties→first layer), `io/ProjectionInputScanner` (discovers z-layer/timepoint folders, streams one timepoint's stack at a time), `export/ProjectionExporter` (16/32-bit z-origin TIFFs, hand-rolled JSON mappings ×2, 8-bit raw projection), and `ui/ZProjectorDialog` (modeless AWT with single/batch scope + Max-Z/Min-Z, reusing the extractor's DirectoryChooser/`addInputGroup` pickers — duplicated so `ZTrackerDialog` stays untouched). The extractor (Tool 1) is unchanged. New tests: `ZProjectorTest` (projection logic, tie-break, missing-layer global-index remap) and `ProjectionExporterTest`, whose seam test writes the outputs and reads them back through the extractor's own `TiffStackLoader` + `ZMappingLoader` to prove the two tools interoperate |
| p7.0 | Added a selectable **pixel coordinate convention** (`ZSampler.PixelConvention`) — whether integer X/Y mark a pixel's **corner** (`[i, i+1)`, center at `i+0.5`, a common 2D-tracking convention) or its **center** (`[i-0.5, i+0.5)`, this plugin's original behavior). **Corner is the new default** in Step 5 (Center remains fully available as the switchable alternate, no "All" option for either — it's always exactly one). The parameter is threaded explicitly through `ZSampler`/`ZExtractor`/`ExtractionResult` (no silent-default overload) so there's no risk of the UI defaulting to Corner while some internal path still assumed Center. Since this changes what the plugin does out of the box, near-zero/negative coordinates can now flip in/out of bounds differently than before (`x=-0.4` was in-bounds under the old Center default, is out-of-bounds under the new Corner default) — see the README's "Pixel coordinate convention" section and the new CLAUDE.md gotcha for details. `export_report.txt` gets a new "Pixel convention:" line |
