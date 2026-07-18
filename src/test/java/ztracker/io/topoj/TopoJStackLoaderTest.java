package ztracker.io.topoj;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopoJStackLoader} — the direct-Z (TopoJ) float-TIFF loader.
 *
 * <p>The defining behaviour vs. {@link TiffStackLoader} is that 32-bit float pixel values
 * are the physical Z in µm and are kept <b>exactly, un-rounded</b> (Tool 2 rounds to int
 * indices). Uses ImageJ's real TIFF read/write headlessly, same approach as
 * {@code ProjectionExporterTest}.
 */
class TopoJStackLoaderTest {

    private static void writeFloatTiff(File file, float[][] values) {
        int h = values.length, w = values[0].length;
        FloatProcessor fp = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                fp.setf(x, y, values[y][x]);
        IJ.saveAsTiff(new ImagePlus(file.getName(), fp), file.getAbsolutePath());
    }

    @Test
    void load_preservesFloatZValuesExactly_unrounded(@TempDir Path dir) throws Exception {
        // Values chosen to be exactly representable in a 32-bit float, including a negative,
        // a sub-unit fraction, and a large value — none may be rounded to an integer.
        float[][] frame = {
            {  12.75f, -3.5f },
            {   0.25f, 42.0f },
        };
        writeFloatTiff(new File(dir.toFile(), "topoj_0000.tif"), frame);

        LoadedFloatStack stack = TopoJStackLoader.load(dir.toFile());
        assertEquals(1, stack.frameCount());
        assertEquals(2, stack.width);
        assertEquals(2, stack.height);

        int idx = stack.frameToIdx.get(0);
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++)
                assertEquals(frame[y][x], stack.pixels[idx][y][x], 0.0f,
                        "float Z must survive un-rounded at (" + x + "," + y + ")");
    }

    @Test
    void load_naturalSortsAndSupportsFrameGaps(@TempDir Path dir) throws Exception {
        // Frames 0 and 2 present (gap at 1); trailing number in filename is the frame index.
        writeFloatTiff(new File(dir.toFile(), "topoj_0000.tif"), new float[][]{{1.0f}});
        writeFloatTiff(new File(dir.toFile(), "topoj_0002.tif"), new float[][]{{2.0f}});

        LoadedFloatStack stack = TopoJStackLoader.load(dir.toFile());

        assertEquals(2, stack.frameCount());
        assertEquals(0, stack.firstFrame());
        assertEquals(2, stack.lastFrame());
        assertTrue(stack.frameToIdx.containsKey(0));
        assertTrue(stack.frameToIdx.containsKey(2));
        assertEquals(1.0f, stack.pixels[stack.frameToIdx.get(0)][0][0], 0.0f);
        assertEquals(2.0f, stack.pixels[stack.frameToIdx.get(2)][0][0], 0.0f);
    }

    @Test
    void frameView_exposesSameFrameMapForFrameAlignerReuse(@TempDir Path dir) throws Exception {
        writeFloatTiff(new File(dir.toFile(), "topoj_0005.tif"), new float[][]{{9.0f}});
        writeFloatTiff(new File(dir.toFile(), "topoj_0007.tif"), new float[][]{{9.0f}});

        LoadedFloatStack stack = TopoJStackLoader.load(dir.toFile());
        LoadedStack view = stack.frameView();

        assertEquals(stack.frameToIdx, view.frameToIdx);
        assertEquals(stack.firstFrame(), view.firstFrame());
        assertEquals(stack.lastFrame(), view.lastFrame());
    }

    @Test
    void load_acceptsAnyPrefixAndAnyZeroPaddingWidth(@TempDir Path dir) throws Exception {
        // Frame index is the integer each name *ends with* — arbitrary prefix, arbitrary
        // padding width. All three resolve to distinct frames (7, 42, 100) with no padding
        // width hard-coded: unpadded, 4-wide, and 8-wide all parse correctly.
        writeFloatTiff(new File(dir.toFile(), "frame7.tif"),                new float[][]{{7.0f}});
        writeFloatTiff(new File(dir.toFile(), "topoj_0042.tif"),           new float[][]{{42.0f}});
        writeFloatTiff(new File(dir.toFile(), "height_map_00000100.tif"),  new float[][]{{100.0f}});

        LoadedFloatStack stack = TopoJStackLoader.load(dir.toFile());

        assertEquals(3, stack.frameCount());
        assertEquals(7,   stack.firstFrame());
        assertEquals(100, stack.lastFrame());
        assertEquals(7.0f,   stack.pixels[stack.frameToIdx.get(7)][0][0],   0.0f);
        assertEquals(42.0f,  stack.pixels[stack.frameToIdx.get(42)][0][0],  0.0f);
        assertEquals(100.0f, stack.pixels[stack.frameToIdx.get(100)][0][0], 0.0f);
    }

    @Test
    void load_rejectsFileNotEndingWithInteger(@TempDir Path dir) throws Exception {
        // A trailing non-numeric suffix means the name does not *end* with a frame number.
        // Rather than silently mapping it to frame 0 (colliding with topoj_0000), it is rejected.
        writeFloatTiff(new File(dir.toFile(), "topoj_0000.tif"),        new float[][]{{1.0f}});
        writeFloatTiff(new File(dir.toFile(), "topoj_0007_final.tif"),  new float[][]{{2.0f}});

        assertThrows(Exception.class, () -> TopoJStackLoader.load(dir.toFile()));
    }

    @Test
    void load_rejects16BitTiff(@TempDir Path dir) throws Exception {
        ShortProcessor sp = new ShortProcessor(2, 2);
        sp.set(0, 0, 5);
        IJ.saveAsTiff(new ImagePlus("s", sp), new File(dir.toFile(), "topoj_0000.tif").getAbsolutePath());

        // 16-bit is a valid input for Tool 2 (indexed) but not for the direct-Z extractor.
        assertThrows(Exception.class, () -> TopoJStackLoader.load(dir.toFile()));
    }

    @Test
    void load_rejects8BitTiff(@TempDir Path dir) throws Exception {
        ByteProcessor bp = new ByteProcessor(2, 2);
        bp.set(0, 0, 5);
        IJ.saveAsTiff(new ImagePlus("b", bp), new File(dir.toFile(), "topoj_0000.tif").getAbsolutePath());

        assertThrows(Exception.class, () -> TopoJStackLoader.load(dir.toFile()));
    }
}
