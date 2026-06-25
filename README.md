# ZTracker Fiji Plugin

A Fiji/ImageJ plugin that extracts Z-coordinates from 16-bit indexed TIFF
projection stacks and exports 3D cell tracks. Ports the functionality of
`3D_tracking_Jay_app_unified_v1.py` into a native Fiji plugin.

---

## Project structure

```
ZTracker_Fiji/
├── pom.xml
└── src/main/
    ├── java/ztracker/
    │   ├── ZTrackerPlugin.java          ← plugin entry point
    │   ├── ui/ZTrackerDialog.java       ← GenericDialog wizard (6 steps)
    │   ├── io/
    │   │   ├── ZMappingLoader.java      ← JSON index→Z parsing (no external lib)
    │   │   ├── TiffStackLoader.java     ← TIFF folder loader with frame→index map
    │   │   └── TrackCsvLoader.java      ← TrackMate CSV parser + column auto-detect
    │   ├── core/
    │   │   ├── FrameAligner.java        ← CSV-to-TIFF offset detection + preview
    │   │   ├── ZSampler.java            ← radius / 4-neighbor / single-pixel sampling
    │   │   ├── ZAggregator.java         ← median / mean / mode aggregation
    │   │   └── ZExtractor.java          ← orchestrates sampling + mapping + aggregation
    │   ├── export/
    │   │   ├── NpyExporter.java         ← writes [X,Y,Z,T] .npy (pure Java, no Python)
    │   │   ├── FijiPointsExporter.java  ← Results Table CSV + ROI Manager .zip
    │   │   └── TrackExportManager.java  ← groups by track, filters, dispatches to exporters
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
| 1 | Z-mapping JSON, TIFF folder, TrackMate CSV |
| 2 | CSV format (header row, skip rows, default radius) |
| 3 | Column names (auto-detected, editable) |
| 4 | CSV-to-TIFF frame offset (with alignment preview) |
| 5 | Sampling method (Radius / 4-Neighbor / Single Pixel) + aggregation (Median / Mean / Mode) |
| 6 | Output directory, track length filter, Z-std filter, export formats |

### Export formats

| Format | File | Use in Fiji |
|--------|------|-------------|
| `.npy` | `tracks_2D/track_XXXXX.npy`, `tracks_3D/track_XXXXX.npy` | Python downstream pipeline |
| Results Table CSV | `fiji/results_table.csv` | `Analyze > Import > Results…` |
| ROI set | `fiji/track_rois.zip` | ROI Manager `More >> Open…` |

---

## Input file requirements

Same as the Python pipeline:

- **JSON mapping**: `{"0": -600.0, "1": -599.0, ...}` (string keys, float values)
- **TIFF stack**: 16-bit unsigned integer, one file per timepoint, numeric filenames
- **CSV**: TrackMate format; required columns: X, Y, Frame, Track_ID

---

## Output `.npy` format

Files are NumPy float64, C-order, no header row:

- `tracks_2D/track_XXXXX.npy` → columns `[X, Y, T]`
- `tracks_3D/track_XXXXX.npy` → columns `[X, Y, Z, T]`

Fully compatible with the existing Python smoothing and visualization scripts.
