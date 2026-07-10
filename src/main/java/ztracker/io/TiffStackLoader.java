package ztracker.io;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a folder of 16-bit or 32-bit indexed TIFF projection images into a
 * 3-D int array and builds a frame-number → stack-index lookup map.
 *
 * <p>Frame numbers are extracted from filenames numerically, matching the
 * Python pipeline's {@code natural_sort_key} behaviour. Gaps in frame
 * numbering are fully supported.
 */
public class TiffStackLoader {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    // ── Public result container ───────────────────────────────────────────────

    public static class LoadedStack {
        /** Pixel data: [stackIndex][y][x]. Values are raw pixel indices. */
        public final int[][][] pixels;
        /** Maps frame number (from filename) → index into {@code pixels}. */
        public final Map<Integer, Integer> frameToIdx;
        /** Sorted list of all frame numbers present (parallel to stack order). */
        public final List<Integer> frameNumbers;
        public final int width;
        public final int height;

        public LoadedStack(int[][][] pixels,
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
    }

    private TiffStackLoader() {}

    /**
     * Loads all TIFF files in {@code folder} and returns a {@link LoadedStack}.
     *
     * @param folder directory containing .tif / .tiff files
     * @return loaded stack with frame mapping
     * @throws IOException if the folder is empty or a file cannot be read
     */
    public static LoadedStack load(File folder) throws IOException {
        File[] tifFiles = folder.listFiles(
                f -> f.isFile() && f.getName().toLowerCase().matches(".*\\.tiff?"));

        if (tifFiles == null || tifFiles.length == 0) {
            throw new IOException("No TIFF files found in: " + folder.getAbsolutePath());
        }

        // Sort by the trailing integer in the filename (natural sort)
        Arrays.sort(tifFiles, Comparator.comparingInt(TiffStackLoader::extractFrameNumber));

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

        if (bitDepth != 16 && bitDepth != 32) {
            throw new IOException(
                    "Unsupported TIFF bit depth (" + bitDepth + "-bit): " + tifFiles[0].getName()
                    + "\nOnly 16-bit and 32-bit indexed TIFFs are supported.");
        }

        // Pre-allocate
        int[][][] pixels = new int[tifFiles.length][height][width];

        // Load all frames
        for (int i = 0; i < tifFiles.length; i++) {
            IJ.showProgress(i, tifFiles.length);
            readFrameInto(tifFiles[i], pixels[i], width, height, bitDepth);
        }
        IJ.showProgress(1.0);

        // Log gap information
        int expectedCount = frameNumbers.get(frameNumbers.size() - 1) - frameNumbers.get(0) + 1;
        int gapCount = expectedCount - frameNumbers.size();

        IJ.log(String.format(
                "[TiffStackLoader] Loaded %d frames | range %d–%d | gaps: %d",
                tifFiles.length, frameNumbers.get(0),
                frameNumbers.get(frameNumbers.size() - 1), gapCount));

        return new LoadedStack(pixels, frameToIdx, frameNumbers, width, height);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the last integer found in a filename (used for sorting and mapping).
     * Falls back to 0 if none found (should not happen with well-named TIFF stacks).
     *
     * <p>Uses the last match rather than the first because filenames can contain
     * incidental numbers before the trailing frame index (e.g. {@code z_origin_32bit_0007.tif}
     * — the "32" in "32bit" is not the frame number).
     */
    static int extractFrameNumber(File file) {
        Matcher m = NUMBER_PATTERN.matcher(file.getName());
        String last = null;
        while (m.find()) last = m.group();
        return last != null ? Integer.parseInt(last) : 0;
    }

    /**
     * Opens one TIFF and copies its pixels into the pre-allocated {@code dest} slice.
     *
     * <p>16-bit frames use {@link ImageProcessor#getPixel(int, int)}, which already
     * returns the correct unsigned {@code 0–65535} value for a {@code ShortProcessor}.
     * 32-bit frames are backed by a {@code FloatProcessor}, so the index is read via
     * {@link ImageProcessor#getf(int, int)} and rounded — {@code getPixel} on a
     * {@code FloatProcessor} truncates toward zero, which can be off-by-one for
     * indices with float rounding error.
     */
    private static void readFrameInto(File file, int[][] dest, int width, int height, int bitDepth)
            throws IOException {
        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            throw new IOException("Could not open TIFF: " + file.getName());
        }
        if (imp.getBitDepth() != bitDepth) {
            imp.close();
            throw new IOException(
                    "Mixed bit depths in TIFF folder: " + file.getName()
                    + " is " + imp.getBitDepth() + "-bit, expected " + bitDepth + "-bit.");
        }
        try {
            ImageProcessor ip = imp.getProcessor();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    dest[y][x] = (bitDepth == 32)
                            ? Math.round(ip.getf(x, y))
                            : ip.getPixel(x, y);
                }
            }
        } finally {
            imp.close();
        }
    }
}
