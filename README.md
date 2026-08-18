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
    │   │   ├── projector/ProjectionSource.java    ← Tool 1: layout-agnostic contract both input types feed (project one timepoint)
    │   │   ├── projector/ProjectionInputScanner.java ← Tool 1 input A: discovers z-layer/timepoint folders; streams one timepoint at a time
    │   │   ├── projector/FolderProjectionSource.java ← Tool 1 input A: ProjectionSource adapter over the scanner above (leaves it untouched)
    │   │   └── projector/ProjectionStackScanner.java ← Tool 1 input B: one TIFF stack per timepoint (slices = Z layers, Z from the slice labels); streams slices on demand
    │   ├── core/
    │   │   ├── FrameAligner.java                  ← shared: CSV-to-TIFF offset suggestion + per-track alignment reporting
    │   │   ├── ZAggregator.java                   ← shared: median / mean aggregation
    │   │   ├── extractor/ZSampler.java            ← Tool 2: radius / 4-neighbor / single-pixel sampling
    │   │   ├── extractor/ZExtractor.java          ← Tool 2: sampling + mapping + aggregation; extractAll cross product; resolveComboOutputDir
    │   │   ├── topoj/TopoJSampler.java            ← Tool 3: same geometry over a float stack; sampled value is Z directly
    │   │   └── topoj/TopoJExtractor.java          ← Tool 3: identity Z (no index→Z lookup); reuses ZExtractor.MethodCombo/resolveComboOutputDir
    │   ├── project/
    │   │   └── ZProjector.java                    ← Tool 1: min/max projection + per-pixel z-origin index map (I/O-free); Accumulator = same result, fed one slice at a time
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

Two input types are supported, chosen by the dialog's **Input type** dropdown. They differ only
in how the raw Z-stack is stored on disk and where each layer's physical Z comes from —
everything downstream (projection, tie-breaking, output tree, JSON mapping) is identical.

| Input type | One timepoint is | Z comes from |
|---|---|---|
| **Z-layer sub-folders** (default) | one TIFF **per Z-layer folder**, sharing a filename | the sub-folder names (`-300`, `-299`, …) |
| **TIFF stacks** | one **multi-slice TIFF file**, its slices being the Z layers | each slice's ImageJ label (`z = -400.000`) |

**Timepoint filenames must contain a number** (both input types). A timepoint's identity *is* the
number in its filename — that is what states its position in the sequence — so a digit-free name
like `cells.tif` means the file carries no timepoint index and the dataset has no timepoint
ordering at all. Since p10.9 that is treated as malformed input and **refused before any output is
written**, listing every offending filename at once so one run tells you everything to rename.
Where the digits sit doesn't matter (`frame_0001.tif`, `t7.tif`, `scan32.tif` are all fine) and
zero-padding widths may be mixed; only a name with no digits anywhere is rejected. Rename such
files to carry their index (`0001.tif`, `0002.tif`, …) and re-run.

**Two timepoints may not claim the same index** (both input types). The index is the **last** run
of digits in the filename, so `run1_0007.tif` and `run2_0007.tif` both read as timepoint 7, as do
`7.tif` and `0007.tif` — leading zeros don't distinguish them. Since p10.9 such a dataset is also
**refused before any output is written**, listing every collision grouped by the index collided on.
Mixed padding widths remain fine as long as the numbers differ (`z_7.tif`, `z_0008.tif`,
`z_00000009.tif` are three distinct timepoints). Note the index is the last digit run, not the
first: `0007_run1.tif` reads as timepoint **1**. This mirrors the extractor's own duplicate-frame
rejection (p10.4) — the same dataset was already refused there, just at load time after a full
projection run rather than in the first seconds.

#### If your layers are 32-bit float and contain NaN

Raw acquisition data is 8- or 16-bit and cannot hold `NaN`, so this only applies if the stack has
been processed into **32-bit float** first — most commonly by Fiji's
`Process > Math > NaN Background`, which sets sub-threshold pixels to `NaN`.

**A `NaN` never wins the projection.** The result is the brightest (or darkest) of the *real*
values at that pixel, whichever layers hold them, and the z-origin names that layer. Before p10.11
a `NaN` in the **lowest-Z** layer could never be beaten — every comparison against `NaN` is false —
so the pixel silently reported layer 0, which is a perfectly valid layer, so the extractor resolved
it to a real depth and reported success. Wrong Z, no warning.

**One limit remains, by design.** If *every* layer is `NaN` at a pixel there is no real value to
name, and the pixel still reports layer 0. The z-origin format stores layer indices and has no
spare "no data" value, so there is nothing truthful to write there. In practice this means a pixel
that is background in the entire Z column — the projected value comes out as `NaN` and the 8-bit
`raw/` preview shows it black, which is the tell to look for.

#### Input type A — Z-layer sub-folders

A **dataset** is a folder whose sub-folders are named by their physical Z value, each
containing one `.tif` per timepoint (the filename is the timepoint id, shared across layers).

##### Naming the Z-layer folders

**The folder name must be the number and nothing else.** The Z value is read straight from the
sub-folder's name — there is no prefix to strip and no unit to write. Surrounding whitespace is
ignored, so ` -300 ` is fine, but any other extra character stops the folder being recognised as
a Z-layer **at all**: it is not an error, it is simply not seen.

| Folder name | Result |
|---|---|
| `0`, `-300`, `-300.0`, `12.5`, `+300` | ✅ Z = 0, −300, −300, 12.5, 300 µm |
| `1e2`, `-3.5e-2`, `.5`, `5.` | ✅ scientific and partial decimals work (100, −0.035, 0.5, 5) |
| `z-300`, `Z_-300`, `zlayer300` | ❌ prefix — **not recognised as a Z-layer** |
| `-300um`, `-300 um`, `-300µm` | ❌ unit suffix — **not recognised** |
| `1,5` | ❌ **decimal comma is not accepted** — write `1.5` |

Layers are sorted **numerically**, not alphabetically, so negatives sort correctly (`-300` before
`-299`) and **gaps and uneven spacing are fine** — the layers need not be contiguous or evenly
spaced. Nothing depends on zero-padding: `-5` and `-005` are the same depth.

Having both is **not** rejected — this is a convention, not a rule the tool checks. Each folder
still gets its own layer index, both are read, both compete in the projection, and the JSON maps
the two indices to that one depth, so the Z reported for either is correct. Keep them apart anyway:
a duplicate depth usually means a name is wrong, and if one of them was meant to be a *different*
depth then that depth is simply missing from the dataset. (Which of two same-depth layers wins a
tie depends on the order the folders come back from the filesystem, so the recorded index — though
never the Z — can differ between machines.) Note the TIFF-stack input type **does** reject two
slices sharing a Z; that difference is internal to how the two layouts work, not a rule that
applies to one and not the other.

If *no* sub-folder parses as a number the dataset is rejected, and the message distinguishes the
three cases — the folder could not be read at all, it holds no sub-folders, or sub-folders were
found but none is named as a plain number (in which case up to five of the rejected names are
listed, with the remainder counted).

The trap to watch for is a **partial** match: if `-300/` and `-299/` parse but `z-298/` does not,
the dataset runs happily **without that layer** — but it no longer does so silently. A `NOTE` in the
Log lists every sub-folder that was not read as a Z layer, once per run per name however many
datasets it appears in. It is a note rather than an error on purpose: nothing can tell a `notes/` or `QC/`
folder apart from a `z-298/` that was meant to be a Z layer, so refusing the run would break
sensibly-organised datasets. The judgement is yours.

What a dropped layer actually costs: **the Z indices do not shift.** The JSON mapping is built from
the same layer list the projection used, so every index still resolves to the depth it names. What
is lost is that the dropped layer never competes — a pixel whose true extremum lay in it is
attributed to the nearest surviving layer instead, wrong in both the z-origin map and the raw
projection, while every pixel whose extremum was in a surviving layer is exactly right. The output
is partially correct, which is precisely why it is worth checking the note rather than ignoring it.

**One case the note cannot reach — batch scope, a folder with *zero* numeric layers.** Batch mode
only treats a sub-folder as a dataset if it holds at least one numerically-named layer, so a folder
containing only `z-300/` and `z-299/` is skipped before the scan ever runs, and no note is emitted.
Single scope does not make that check — the folder you pick *is* the dataset — so there the same
folder fails loudly and names the rejected sub-folders. The gap is therefore specifically: a batch
run in which other datasets succeed and one candidate folder is entirely misnamed, which is skipped
in silence. If a batch run processes fewer datasets than you expected, that is the thing to check.

:::note
Two quirks inherited from Java's number parser, listed only so they don't surprise you: a trailing
`d`/`f` **is** accepted as a type suffix (`-300F` parses as −300, unlike `-300um`), and the literal
names `NaN`, `Infinity`, and `-Infinity` parse too. None of these are worth using — stick to plain
numbers.
:::

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

#### Input type B — TIFF stacks (one file per timepoint)

Here a **dataset** is a folder holding the per-timepoint TIFF *stacks* directly — each file is
one timepoint, and the slices inside it are that timepoint's Z layers (the `t/z` file structure
Fiji produces when you save a Z-stack per frame):

```
<input folder>/                 ← select this (Scope = Single dataset)
├── 00001.tif                   ← timepoint 1: a multi-slice stack, one slice per Z layer
├── 00002.tif                   ← timepoint 2, same Z layers
└── ...
```

**Scope = Batch** works the same as for input type A — select a parent folder, and every
sub-folder holding TIFFs is processed as its own dataset (`min_z`/`max_z` output folders skipped):

```
<input folder>/                 ← select this (Scope = Batch)
├── datasetA/   00001.tif, 00002.tif, ...
├── datasetB/   00001.tif, ...
└── ...
```

Filenames are ordered by the integer they **end with**, so any zero-padding width works
(`00001.tif`, `7.tif`, `00010.tif` sort numerically, not lexicographically), and the filename
carries straight through to the outputs (`00001.tif` → `z_origin_00001.tif`).

##### Reading Z from the slice labels

**Where Z comes from.** There are no Z-named folders here, so each layer's physical Z (µm) is
read from the **ImageJ slice label** — the `z = -400.000` form Fiji writes for a Z stack (a
label that is just a bare number works too). Layers are then sorted **ascending by Z**, exactly
as the folder layout sorts its Z-named sub-folders, so the JSON mapping's `index → Z` ordering
and the tie-breaking rule are identical for both input types no matter what order the slices sit
in inside the file. A slice label with no readable Z is **rejected with a clear message** rather
than guessed at — a silently mislabelled depth would corrupt every downstream Z coordinate.

This is the type B counterpart of [Naming the Z-layer folders](#naming-the-z-layer-folders) for
type A: the same decision — where a layer's depth comes from — made in a different place, and
worth settling *before* you acquire, not after.

**Two forms are accepted:** ImageJ's `z = …` assignment appearing anywhere in the label (labels
are often multi-line), or a label that is **nothing but** a number.

| Slice label | Result |
|---|---|
| `z = -400.000`, `Z = -400.000`, `z=-400.000`, `z   =   -400.000` | ✅ Z = −400 µm |
| `z: -400.000`, `Z:12.5` | ✅ `:` works as well as `=` |
| `00001.tif` ⏎ `z = -400.000` | ✅ multi-line — the form Fiji actually writes |
| `slice 3, z = -400.000, ch 1` | ✅ found anywhere in the label (constructed example — see note) |
| `-400.000`, `0`, `400`, `12.5`, `1e2`, `-3.5e-2` | ✅ bare number as the **whole** label |
| `+300`, `.5`, `5.`, `1,5`, `NaN`, `Infinity`, `400 um` | ❌ as a whole label — **not** accepted |
| `depth = 400`, `zz = 400`, `az = 400`, `z 400`, `z - 400` | ❌ no usable `z=` / `z:` |
| `00001-0003.tif`, `z_stack_0003.tif`, `MAX_z_0003` | ❌ no number is mined out of arbitrary text |

The `z` is **case-insensitive**, must be followed by `=` or `:` (spaces around it optional, any
number of them), and must stand on its own — `zz =`, `az =` and `_z =` are not matched. Negatives,
decimals and scientific notation all work; a `+` sign does not.

:::note
**The assignment form stops reading at the first character that cannot continue the number and
ignores the rest, which cuts both ways.** That tolerance is the point of the form — it is what
lets a depth be found inside a label full of other metadata — but it has two edges worth knowing.

Helpfully, `z = -400.000 um` and `z = -400.000µm` both give −400: a trailing unit does no harm.
Unhelpfully, **`z = 1,5` gives 1.0, not 1.5** — a decimal comma silently truncates, so write
`z = 1.5`. And a label of the form `z:3/401` would give **3.0**, the slice *number* read as a
depth. Neither is detected.

Two caveats on how seriously to take these, since neither has a known producer. Fiji itself does
**not** write `z:3/401` into a slice label — that shape is ImageJ's *window subtitle*
(`StackWindow.createSubtitle()`), drawn above the image and never stored in the stack or saved to
a TIFF — so it could only reach you from a third-party writer. And the multi-metadata example in
the table above (`slice 3, z = -400.000, ch 1`) is a **constructed** example showing what the
parser accepts, not a format observed in the wild.

The bare-number form is stricter and rejects all of these outright; only the `z = …` form
tolerates trailing text. If your depths come out as small integers or suspiciously round, the
labels are the first thing to check.
:::

**An unreadable label fails loudly — the opposite of type A's misnamed folder.** A `z-298/`
sub-folder in type A is simply not seen as a Z layer and the run continues with a `NOTE`; a slice
whose label carries no readable Z **stops the work**, naming the slice number, the file, and the
offending label text. Where it stops depends on which stack it is in: in the dataset's **first**
stack the whole dataset is refused before anything is written, and in any **later** timepoint just
that timepoint is skipped with the reason logged, counted in the run summary's skipped-timepoint
`WARNING` and never counted as a success. This is deliberate — a wrong depth is far worse than a
refusal, so nothing is guessed.

The dataset's Z layers are taken from its **first** stack; every later timepoint is checked
against them. A timepoint covering only *some* of those layers is fine (the same way a timepoint
can be absent from some Z folders in input type A) — its slices keep their global Z indices. A
timepoint naming a depth the dataset has no layer for is skipped with a logged reason.

**A stack needs at least two slices.** A single-slice TIFF isn't a Z stack, so it is rejected up
front — and since that almost always means the data is really the *other* layout, the message
points you at the **Z-layer sub-folders** input type instead. This is checked on the stack that
defines the dataset's Z layers; a later timepoint holding fewer layers is the supported subset
case described above, not an error.

**No two slices may share a Z value.** If two of that stack's labels read the same depth (both
`z = 0.000`, say), the dataset is rejected and the message names the repeated value. Each slice
must carry its own distinct depth — otherwise two slices would compete for one layer, and one
would silently overwrite the other in the projection.

**Calibration is ignored by design — Z comes only from the labels.** If your stack has a properly
set voxel depth (Image ▸ Properties, or `pixelDepth`/unit in the TIFF header), the tool still does
not read it. That is deliberate, not an oversight: the stacks this pipeline works with routinely
carry a placeholder depth of `1.0` with no unit while keeping their true depths in the slice
labels, so believing the calibration would look like it worked and hand you Z coordinates on the
wrong scale — the worst kind of failure, because nothing about the output would look wrong. The
labels are the single source of Z, which is also why an unreadable label stops the run outright
instead of quietly falling back to the calibration.

> **Memory.** One timepoint here is one large file — a 401-slice 1051×1674 stack is ~700 MB, and
> holding every slice as floats at once would need ~2.8 GB. So slices are read **one at a time**
> from a virtual (on-demand) stack and folded straight into `ZProjector.Accumulator`, keeping
> peak memory at `width × height × (12 + bytes-per-pixel)` — three full-frame working buffers plus
> the one slice being read — whatever the depth of the stack. That real 401-slice timepoint
> projects in ~2 s inside a **512 MB** heap — 512 MB being the budget the design is built to
> fit, not the heap Fiji actually runs with, since peak memory here does not grow with stack
> depth at all. Files that can't be opened virtually (e.g. compressed TIFFs) fall back to a
> normal in-memory open.

### The dialog

A single modeless AWT dialog (same folder pickers as the extractor's Step 1 / Step 6),
in top-to-bottom order:

| Field | Choices |
|-------|---------|
| Input type | **Z-layer sub-folders** (default — one folder per Z depth, one TIFF per timepoint inside) / **TIFF stacks** (one file per timepoint; the file's slices are the Z layers) — asked first, since it decides what a dataset looks like on disk |
| Scope | **Single dataset** (the input folder is one dataset) / **Batch** (the input folder holds many datasets) — a live description line below spells out the exact structure expected for the chosen input type × scope |
| Input folder | The folder matching the chosen input type and scope. |
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
  depended on Z, so it's unaffected. Since p10.12 a **`NaN` pixel in a 32-bit indexed
  TIFF** counts as unmapped too: previously it was rounded to index `0`, which is the
  lowest-Z layer's own legitimate index, so such a pixel quietly produced a real-looking
  Z instead of failing. Only 32-bit input can be affected — a 16-bit TIFF has no way to
  represent `NaN`. (This is the extractor-side twin of p10.11's projection fix.)
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
- **TIFF stack**: 16-bit unsigned integer or 32-bit indexed, one file per timepoint. The frame index is the **last run of digits anywhere in the filename** (`z_origin_0007.tif` and `z_origin_32bit_0007.tif` both read as frame 7 — the "32" in "32bit" is skipped). Any zero-padding width works and widths may be mixed within one folder, since each is parsed to an integer and the folder is sorted numerically. A filename with **no digits at all** is rejected up front, listing every offending file (p10.2). Every frame must also match the **first frame's pixel dimensions** — larger as well as smaller (p10.1) — and share its **bit depth**. Note Tool 3 (TopoJ / direct-Z) uses a stricter rule: the digits must be at the **end** of the base name, so a name this loader accepts may be rejected there
- **CSV**: TrackMate or other tracker formats (alias-based column auto-detection); required columns: X, Y, Frame, Track_ID. An optional **Radius** (or Size) column is used for the Radius sampling method, and is read as **pixels** — the same units as X and Y. There is no unit handling anywhere in the pipeline, so a radius exported in µm or nm by another tracker is taken at face value and nothing will catch it; convert it to pixels first, or drop the column and set the Step-2 default radius instead. A cell that is blank, unparseable, `NaN`, infinite, or not positive falls back to that default, and the substitution is logged with the offending line numbers. A radius **larger than the frame** takes a different path: it is accepted as given, then capped at the frame diagonal when sampling, and the detection succeeds having aggregated over the **entire image** — **nothing reports this, at either step**, so a Z that looks perfectly valid may have come from every pixel in the frame rather than a neighbourhood. This is the failure mode a unit mix-up produces, which is why the pixel requirement above is worth checking against your tracker's output

**Why the three tools accept different input bit depths.** It follows from what a pixel *means*
in each. Tool 1 reads **raw intensity** and only ever compares pixels with each other, so any
grayscale depth works — 8-, 16-, or 32-bit — and the depth affects precision, not correctness,
provided every Z layer of a timepoint shares one depth (mixing them within a timepoint is
rejected since p10.6, since the comparison would be decided by the deeper layer's larger
ceiling). (A colour/RGB TIFF is **rejected** since p10.5: there is no single intensity to
compare, and projecting one would run on packed ARGB values — convert to grayscale first.)
Tool 2's pixel value is an integer **index** into the JSON mapping, so it needs 16- or 32-bit to
carry the index range — 8-bit would cap the dataset at 256 Z-layers, and it plus 24-bit RGB are
rejected outright. Tool 3's pixel value **is** the Z coordinate in µm, so it must be 32-bit
**float**: a 16-bit integer image could only hold whole micrometres, silently rounding every
depth it stores.

---

## Memory and large datasets

Z-projection (step 1) is the only part of the pipeline with a real appetite for memory. How much
it needs depends on **which input type you picked** in step 1's first dropdown and **whether your
TIFFs are compressed** — not on how long the recording is, since timepoints are processed one at a
time either way. (If you don't know whether yours are compressed, the last paragraph below shows
how to check in about ten seconds. Acquisition software normally writes uncompressed files.)

Worked through on one real acquisition — **1051 × 1674 pixels, 401 Z layers, 8-bit, 673 MB per
timepoint**:

| Input | For that example | The general rule |
|-------|-----------------|------------------|
| **TIFF stacks, uncompressed** (the normal case) | **~25 MB** | `width × height × 13` bytes for 8-bit input (`× 14` for 16-bit, `× 16` for 32-bit). Layers are read one at a time, so the depth of the stack costs nothing |
| **TIFF stacks, compressed** | **~700 MB** — about the file's own size over again | Roughly the size of one timepoint's file: compressed pixels can't be read a piece at a time, so the whole thing is unpacked into memory first |
| **Z-layer sub-folders** | **over 2.6 GB** | `width × height × 4 × number of Z layers` bytes — every layer is held at once |

**The middle column is that one acquisition's numbers, not the plugin's requirements.** Work out
your own from the right-hand column: frame size, bit depth and (for sub-folders) the number of Z
layers all change the answer. The number of timepoints does not.

**The sub-folder layout is the expensive one, and there is no way around it.** Each Z layer is a
separate single-image file, so there is no multi-slice file to read a piece at a time — the layers
have to be assembled before they can be compared. That is a consequence of how the layout stores
the data, not a fault in it. If you have a choice of input type for a deep acquisition, that is
the figure to plan around.

**Where the limit actually sits: `Edit > Options > Memory & Threads` in Fiji.** That setting is the
ceiling, not how much RAM your machine has — a computer with 64 GB installed will still run out if
Fiji was only given 2 GB. If step 1 stalls or fails on a large dataset, raise it there and restart
Fiji.

**How to tell whether your TIFFs are compressed.** Open one, read its size off `Image > Show Info`,
and compare that against `width × height × number of slices` bytes (double it for 16-bit,
quadruple for 32-bit). A file matching the product stores every pixel raw and takes the cheap
path; one meaningfully smaller than it is compressed. In practice acquisition software writes
uncompressed files, so this is rare — and compressed input **gives exactly the same results**
either way. The only difference is memory and time.

---

## Output `.npy` format

Files are NumPy float64, C-order, no header row:

- `tracks_2D/track_XXXXX.npy` → columns `[X, Y, T]`
- `tracks_3D/track_XXXXX.npy` → columns `[X, Y, Z, T]`

Fully compatible with the existing Python smoothing and visualization scripts.

---

## Patch history

The full patch-by-patch record now lives in **[docs/CHANGELOG.md](docs/CHANGELOG.md)** — every
version from p1.0 onwards, newest first, with what changed and why. It was moved out of this file
in p10.18, where it had grown to more than half of README by word count.

Those rows are a historical record and are never edited, so some knowingly state figures or file
locations that later patches corrected; the changelog explains that convention at the top.
