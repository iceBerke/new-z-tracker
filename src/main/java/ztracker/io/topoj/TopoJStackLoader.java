package ztracker.io.topoj;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool 3 (TopoJ / direct-Z) counterpart to {@link TiffStackLoader}.
 *
 * <p>Loads a folder of <b>32-bit float</b> TIFF projection images — as produced by
 * Fiji's TopoJ — into a 3-D {@code float} array. Unlike {@link TiffStackLoader}
 * (whose pixel values are integer <i>indices</i> into a JSON Z-mapping and are
 * therefore rounded to {@code int} on read), a TopoJ pixel value <b>is</b> the
 * physical Z coordinate in micrometres directly, so it must be kept as a float —
 * there is no mapping and no rounding.
 *
 * <p>Everything else mirrors {@link TiffStackLoader}: files are natural-sorted by the
 * trailing integer in the filename, a {@code frame → stackIndex} map is built, and
 * gaps in frame numbering are supported. Only 32-bit float TIFFs are accepted (8-bit,
 * 16-bit, and 24-bit RGB are rejected with a clear message), matching the Python
 * script's {@code float32}-only requirement.
 *
 * <p><b>Filename flexibility (Tool 3 only).</b> The frame index is taken from the
 * integer the filename <i>ends with</i> — any prefix text is allowed and any
 * zero-padding width is accepted (the padding is not hard-coded: {@code frame7.tif},
 * {@code topoj_0007.tif}, and {@code height_map_00000007.tif} all resolve to frame 7).
 * Unlike {@link TiffStackLoader} (which takes the last digit run <i>anywhere</i> in the
 * name and silently falls back to frame 0), this loader anchors the match to the end of
 * the base name and <b>rejects</b> any file that does not end with an integer, rather
 * than silently collapsing it to frame 0 (where several such files would clobber each
 * other in the {@code frame → stackIndex} map).
 */
public class TopoJStackLoader {

    /** The integer the base filename (extension stripped) ends with — any zero-padding width. */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    /** Sentinel returned by {@link #extractFrameNumber} when a name has no trailing integer. */
    private static final int NO_FRAME_NUMBER = -1;

    // ── Public result container ───────────────────────────────────────────────

    public static class LoadedFloatStack {
        /** Pixel data: [stackIndex][y][x]. Values are Z coordinates in µm (un-rounded). */
        public final float[][][] pixels;
        /** Maps frame number (from filename) → index into {@code pixels}. */
        public final Map<Integer, Integer> frameToIdx;
        /** Sorted list of all frame numbers present (parallel to stack order). */
        public final List<Integer> frameNumbers;
        public final int width;
        public final int height;

        public LoadedFloatStack(float[][][] pixels,
                                Map<Integer, Integer> frameToIdx,
                                List<Integer> frameNumbers,
                                int width, int height) {
            this.pixels       = pixels;
            this.frameToIdx   = frameToIdx;
            this.frameNumbers = frameNumbers;
            this.width        = width;
            this.height       = height;
        }

        public int frameCount() { return frameNumbers.size(); }
        public int firstFrame()  { return frameNumbers.get(0); }
        public int lastFrame()   { return frameNumbers.get(frameNumbers.size() - 1); }

        /**
         * Exposes this stack's frame map as a pixel-less {@link LoadedStack}, purely so
         * {@link ztracker.core.FrameAligner} (which is written against {@code LoadedStack}
         * but only ever reads {@code frameToIdx}/{@code firstFrame()}/{@code lastFrame()},
         * never the pixel array) can be reused unchanged for Tool 3.
         *
         * <p>The returned stack shares this stack's {@code frameToIdx} and {@code frameNumbers}
         * and carries a zero-length pixel array — it is a frame-index view only and must not
         * be sampled.
         */
        public LoadedStack frameView() {
            return new LoadedStack(new int[0][][], frameToIdx, frameNumbers, width, height);
        }
    }

    private TopoJStackLoader() {}

    /**
     * Loads all TIFF files in {@code folder} and returns a {@link LoadedFloatStack}.
     *
     * @param folder directory containing .tif / .tiff files
     * @return loaded float stack with frame mapping
     * @throws IOException if the folder is empty, a file cannot be read, or a file
     *                     is not 32-bit float (or bit depths are mixed)
     */
    public static LoadedFloatStack load(File folder) throws IOException {
        File[] tifFiles = folder.listFiles(
                f -> f.isFile() && f.getName().toLowerCase().matches(".*\\.tiff?"));

        if (tifFiles == null || tifFiles.length == 0) {
            throw new IOException("No TIFF files found in: " + folder.getAbsolutePath());
        }

        // Every file must end with an integer (the frame index). Reject up front with a
        // clear message rather than silently mapping a non-conforming name to frame 0
        // (where several such files would clobber each other in frameToIdx).
        List<String> badNames = new ArrayList<>();
        for (File f : tifFiles) {
            if (extractFrameNumber(f) == NO_FRAME_NUMBER) badNames.add(f.getName());
        }
        if (!badNames.isEmpty()) {
            throw new IOException(
                    "These TIFF filenames do not end with a frame number: " + badNames
                    + "\nThe TopoJ / direct-Z extractor takes the frame index from the integer"
                    + " the filename ends with (any prefix, any zero-padding width — e.g."
                    + " frame7.tif, topoj_0007.tif). Rename so each file ends with its frame"
                    + " number before the .tif extension.");
        }

        // Sort by the trailing integer in the filename (natural sort)
        Arrays.sort(tifFiles, Comparator.comparingInt(TopoJStackLoader::extractFrameNumber));

        // Build frame number list and mapping
        List<Integer> frameNumbers = new ArrayList<>(tifFiles.length);
        Map<Integer, Integer> frameToIdx = new HashMap<>(tifFiles.length * 2);

        for (int i = 0; i < tifFiles.length; i++) {
            int frameNum = extractFrameNumber(tifFiles[i]);
            frameNumbers.add(frameNum);
            frameToIdx.put(frameNum, i);
        }

        // Read first frame to determine dimensions and bit depth
        ImagePlus first = IJ.openImage(tifFiles[0].getAbsolutePath());
        if (first == null) {
            throw new IOException("Could not open TIFF: " + tifFiles[0].getName());
        }
        int width    = first.getWidth();
        int height   = first.getHeight();
        int bitDepth = first.getBitDepth();
        first.close();

        if (bitDepth != 32) {
            throw new IOException(
                    "Unsupported TIFF bit depth (" + bitDepth + "-bit): " + tifFiles[0].getName()
                    + "\nThe TopoJ / direct-Z extractor requires 32-bit float TIFFs whose pixel"
                    + " values are Z coordinates in µm. (For 16-bit/32-bit indexed projections"
                    + " with a JSON Z-mapping, use the 3D Z-Coordinate Extractor instead.)");
        }

        // Pre-allocate
        float[][][] pixels = new float[tifFiles.length][height][width];

        // Load all frames
        for (int i = 0; i < tifFiles.length; i++) {
            IJ.showProgress(i, tifFiles.length);
            readFrameInto(tifFiles[i], pixels[i], width, height);
        }
        IJ.showProgress(1.0);

        // Log gap information
        int expectedCount = frameNumbers.get(frameNumbers.size() - 1) - frameNumbers.get(0) + 1;
        int gapCount = expectedCount - frameNumbers.size();

        IJ.log(String.format(
                "[TopoJStackLoader] Loaded %d frames (32-bit float) | range %d–%d | gaps: %d",
                tifFiles.length, frameNumbers.get(0),
                frameNumbers.get(frameNumbers.size() - 1), gapCount));

        return new LoadedFloatStack(pixels, frameToIdx, frameNumbers, width, height);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the integer the filename <b>ends with</b> — the frame index (used for
     * sorting and mapping). The extension is stripped first, then the trailing digit run
     * is parsed, so any prefix and any zero-padding width are accepted
     * ({@code height_map_00000007.tif} → 7). Anchoring to the end also sidesteps incidental
     * numbers earlier in the name (e.g. a "32bit" tag) without relying on the extension
     * being digit-free.
     *
     * @return the trailing integer, or {@link #NO_FRAME_NUMBER} if the base name has none
     *         (the caller rejects such files with a clear message)
     */
    static int extractFrameNumber(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        Matcher m = TRAILING_NUMBER.matcher(base);
        return m.find() ? Integer.parseInt(m.group(1)) : NO_FRAME_NUMBER;
    }

    /**
     * Opens one 32-bit float TIFF and copies its pixels into the pre-allocated
     * {@code dest} slice via {@link ImageProcessor#getf(int, int)} — the raw float
     * Z value is kept exactly, never rounded.
     */
    private static void readFrameInto(File file, float[][] dest, int width, int height)
            throws IOException {
        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            throw new IOException("Could not open TIFF: " + file.getName());
        }
        if (imp.getBitDepth() != 32) {
            imp.close();
            throw new IOException(
                    "Mixed bit depths in TIFF folder: " + file.getName()
                    + " is " + imp.getBitDepth() + "-bit, expected 32-bit float.");
        }
        if (imp.getWidth() != width || imp.getHeight() != height) {
            imp.close();
            throw new IOException(
                    "Inconsistent image dimensions in TIFF folder: " + file.getName()
                    + " is " + imp.getWidth() + "x" + imp.getHeight()
                    + ", expected " + width + "x" + height + "."
                    + "\nEvery Z-map frame must match the first frame's dimensions.");
        }
        try {
            ImageProcessor ip = imp.getProcessor();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    dest[y][x] = ip.getf(x, y);
                }
            }
        } finally {
            imp.close();
        }
    }
}
