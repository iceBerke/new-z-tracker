package ztracker.io.projector;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        File[] subDirs = datasetDir.listFiles(f -> f.isDirectory() && parseZ(f.getName()) != null);
        if (subDirs == null || subDirs.length == 0) {
            throw new IOException(
                    "No numeric z-layer sub-folders found in dataset: " + datasetDir.getAbsolutePath()
                    + "\nExpected sub-folders named by Z value (e.g. -300, -299, ...).");
        }

        // Sort z-layers by numeric value (float(z) in the Python script).
        java.util.Arrays.sort(subDirs, Comparator.comparingDouble(f -> parseZ(f.getName())));

        List<String> zLayerNames = new ArrayList<>(subDirs.length);
        double[] zValues = new double[subDirs.length];
        for (int i = 0; i < subDirs.length; i++) {
            zLayerNames.add(subDirs[i].getName());
            zValues[i] = parseZ(subDirs[i].getName());
        }

        // Union of .tif filenames across all layers (a TreeSet dedups and sorts lexicographically,
        // matching the script's set() + sorted()).
        TreeSet<String> filenames = new TreeSet<>();
        for (File layer : subDirs) {
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
     */
    public static boolean isDataset(File dir) {
        File[] zs = dir.listFiles(f -> f.isDirectory() && parseZ(f.getName()) != null);
        return zs != null && zs.length > 0;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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
