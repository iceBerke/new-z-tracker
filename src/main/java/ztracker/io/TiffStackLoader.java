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
 * Loads a folder of 16-bit TIFF projection images into a 3-D short array
 * and builds a frame-number → stack-index lookup map.
 *
 * <p>Frame numbers are extracted from filenames numerically, matching the
 * Python pipeline's {@code natural_sort_key} behaviour. Gaps in frame
 * numbering are fully supported.
 */
public class TiffStackLoader {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    // ── Public result container ───────────────────────────────────────────────

    public static class LoadedStack {
        /** Pixel data: [stackIndex][y][x]. Values are raw 16-bit indices (unsigned). */
        public final short[][][] pixels;
        /** Maps frame number (from filename) → index into {@code pixels}. */
        public final Map<Integer, Integer> frameToIdx;
        /** Sorted list of all frame numbers present (parallel to stack order). */
        public final List<Integer> frameNumbers;
        public final int width;
        public final int height;

        LoadedStack(short[][][] pixels,
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

        // Sort by the leading integer in the filename (natural sort)
        Arrays.sort(tifFiles, Comparator.comparingInt(TiffStackLoader::extractFrameNumber));

        // Build frame number list and mapping
        List<Integer> frameNumbers = new ArrayList<>(tifFiles.length);
        Map<Integer, Integer> frameToIdx = new HashMap<>(tifFiles.length * 2);

        for (int i = 0; i < tifFiles.length; i++) {
            int frameNum = extractFrameNumber(tifFiles[i]);
            frameNumbers.add(frameNum);
            frameToIdx.put(frameNum, i);
        }

        // Read first frame to determine dimensions
        ImagePlus first = IJ.openImage(tifFiles[0].getAbsolutePath());
        if (first == null) {
            throw new IOException("Could not open TIFF: " + tifFiles[0].getName());
        }
        int width  = first.getWidth();
        int height = first.getHeight();
        first.close();

        // Pre-allocate
        short[][][] pixels = new short[tifFiles.length][height][width];

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
                "[TiffStackLoader] Loaded %d frames | range %d–%d | gaps: %d",
                tifFiles.length, frameNumbers.get(0),
                frameNumbers.get(frameNumbers.size() - 1), gapCount));

        return new LoadedStack(pixels, frameToIdx, frameNumbers, width, height);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the first integer found in a filename (used for sorting and mapping).
     * Falls back to 0 if none found (should not happen with well-named TIFF stacks).
     */
    private static int extractFrameNumber(File file) {
        Matcher m = NUMBER_PATTERN.matcher(file.getName());
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /**
     * Opens one TIFF and copies its pixels into the pre-allocated {@code dest} slice.
     * Handles both signed/unsigned 16-bit by reading via ImageJ's ImageProcessor.
     */
    private static void readFrameInto(File file, short[][] dest, int width, int height)
            throws IOException {
        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            throw new IOException("Could not open TIFF: " + file.getName());
        }
        try {
            ImageProcessor ip = imp.getProcessor();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // getPixel returns the raw 16-bit value as int (0–65535)
                    dest[y][x] = (short) ip.getPixel(x, y);
                }
            }
        } finally {
            imp.close();
        }
    }
}
