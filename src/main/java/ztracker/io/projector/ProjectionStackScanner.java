package ztracker.io.projector;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.io.TiffDecoder;
import ij.plugin.FileInfoVirtualStack;
import ij.process.ImageProcessor;
import ztracker.project.ZProjector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the <em>TIFF-stack</em> flavour of projection input: one multi-slice TIFF per
 * timepoint, sitting directly in the dataset folder, whose slices are the Z layers.
 *
 * <p>Expected layout of a single <em>dataset</em> folder:
 * <pre>
 * datasetDir/
 *   00001.tif   ← timepoint 1, one slice per Z layer
 *   00002.tif   ← timepoint 2, same Z layers
 *   ...
 * </pre>
 *
 * <p>This is the sibling of {@link ProjectionInputScanner} (the z-layer-sub-folder layout);
 * the two differ only in where the images and the Z values come from, and both feed the
 * same {@link ProjectionSource} contract, so the plugin's projection/export path is shared.
 *
 * <h2>Where Z comes from</h2>
 * There are no Z-named folders here, so each layer's physical Z (µm) is read from the
 * <b>ImageJ slice label</b> — the {@code z = -400.000} form Fiji writes for a Z stack.
 * A label that is just a bare number ({@code -400.000}) is accepted too. Anything else is
 * <b>rejected with a clear message</b> rather than guessed at: silently mislabelled depth
 * would corrupt every downstream Z coordinate. Layers are then sorted <b>ascending by Z</b>,
 * exactly as the folder layout sorts its Z-named sub-folders, so the JSON mapping's
 * {@code index → Z} ordering (and tie-breaking toward the lowest Z) is identical for both
 * input types regardless of the order the slices happen to sit in the file.
 *
 * <h2>Memory</h2>
 * One timepoint here is one large file — a 401-slice 1051×1674 stack is ~700 MB, and holding
 * every slice as {@code float[][]} at once would need ~2.8 GB. So slices are read
 * <b>one at a time</b> from a virtual (on-demand) stack and folded straight into
 * {@link ZProjector.Accumulator}, keeping peak memory at roughly one slice plus two
 * full-frame result buffers. Files that cannot be opened virtually (e.g. compressed TIFFs)
 * fall back to a normal in-memory open.
 */
public final class ProjectionStackScanner {

    private ProjectionStackScanner() {}

    /** {@code z = -400.000} / {@code Z: 12.5} — ImageJ's slice-label convention for Z stacks. */
    private static final Pattern Z_ASSIGNMENT =
            Pattern.compile("(?i)\\bz\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");
    /** A label that is nothing but a number, e.g. {@code -400.000}. */
    private static final Pattern BARE_NUMBER =
            Pattern.compile("\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*");
    /** Trailing integer of a base filename — {@code 00001} in {@code 00001.tif}. */
    private static final Pattern TRAILING_INT = Pattern.compile("(\\d+)$");

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Scans one dataset folder for its per-timepoint TIFF stacks, taking the dataset's Z
     * layers from the <em>first</em> stack's slice labels (every later timepoint is checked
     * against them when it is projected).
     *
     * @throws IOException if the folder holds no TIFFs, the first stack has fewer than two
     *                     slices, or its slice labels carry no usable Z
     */
    public static StackScan scanDataset(File datasetDir) throws IOException {
        List<String> filenames = listStackFilenames(datasetDir);
        if (filenames.isEmpty()) {
            throw new IOException(
                    "No .tif stacks found directly in dataset: " + datasetDir.getAbsolutePath()
                    + "\nExpected one multi-slice TIFF per timepoint (e.g. 00001.tif, 00002.tif, ...).");
        }

        String reference = filenames.get(0);
        OpenedStack opened = openStack(new File(datasetDir, reference));
        double[] sliceZ;
        try {
            if (opened.stack.getSize() < 2) {
                throw new IOException(
                        "'" + reference + "' has only one slice, so it is not a Z stack."
                        + "\nIf your Z layers are separate folders, choose the 'Z-layer sub-folders'"
                        + " input type instead.");
            }
            sliceZ = readSliceZValues(opened.stack, reference);
        } finally {
            opened.close();
        }

        // Ascending Z, like the folder layout's numerically-sorted sub-folders.
        double[] zValues = sliceZ.clone();
        Arrays.sort(zValues);
        for (int i = 1; i < zValues.length; i++) {
            if (zValues[i] == zValues[i - 1]) {
                throw new IOException("Duplicate Z value " + zValues[i] + " in the slice labels of '"
                        + reference + "' — each slice must have its own depth.");
            }
        }

        Map<Double, Integer> zIndex = new HashMap<>();
        List<String> zLayerNames = new ArrayList<>(zValues.length);
        for (int i = 0; i < zValues.length; i++) {
            zIndex.put(zValues[i], i);
            zLayerNames.add(formatZ(zValues[i]));
        }

        return new StackScan(datasetDir, filenames, zValues, zLayerNames, zIndex, reference);
    }

    /**
     * Quick check (no TIFF reads) for whether {@code dir} looks like a stack dataset — i.e. it
     * directly contains at least one {@code .tif}. Used by the plugin's batch mode, mirroring
     * {@link ProjectionInputScanner#isDataset}; a folder that passes here but turns out not to
     * hold real Z stacks is rejected with a clear message by {@link #scanDataset}.
     */
    public static boolean isDataset(File dir) {
        return !listStackFilenames(dir).isEmpty();
    }

    // ── The source ────────────────────────────────────────────────────────────

    /** One stack dataset: its timepoint files and the Z layers read from the slice labels. */
    public static final class StackScan implements ProjectionSource {

        private final File datasetDir;
        private final List<String> filenames;
        private final double[] zValues;
        private final List<String> zLayerNames;
        /** Z value → its index in {@link #zValues}, for mapping each timepoint's slices. */
        private final Map<Double, Integer> zIndex;
        /** The timepoint whose labels defined the Z layers — named in mismatch messages. */
        private final String referenceFile;

        StackScan(File datasetDir, List<String> filenames, double[] zValues,
                  List<String> zLayerNames, Map<Double, Integer> zIndex, String referenceFile) {
            this.datasetDir    = datasetDir;
            this.filenames     = filenames;
            this.zValues       = zValues;
            this.zLayerNames   = zLayerNames;
            this.zIndex        = zIndex;
            this.referenceFile = referenceFile;
        }

        @Override public File datasetDir()             { return datasetDir; }
        @Override public List<String> timepointLabels() { return filenames; }
        @Override public List<String> zLayerNames()     { return zLayerNames; }
        @Override public double[] zValues()             { return zValues; }

        /**
         * Streams one timepoint's stack slice-by-slice into {@link ZProjector.Accumulator},
         * in ascending-Z order, so the whole stack is never in memory at once.
         *
         * <p>A timepoint may cover only <em>some</em> of the dataset's Z layers (the folder
         * layout supports the same thing) — its slices keep their global Z indices either way.
         * A slice whose Z is not one of the dataset's layers is an error for that timepoint.
         */
        @Override
        public Projected projectTimepoint(ZProjector.Mode mode, String label) throws IOException {
            File file = new File(datasetDir, label);
            if (!file.isFile()) {
                throw new IOException("Timepoint file not found: " + file.getAbsolutePath());
            }

            OpenedStack opened = openStack(file);
            try {
                ImageStack stack = opened.stack;
                double[] sliceZ = readSliceZValues(stack, label);

                // Slice numbers (1-based) ordered by ascending Z, so ties resolve to the
                // lowest Z exactly as the folder layout's ascending walk does.
                Integer[] order = new Integer[sliceZ.length];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Double.compare(sliceZ[a], sliceZ[b]));

                ZProjector.Accumulator acc = new ZProjector.Accumulator(mode);
                float[][] buffer = null;
                int bitDepth = -1;

                for (int k = 0; k < order.length; k++) {
                    int slice = order[k] + 1;
                    double z  = sliceZ[order[k]];
                    Integer global = zIndex.get(z);
                    if (global == null) {
                        throw new IOException("Slice " + slice + " of '" + label + "' has Z=" + z
                                + ", which is not one of this dataset's Z layers (taken from '"
                                + referenceFile + "': " + formatZ(zValues[0]) + " … "
                                + formatZ(zValues[zValues.length - 1]) + ").");
                    }

                    ImageProcessor ip = stack.getProcessor(slice);
                    // Colour slices have no single intensity to project: ColorProcessor.getf
                    // hands back the packed ARGB int, so projecting one would compare packed
                    // colour values and yield a confident but meaningless z-origin map.
                    if (ip.getBitDepth() == 24) {
                        throw new IOException(
                                "Colour (RGB) slice within '" + label + "': slice " + slice
                                + " is 24-bit RGB."
                                + "\nZ-projection needs grayscale intensity — convert the stack"
                                + " with Image > Type > 8-bit or 16-bit first.");
                    }
                    if (bitDepth < 0) {
                        bitDepth = ip.getBitDepth();
                        buffer   = new float[ip.getHeight()][ip.getWidth()];
                    } else if (ip.getWidth() != buffer[0].length || ip.getHeight() != buffer.length) {
                        // Defensive, and deliberately untested — though not because a
                        // mismatched slice is refused. ImageStack.addSlice *accepts* a
                        // differently-sized processor and silently re-frames it to the stack's
                        // size (crop or zero-pad, ij 1.54f ImageStack:106-114), and
                        // ImageStack.getProcessor then builds every processor at that one
                        // width/height — so no ImageStack can report per-slice sizes at all.
                        // Both ways this method obtains a stack are closed too: tryOpenVirtual
                        // demands info.length == 1, whose single FileInfo is cloned per slice,
                        // and the IJ.openImage fallback sends a multi-IFD TIFF through
                        // Opener.openTiffStack, which bails out unless allSameSizeAndType
                        // (Opener:800). The folder layout's identical check IS tested, because
                        // there each Z layer is a separate file opened on its own and nothing
                        // enforces a common size — the asymmetry is the layouts', not a gap.
                        throw new IOException(
                                "Inconsistent slice dimensions within '" + label + "': slice " + slice
                                + " is " + ip.getWidth() + "x" + ip.getHeight() + ", expected "
                                + buffer[0].length + "x" + buffer.length + ".");
                    } else if (ip.getBitDepth() != bitDepth) {
                        // Defensive, and deliberately untested: a mixed-depth stack cannot
                        // survive a round-trip to a file. ImageJ's FileSaver writes the whole
                        // stack using the *first* slice's type and coerces the rest, so a
                        // reopened stack always reports one depth (verified against ij 1.54f).
                        // Kept for parity with the folder layout, where each Z layer is its own
                        // file and mixing really does happen — see that check for why it matters.
                        throw new IOException(
                                "Inconsistent bit depths within '" + label + "': slice " + slice
                                + " is " + ip.getBitDepth() + "-bit, expected " + bitDepth + "-bit."
                                + "\nAll slices of one timepoint must share a bit depth — the"
                                + " projection compares their raw intensities.");
                    }
                    readInto(ip, buffer);
                    acc.add(buffer, global); // add() copies — the buffer is reused next slice
                }

                return new Projected(acc.result(), bitDepth);
            } finally {
                opened.close();
            }
        }
    }

    // ── Slice labels → Z ──────────────────────────────────────────────────────

    /** Reads every slice's Z from its label, in file order (index 0 = slice 1). */
    static double[] readSliceZValues(ImageStack stack, String filename) throws IOException {
        int n = stack.getSize();
        double[] z = new double[n];
        for (int i = 1; i <= n; i++) {
            String label = stack.getSliceLabel(i);
            Double parsed = parseZLabel(label);
            if (parsed == null) {
                throw new IOException(
                        "Slice " + i + " of '" + filename + "' has no readable Z in its label"
                        + (label == null || label.isEmpty() ? " (the label is empty)"
                                                            : ": \"" + firstLine(label) + "\"")
                        + ".\nThis input type takes each Z depth from the ImageJ slice label,"
                        + " e.g. \"z = -400.000\".");
            }
            z[i - 1] = parsed;
        }
        return z;
    }

    /**
     * Parses one slice label to its Z value, or {@code null} if it holds none.
     *
     * <p>Accepts ImageJ's {@code z = -400.000} form anywhere in the label (labels may carry
     * several lines of metadata), or a label that is nothing but a number. It deliberately
     * does <em>not</em> pick a stray number out of arbitrary text — a filename-like label
     * would otherwise yield a confidently wrong depth.
     */
    static Double parseZLabel(String label) {
        if (label == null) return null;
        Matcher assign = Z_ASSIGNMENT.matcher(label);
        if (assign.find()) return Double.valueOf(assign.group(1));
        Matcher bare = BARE_NUMBER.matcher(label);
        if (bare.matches()) return Double.valueOf(bare.group(1));
        return null;
    }

    // ── File listing ──────────────────────────────────────────────────────────

    /**
     * The dataset folder's TIFFs, ordered by the integer their name ends with when they have
     * one ({@code 00001.tif} before {@code 00010.tif} whatever the padding), falling back to
     * plain name order.
     */
    static List<String> listStackFilenames(File dir) {
        File[] files = dir == null ? null : dir.listFiles(
                f -> f.isFile() && f.getName().toLowerCase().matches(".*\\.tiff?"));
        List<String> names = new ArrayList<>();
        if (files == null) return names;
        for (File f : files) names.add(f.getName());
        names.sort(ProjectionStackScanner::compareTimepointNames);
        return names;
    }

    static int compareTimepointNames(String a, String b) {
        Long na = trailingInt(a);
        Long nb = trailingInt(b);
        if (na != null && nb != null && !na.equals(nb)) return na.compareTo(nb);
        return a.compareTo(b);
    }

    private static Long trailingInt(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        Matcher m = TRAILING_INT.matcher(base);
        if (!m.find()) return null;
        try {
            return Long.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null; // absurdly long digit run — fall back to name order
        }
    }

    // ── Stack opening / pixel reads ───────────────────────────────────────────

    /** An opened stack plus whatever needs releasing afterwards. */
    private static final class OpenedStack {
        final ImageStack stack;
        final ImagePlus imp; // null when the stack was opened virtually

        OpenedStack(ImageStack stack, ImagePlus imp) {
            this.stack = stack;
            this.imp   = imp;
        }

        void close() {
            if (imp != null) imp.close();
        }
    }

    /**
     * Opens a stack without reading pixels up front where possible: an uncompressed,
     * single-IFD multi-slice TIFF becomes a {@link FileInfoVirtualStack} (slices read on
     * demand); anything else falls back to a normal {@link IJ#openImage} load.
     */
    private static OpenedStack openStack(File file) throws IOException {
        ImageStack virtual = tryOpenVirtual(file);
        if (virtual != null) return new OpenedStack(virtual, null);

        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            throw new IOException("Could not open TIFF stack: " + file.getAbsolutePath());
        }
        return new OpenedStack(imp.getStack(), imp);
    }

    /** @return an on-demand stack, or {@code null} if this file cannot be read that way */
    static ImageStack tryOpenVirtual(File file) {
        try {
            String parent = file.getParent();
            TiffDecoder td = new TiffDecoder(parent == null ? "" : parent + File.separator,
                    file.getName());
            FileInfo[] info = td.getTiffInfo();
            if (info == null || info.length != 1) return null;      // not a contiguous ImageJ stack
            if (info[0].nImages <= 1) return null;                  // single image — nothing to gain
            if (info[0].compression > FileInfo.COMPRESSION_NONE) return null; // needs decoding
            return new FileInfoVirtualStack(info[0], false);
        } catch (Throwable t) {
            return null; // any trouble reading the header → use the plain in-memory open
        }
    }

    /**
     * Copies a slice's intensities into {@code buffer}. Goes straight to the backing pixel
     * array for the common processor types (a 1051×1674 slice is 1.7 M pixels, and a deep
     * stack multiplies that by hundreds), falling back to
     * {@link ImageProcessor#getf(int, int)} — the same accessor the folder layout uses — for
     * anything else.
     */
    static void readInto(ImageProcessor ip, float[][] buffer) {
        final int width  = ip.getWidth();
        final int height = ip.getHeight();
        Object pixels = ip.getPixels();

        if (pixels instanceof byte[]) {
            byte[] px = (byte[]) pixels;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) buffer[y][x] = px[row + x] & 0xff;
            }
        } else if (pixels instanceof short[]) {
            short[] px = (short[]) pixels;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) buffer[y][x] = px[row + x] & 0xffff;
            }
        } else if (pixels instanceof float[]) {
            float[] px = (float[]) pixels;
            for (int y = 0; y < height; y++) {
                System.arraycopy(px, y * width, buffer[y], 0, width);
            }
        } else {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) buffer[y][x] = ip.getf(x, y);
            }
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    /** Compact Z text for logs and layer names: {@code -400} rather than {@code -400.0}. */
    static String formatZ(double z) {
        if (z == Math.rint(z) && !Double.isInfinite(z)) {
            return String.valueOf((long) z);
        }
        return Double.toString(z);
    }

    private static String firstLine(String label) {
        int nl = label.indexOf('\n');
        return nl < 0 ? label : label.substring(0, nl);
    }
}
