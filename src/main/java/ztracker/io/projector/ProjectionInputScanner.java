package ztracker.io.projector;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Discovers the z-layer / timepoint folder structure the projection tool consumes,
 * and streams <em>one timepoint's</em> z-stack at a time (RAM-friendly, mirroring the
 * Python script's per-filename processing rather than loading the whole 4-D dataset).
 *
 * <p>Expected layout of a single <em>dataset</em> folder:
 * <pre>
 * datasetDir/
 *   -300/   frame_0001.tif, frame_0002.tif, ...
 *   -299/   frame_0001.tif, ...
 *   ...
 * </pre>
 * Sub-folder names are the physical Z values (parsed as doubles and sorted numerically,
 * so negatives and gaps are fine). Each z-layer folder holds one {@code .tif} per
 * timepoint; the filename is the timepoint id, shared across layers. A timepoint that
 * is absent from some layers is supported — only the present layers are stacked, and
 * their <em>global</em> z-index (position in the full sorted list) is what gets recorded.
 *
 * <p>This lives in {@code ztracker.io} alongside the other input loaders and reuses the
 * same ImageJ read path ({@link IJ#openImage}) as {@link TiffStackLoader}. It reads pixel
 * intensities via {@link ImageProcessor#getf(int, int)}, which returns the correct value
 * for 8-, 16-, and 32-bit sources alike (unlike {@code getPixel}, which truncates floats).
 */
public final class ProjectionInputScanner {

    private ProjectionInputScanner() {}

    // ── Result containers ─────────────────────────────────────────────────────

    /** The static structure of one dataset: its sorted z-layers and timepoint filenames. */
    public static final class DatasetScan {
        public final File datasetDir;
        /** Z-layer sub-folder names, sorted by numeric value (parallel to {@link #zValues}). */
        public final List<String> zLayerNames;
        /** Physical Z value of each layer (µm), index → Z; parallel to {@link #zLayerNames}. */
        public final double[] zValues;
        /** Union of timepoint {@code .tif} filenames across all layers, lexicographically sorted. */
        public final List<String> timepointFilenames;

        DatasetScan(File datasetDir, List<String> zLayerNames, double[] zValues,
                    List<String> timepointFilenames) {
            this.datasetDir         = datasetDir;
            this.zLayerNames        = zLayerNames;
            this.zValues            = zValues;
            this.timepointFilenames = timepointFilenames;
        }
    }

    /** One timepoint's loaded z-stack: the present slices plus their global z-indices. */
    public static final class TimepointStack {
        /** One intensity array per present z-layer, each {@code [height][width]}. */
        public final List<float[][]> slices;
        /** Global z-layer index of each slice (parallel to {@link #slices}). */
        public final int[] globalZIndex;
        public final int width;
        public final int height;
        /** Bit depth of the source images (8/16/32) — used to normalize the raw projection. */
        public final int sourceBitDepth;

        TimepointStack(List<float[][]> slices, int[] globalZIndex,
                       int width, int height, int sourceBitDepth) {
            this.slices         = slices;
            this.globalZIndex   = globalZIndex;
            this.width          = width;
            this.height         = height;
            this.sourceBitDepth = sourceBitDepth;
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Scans one dataset folder for its z-layers and timepoints.
     *
     * @param datasetDir folder containing numerically-named z-layer sub-folders
     * @return the dataset's static structure
     * @throws IOException if no numeric z-layer sub-folders or no TIFFs are found
     */
    public static DatasetScan scanDataset(File datasetDir) throws IOException {
        // One listing, partitioned: the numeric sub-folders are the z-layers, and the rest are
        // reported (see reportIgnoredSubFolders) rather than dropped without a word.
        File[] allSubDirs = datasetDir.listFiles(File::isDirectory);
        if (allSubDirs == null) {
            throw new IOException(noZLayersMessage(datasetDir, true));
        }
        List<File> layers = new ArrayList<>(allSubDirs.length);
        List<String> ignored = new ArrayList<>();
        for (File sub : allSubDirs) {
            if (parseZ(sub.getName()) != null) layers.add(sub);
            else ignored.add(sub.getName());
        }
        if (layers.isEmpty()) {
            throw new IOException(noZLayersMessage(datasetDir, false));
        }
        for (String line : reportIgnoredSubFolders(datasetDir, ignored)) {
            IJ.log(line);
        }

        // Sort z-layers by numeric value (float(z) in the Python script).
        layers.sort(Comparator.comparingDouble(f -> parseZ(f.getName())));

        List<String> zLayerNames = new ArrayList<>(layers.size());
        double[] zValues = new double[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            zLayerNames.add(layers.get(i).getName());
            zValues[i] = parseZ(layers.get(i).getName());
        }

        // Union of .tif filenames across all layers (a TreeSet dedups and sorts lexicographically,
        // matching the script's set() + sorted()).
        // The dedup is load-bearing downstream: ZProjectorPlugin.duplicateTimepointIndexError
        // refuses a dataset whose timepoint labels resolve to the same index, so one timepoint
        // present in twenty Z-layer folders must arrive there as ONE label. Replace this with a
        // plain List and every well-formed folder dataset is rejected as an index collision.
        TreeSet<String> filenames = new TreeSet<>();
        for (File layer : layers) {
            File[] tifs = layer.listFiles(
                    f -> f.isFile() && f.getName().toLowerCase().matches(".*\\.tiff?"));
            if (tifs != null) {
                for (File t : tifs) filenames.add(t.getName());
            }
        }
        if (filenames.isEmpty()) {
            throw new IOException(
                    "No .tif images found in any z-layer of dataset: " + datasetDir.getAbsolutePath());
        }

        return new DatasetScan(datasetDir, zLayerNames, zValues,
                new ArrayList<>(filenames));
    }

    /**
     * Loads the z-stack for a single timepoint, reading only the layers that actually
     * contain {@code filename}. The returned slices are parallel to {@code globalZIndex},
     * where each entry is the layer's index into {@link DatasetScan#zLayerNames}.
     *
     * @throws IOException if a present image cannot be opened, is 24-bit RGB, or slice
     *                     dimensions disagree
     */
    public static TimepointStack loadTimepoint(DatasetScan scan, String filename) throws IOException {
        List<float[][]> slices = new ArrayList<>();
        List<Integer> globalIdx = new ArrayList<>();
        int width = -1, height = -1, bitDepth = -1;

        for (int i = 0; i < scan.zLayerNames.size(); i++) {
            File img = new File(new File(scan.datasetDir, scan.zLayerNames.get(i)), filename);
            if (!img.isFile()) continue; // this timepoint is absent from this layer — skip it

            ImagePlus imp = IJ.openImage(img.getAbsolutePath());
            if (imp == null) {
                throw new IOException("Could not open image: " + img.getAbsolutePath());
            }
            try {
                // Colour images have no single intensity to project: ColorProcessor.getf
                // hands back the packed ARGB int, so projecting one would compare packed
                // colour values and yield a confident but meaningless z-origin map.
                if (imp.getBitDepth() == 24) {
                    throw new IOException(
                            "Colour (RGB) image within timepoint '" + filename + "': "
                            + scan.zLayerNames.get(i) + " is 24-bit RGB."
                            + "\nZ-projection needs grayscale intensity — convert with"
                            + " Image > Type > 8-bit or 16-bit first.");
                }
                if (width < 0) {
                    width    = imp.getWidth();
                    height   = imp.getHeight();
                    bitDepth = imp.getBitDepth();
                } else if (imp.getWidth() != width || imp.getHeight() != height) {
                    throw new IOException(
                            "Inconsistent image dimensions within timepoint '" + filename + "': "
                            + scan.zLayerNames.get(i) + " is " + imp.getWidth() + "x" + imp.getHeight()
                            + ", expected " + width + "x" + height + ".");
                } else if (imp.getBitDepth() != bitDepth) {
                    // The projection compares raw intensities across these layers, and the
                    // depths have different ceilings (255 vs 65535), so mixing them lets the
                    // deeper layer win Max-Z on magnitude alone — a plausible, wrong z-origin.
                    throw new IOException(
                            "Inconsistent bit depths within timepoint '" + filename + "': "
                            + scan.zLayerNames.get(i) + " is " + imp.getBitDepth() + "-bit, expected "
                            + bitDepth + "-bit."
                            + "\nAll Z layers of one timepoint must share a bit depth — the"
                            + " projection compares their raw intensities.");
                }
                ImageProcessor ip = imp.getProcessor();
                float[][] slice = new float[height][width];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        slice[y][x] = ip.getf(x, y);
                    }
                }
                slices.add(slice);
                globalIdx.add(i);
            } finally {
                imp.close();
            }
        }

        if (slices.isEmpty()) {
            throw new IOException("No layers contained timepoint '" + filename + "'.");
        }

        int[] globalZIndex = new int[globalIdx.size()];
        for (int i = 0; i < globalZIndex.length; i++) globalZIndex[i] = globalIdx.get(i);

        return new TimepointStack(slices, globalZIndex, width, height, bitDepth);
    }

    /**
     * Quick check (no TIFF reads) for whether {@code dir} looks like a dataset — i.e. it
     * directly contains at least one numerically-named z-layer sub-folder. Used by the
     * plugin's batch mode to pick out datasets and skip the tool's own output roots.
     *
     * <p>Deliberately does <b>not</b> report its ignored sub-folders: batch mode calls this on
     * every sub-folder of the input root, most of which are not datasets at all, so reporting
     * here would describe folders the run never touches. Only {@link #scanDataset} reports.
     */
    public static boolean isDataset(File dir) {
        File[] zs = dir.listFiles(f -> f.isDirectory() && parseZ(f.getName()) != null);
        return zs != null && zs.length > 0;
    }

    // ── Ignored sub-folder reporting ───────────────────────────────────────────

    /** Log prefix, matching the other {@code ztracker.io} loaders' own-class convention. */
    private static final String LOG = "[ProjectionInputScanner] ";

    /**
     * Sub-folder names already reported during this run, so a {@code notes/} folder present in
     * forty datasets is described <em>once</em> rather than forty times — and once, not twice,
     * when both Max-Z and Min-Z are requested over the same datasets.
     *
     * <p>Static because that is the only place the state can sit without threading a collector up
     * through {@link ProjectionSource}: the rejected names exist nowhere but inside
     * {@link #scanDataset}, and the whole point of that boundary is that nothing type-specific
     * crosses it. Nothing flows upward here — the report goes sideways to the Log, exactly as this
     * class's failures go sideways to {@link IOException}.
     *
     * <p>The cost of that choice is that the run boundary has to be signalled from outside: see
     * {@link #resetIgnoredSubFolderReport()}.
     */
    private static final Set<String> reportedIgnoredSubFolders = new LinkedHashSet<>();

    /**
     * Clears the once-per-run dedup state — called once at the start of
     * {@code ZProjectorPlugin.run()}.
     *
     * <p>Without it the dedup would be once per <em>Fiji session</em>, not once per run: a second
     * run in the same session would stay silent about the very folders the first one described,
     * which is worse than not deduping at all. This is a bare static signal, carrying no data in
     * either direction.
     */
    public static void resetIgnoredSubFolderReport() {
        reportedIgnoredSubFolders.clear();
    }

    /**
     * The Log lines describing sub-folders that were not read as Z layers — empty when every
     * ignored name has already been reported this run.
     *
     * <p><b>A note, not a warning, and deliberately not an error.</b> Nothing here can tell a
     * {@code notes/} folder apart from a {@code z-298/} that was meant to be a Z layer: every
     * heuristic (does it hold TIFFs? does it look number-ish?) fails in a realistic direction, and
     * refusing a sensibly-organised dataset over a {@code QC/} folder would be a worse outcome than
     * the problem it guards. So this states what was ignored and what a misnamed layer would cost,
     * and leaves the judgement to the user without claiming anything is wrong.
     *
     * <p>The consequence it spells out is the accurate one. A dropped layer does <b>not</b> shift
     * the Z indices: {@link DatasetScan#zValues} and {@link DatasetScan#zLayerNames} are both built
     * from the same filtered layer list, so an index and the depth it maps to stay mutually
     * consistent. What is lost is that the dropped layer never competes in the projection, so a
     * pixel whose true extremum lay in it is attributed to the nearest surviving layer instead —
     * wrong in both the z-origin map and the raw projection — while every pixel whose extremum was
     * in a surviving layer is exactly right. Partially-correct output is precisely why this is
     * worth saying out loud.
     *
     * <p>Package-private and returning its lines rather than logging them, so the wording and the
     * once-per-run dedup are testable without capturing {@code IJ.log}.
     */
    static List<String> reportIgnoredSubFolders(File datasetDir, List<String> ignoredNames) {
        List<String> fresh = new ArrayList<>();
        for (String name : ignoredNames) {
            if (reportedIgnoredSubFolders.add(name)) fresh.add(name);
        }
        if (fresh.isEmpty()) return Collections.emptyList();

        List<String> lines = new ArrayList<>();
        // First report of the run — everything in the set arrived just now, so the explanation
        // is printed once and every later dataset only adds its new names underneath it.
        if (reportedIgnoredSubFolders.size() == fresh.size()) {
            lines.add(LOG + "NOTE — sub-folder(s) not named as a plain number were not read as Z layers.");
            lines.add(LOG + "  Normal for folders that are not Z layers (notes, QC, backups). Worth a"
                    + " look only if one of them is a Z layer whose name is wrong, because such a"
                    + " layer is left out of the projection entirely.");
            lines.add(LOG + "  A left-out layer does not shift the Z indices — the index → Z mapping"
                    + " is built from the layers that were kept, so every index still resolves to the"
                    + " depth it names. What it changes is that the layer never competes: a pixel"
                    + " whose true extremum lay in it is attributed to the nearest surviving layer"
                    + " instead, wrong in both the z-origin map and the raw projection, while every"
                    + " pixel whose extremum was in a kept layer is exactly right.");
            lines.add(LOG + "  Each name is listed once per run, however many datasets it appears in.");
        }
        for (String name : fresh) {
            lines.add(LOG + "  ignored: '" + name + "'  (first seen in dataset '"
                    + datasetDir.getName() + "')");
        }
        return lines;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** How many rejected sub-folder names the failure message spells out before summarising. */
    private static final int MAX_NAMES_LISTED = 5;

    /**
     * Explains <em>why</em> no z-layers were found, distinguishing the three states the old
     * single message conflated. Called on the failure path only, so the extra directory listing
     * costs a healthy scan nothing.
     *
     * <ul>
     *   <li><b>Unreadable</b> — {@code listFiles} returned {@code null}: not a folder, missing,
     *       or no permission. Reporting that as "no numeric sub-folders" was actively
     *       misleading.</li>
     *   <li><b>Sub-folders present, none numeric</b> — the case worth naming: something
     *       <em>was</em> found and silently rejected, so the names are listed. A user with
     *       {@code z-300/} and {@code z-299/} was previously told nothing was found at all.</li>
     *   <li><b>No sub-folders</b> — genuinely empty, or holding only files.</li>
     * </ul>
     *
     * @param unreadable whether the filtered {@code listFiles} returned {@code null}
     */
    private static String noZLayersMessage(File datasetDir, boolean unreadable) {
        if (unreadable) {
            return "Cannot read dataset folder: " + datasetDir.getAbsolutePath()
                    + "\nIt may not exist, may not be a folder, or may not be readable.";
        }
        File[] allSubDirs = datasetDir.listFiles(File::isDirectory);
        if (allSubDirs == null || allSubDirs.length == 0) {
            return "No sub-folders in dataset: " + datasetDir.getAbsolutePath()
                    + "\nExpected one sub-folder per Z layer, named by its Z value"
                    + " (e.g. -300, -299, ...).";
        }
        java.util.Arrays.sort(allSubDirs);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(allSubDirs.length, MAX_NAMES_LISTED); i++) {
            shown.add(allSubDirs[i].getName());
        }
        String listed = shown.toString();
        if (allSubDirs.length > MAX_NAMES_LISTED) {
            listed = listed.substring(0, listed.length() - 1)
                    + ", …and " + (allSubDirs.length - MAX_NAMES_LISTED) + " more]";
        }
        return "No numeric z-layer sub-folders in dataset: " + datasetDir.getAbsolutePath()
                + "\n" + allSubDirs.length + " sub-folder(s) were found but none is named as a"
                + " plain number, so none was treated as a Z layer: " + listed
                + "\nA Z-layer folder's name must be the number alone — \"-300\", not \"z-300\""
                + " or \"-300um\". Rename them and re-run.";
    }

    /**
     * Parses a z-layer folder name to its numeric Z value, or {@code null} if it is not
     * numeric (used both to filter which sub-folders count as z-layers and to sort them).
     */
    static Double parseZ(String folderName) {
        try {
            return Double.parseDouble(folderName.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
