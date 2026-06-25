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

## Build (IntelliJ + Maven)

### Prerequisites
- JDK 8 or later
- Maven 3.6+ (bundled with IntelliJ)
- Internet access to resolve `net.imagej:ij` from the SciJava repository

### Steps

1. **Open the project** in IntelliJ: `File > Open` → select `ZTracker_Fiji/` folder.
   IntelliJ will detect `pom.xml` automatically.

2. **Build the jar**:
   - Open the Maven tool window (`View > Tool Windows > Maven`)
   - Run `Lifecycle > package`
   - Or in the terminal: `mvn package`

3. **Output**: `target/ZTracker_Fiji.jar`

---

## Installation in Fiji

1. Copy `target/ZTracker_Fiji.jar` into your `Fiji.app/plugins/` folder.
2. Restart Fiji (or `Help > Refresh Menus`).
3. The plugin appears under `Plugins > ZTracker > 3D Z-Coordinate Extractor`.

> **Tip**: To automate the copy step, uncomment the `maven-resources-plugin`
> block in `pom.xml` and set the correct path to your `Fiji.app/plugins/`.

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
