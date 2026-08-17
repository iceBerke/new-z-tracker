package ztracker.io.extractor;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TiffStackLoader} — Tool 2's indexed 16-/32-bit TIFF stack loader.
 *
 * <p>Beyond filename parsing, these exercise the loader through ImageJ's real headless TIFF
 * read/write (same approach as {@code TopoJStackLoaderTest} / {@code ProjectionExporterTest};
 * nothing is mocked), covering the behaviours CLAUDE.md documents for this class: unsigned
 * 16-bit reads via {@code getPixel}, 32-bit reads via {@code getf} + {@code Math.round} (rather
 * than {@code getPixel}, which truncates a {@code FloatProcessor} toward zero), rejection of
 * mixed/unsupported bit depths, frame-gap support, and — added p10.1 — the per-frame dimension
 * guard.
 */
class TiffStackLoaderTest {

    @Test
    void extractFrameNumber_usesTrailingNumber_ignoringIncidentalDigitsEarlierInName() {
        // "32" in "32bit" must not be mistaken for the frame index.
        assertEquals(7, TiffStackLoader.extractFrameNumber(new File("z_origin_32bit_0007.tif")));
    }

    @Test
    void extractFrameNumber_plainNumericFilename() {
        assertEquals(42, TiffStackLoader.extractFrameNumber(new File("0042.tif")));
    }

    @Test
    void extractFrameNumber_noDigits_fallsBackToZero() {
        assertEquals(0, TiffStackLoader.extractFrameNumber(new File("frame.tif")));
    }

    // ── Headless TIFF helpers ─────────────────────────────────────────────────

    private static void writeShortTiff(File file, int[][] values) {
        int h = values.length, w = values[0].length;
        ShortProcessor sp = new ShortProcessor(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                sp.set(x, y, values[y][x]);
        IJ.saveAsTiff(new ImagePlus(file.getName(), sp), file.getAbsolutePath());
    }

    private static void writeFloatTiff(File file, float[][] values) {
        int h = values.length, w = values[0].length;
        FloatProcessor fp = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                fp.setf(x, y, values[y][x]);
        IJ.saveAsTiff(new ImagePlus(file.getName(), fp), file.getAbsolutePath());
    }

    // ── Pixel reads ───────────────────────────────────────────────────────────

    @Test
    void load_reads16BitIndicesUnsigned_acrossTheFullRange(@TempDir Path dir) throws Exception {
        // getPixel on a ShortProcessor already returns the unsigned 0–65535 value; anything
        // sign-extending would turn 40000/65535 into negatives and index the mapping wrongly.
        int[][] frame = {
            {     0,     1 },
            { 40000, 65535 },
        };
        writeShortTiff(new File(dir.toFile(), "z_origin_0000.tif"), frame);

        LoadedStack stack = TiffStackLoader.load(dir.toFile());

        assertEquals(1, stack.frameCount());
        assertEquals(2, stack.width);
        assertEquals(2, stack.height);
        int idx = stack.frameToIdx.get(0);
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++)
                assertEquals(frame[y][x], stack.pixels[idx][y][x],
                        "16-bit index must read unsigned at (" + x + "," + y + ")");
    }

    @Test
    void load_reads32BitIndices_roundedToNearest_notTruncatedTowardZero(@TempDir Path dir)
            throws Exception {
        // A 32-bit indexed TIFF is backed by a FloatProcessor, so the loader reads getf() and
        // rounds. Every value here is chosen so truncation (what getPixel would do) gives a
        // DIFFERENT answer than rounding: 6.75→7 not 6, 9.5→10 not 9, 2.999→3 not 2.
        float[][] frame = {
            { 6.75f,  9.5f },
            { 2.999f, 0.4f },
        };
        int[][] rounded    = { { 7, 10 }, { 3, 0 } };
        int[][] truncated  = { { 6,  9 }, { 2, 0 } };
        writeFloatTiff(new File(dir.toFile(), "z_origin_32bit_0000.tif"), frame);

        LoadedStack stack = TiffStackLoader.load(dir.toFile());

        int idx = stack.frameToIdx.get(0);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals(rounded[y][x], stack.pixels[idx][y][x],
                        "32-bit index must round to nearest at (" + x + "," + y + ")");
            }
        }
        // Guard the guard: the two expectations really do differ where it matters, so this
        // test cannot pass under a truncating implementation.
        assertTrue(rounded[0][0] != truncated[0][0] && rounded[0][1] != truncated[0][1],
                "test data must distinguish rounding from truncation");
    }

    // ── Frame mapping ─────────────────────────────────────────────────────────

    @Test
    void load_supportsFrameGaps_andMapsEachFrameNumberToItsStackIndex(@TempDir Path dir)
            throws Exception {
        // Frames 3, 4 and 9 present — a gap at 5–8. Frame numbers come from the filename, so
        // frameToIdx must map real frame numbers onto contiguous stack positions.
        writeShortTiff(new File(dir.toFile(), "z_origin_0003.tif"), new int[][]{{30}});
        writeShortTiff(new File(dir.toFile(), "z_origin_0004.tif"), new int[][]{{40}});
        writeShortTiff(new File(dir.toFile(), "z_origin_0009.tif"), new int[][]{{90}});

        LoadedStack stack = TiffStackLoader.load(dir.toFile());

        assertEquals(3, stack.frameCount());
        assertEquals(3, stack.firstFrame());
        assertEquals(9, stack.lastFrame());
        assertTrue(stack.frameToIdx.containsKey(3));
        assertTrue(stack.frameToIdx.containsKey(9));
        assertTrue(!stack.frameToIdx.containsKey(5), "frame 5 is a genuine gap");
        // Each frame's own pixel proves the mapping points at the right slice, not just that
        // the key exists.
        assertEquals(30, stack.pixels[stack.frameToIdx.get(3)][0][0]);
        assertEquals(40, stack.pixels[stack.frameToIdx.get(4)][0][0]);
        assertEquals(90, stack.pixels[stack.frameToIdx.get(9)][0][0]);
    }

    // ── Bit-depth rejection ───────────────────────────────────────────────────

    @Test
    void load_rejectsMixedBitDepthsWithinOneFolder(@TempDir Path dir) throws Exception {
        // Same dimensions throughout, so it is unambiguously the bit depth being rejected.
        writeShortTiff(new File(dir.toFile(), "z_origin_0000.tif"), new int[][]{{1, 2}});
        writeFloatTiff(new File(dir.toFile(), "z_origin_0001.tif"), new float[][]{{1f, 2f}});

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("Mixed bit depths"),
                "message should name the cause: " + e.getMessage());
        assertTrue(e.getMessage().contains("z_origin_0001.tif"),
                "message should name the offending file: " + e.getMessage());
    }

    @Test
    void load_rejects8BitTiff(@TempDir Path dir) throws Exception {
        ByteProcessor bp = new ByteProcessor(2, 2);
        bp.set(0, 0, 5);
        IJ.saveAsTiff(new ImagePlus("b", bp),
                new File(dir.toFile(), "z_origin_0000.tif").getAbsolutePath());

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("8-bit"), "message should name the bit depth: "
                + e.getMessage());
    }

    @Test
    void load_rejects24BitRgbTiff(@TempDir Path dir) throws Exception {
        ColorProcessor cp = new ColorProcessor(2, 2);
        cp.set(0, 0, 0xFF0000);
        IJ.saveAsTiff(new ImagePlus("rgb", cp),
                new File(dir.toFile(), "z_origin_0000.tif").getAbsolutePath());

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("24-bit"), "message should name the bit depth: "
                + e.getMessage());
    }

    // ── Per-frame dimension guard (p10.1) ─────────────────────────────────────

    @Test
    void load_rejectsLaterFrameSmallerThanFirst_16bit(@TempDir Path dir) throws Exception {
        // The silent-corruption case this guard exists for: getPixel is bounds-checked and
        // returns 0 past the real edge, and index 0 is a valid mapping key (the lowest-Z
        // layer), so without the guard those pixels yield a confident wrong Z marked OK.
        writeShortTiff(new File(dir.toFile(), "z_origin_0000.tif"), new int[][]{{1, 2}, {3, 4}});
        writeShortTiff(new File(dir.toFile(), "z_origin_0001.tif"), new int[][]{{5}});

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("z_origin_0001.tif"),
                "message should name the offending file: " + e.getMessage());
        assertTrue(e.getMessage().contains("1x1") && e.getMessage().contains("2x2"),
                "message should give actual and expected dimensions: " + e.getMessage());
    }

    @Test
    void load_rejectsLaterFrameLargerThanFirst_16bit(@TempDir Path dir) throws Exception {
        // A larger frame reads in-bounds and would silently become a top-left crop.
        writeShortTiff(new File(dir.toFile(), "z_origin_0000.tif"), new int[][]{{1, 2}, {3, 4}});
        writeShortTiff(new File(dir.toFile(), "z_origin_0001.tif"),
                new int[][]{{1, 2, 3}, {4, 5, 6}, {7, 8, 9}});

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("z_origin_0001.tif"),
                "message should name the offending file: " + e.getMessage());
        assertTrue(e.getMessage().contains("3x3") && e.getMessage().contains("2x2"),
                "message should give actual and expected dimensions: " + e.getMessage());
    }

    @Test
    void load_rejectsLaterFrameSmallerThanFirst_32bit(@TempDir Path dir) throws Exception {
        // The 32-bit path reads via getf, which is NOT bounds-checked — unguarded it shears
        // rows and then usually throws a bare AIOOBE. Separate accessor, separate test.
        writeFloatTiff(new File(dir.toFile(), "z_origin_0000.tif"),
                new float[][]{{1f, 2f}, {3f, 4f}});
        writeFloatTiff(new File(dir.toFile(), "z_origin_0001.tif"), new float[][]{{5f}});

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("z_origin_0001.tif"),
                "message should name the offending file: " + e.getMessage());
        assertTrue(e.getMessage().contains("1x1") && e.getMessage().contains("2x2"),
                "message should give actual and expected dimensions: " + e.getMessage());
    }

    @Test
    void load_rejectsLaterFrameLargerThanFirst_32bit(@TempDir Path dir) throws Exception {
        writeFloatTiff(new File(dir.toFile(), "z_origin_0000.tif"),
                new float[][]{{1f, 2f}, {3f, 4f}});
        writeFloatTiff(new File(dir.toFile(), "z_origin_0001.tif"),
                new float[][]{{1f, 2f, 3f}, {4f, 5f, 6f}, {7f, 8f, 9f}});

        IOException e = assertThrows(IOException.class, () -> TiffStackLoader.load(dir.toFile()));
        assertTrue(e.getMessage().contains("z_origin_0001.tif"),
                "message should name the offending file: " + e.getMessage());
        assertTrue(e.getMessage().contains("3x3") && e.getMessage().contains("2x2"),
                "message should give actual and expected dimensions: " + e.getMessage());
    }
}
