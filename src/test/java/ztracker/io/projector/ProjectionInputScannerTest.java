package ztracker.io.projector;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProjectionInputScanner}, the only piece of the projection tool's I/O with
 * real logic: numeric z-layer sort (negatives + gaps), the union/dedup/sort of timepoint
 * filenames across layers, and — the subtle one — recording each present slice's <em>global</em>
 * z-index (position in the full sorted layer list) when a timepoint is absent from some layers.
 *
 * <p>TIFFs are written headlessly through ImageJ's real {@link FileSaver} and read back through
 * the scanner's own {@link ij.process.ImageProcessor#getf} path, so the pixel reads are genuine
 * rather than mocked (the same reason {@code ProjectionExporterTest} uses real TIFF I/O).
 */
class ProjectionInputScannerTest {

    // ── scanDataset: discovery, sort, filename union ──────────────────────────

    @Test
    void scanDataset_sortsLayersNumerically_withNegativesAndGaps_ignoringNonNumericFolders(
            @TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        // Deliberately created out of numeric order, with a gap (no -299) and non-numeric
        // sibling folders (a stray note dir and the tool's own output root) that must be ignored.
        writeShortTiff(layer(dataset, "0"),    "f1.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "-300"), "f1.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "12.5"), "f1.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "-10"),  "f1.tif", 2, 2, 0);
        assertTrue(new File(dataset, "max_z").mkdirs());  // non-numeric → not a z-layer
        assertTrue(new File(dataset, "notes").mkdirs());

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);

        assertEquals(Arrays.asList("-300", "-10", "0", "12.5"), scan.zLayerNames);
        assertArrayEquals(new double[]{-300.0, -10.0, 0.0, 12.5}, scan.zValues);
    }

    @Test
    void scanDataset_unionsTimepointFilenames_dedupedAndLexicographicallySorted(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // Filenames overlap across layers and are created out of order; the union must dedup
        // "0002.tif" and come back sorted.
        writeShortTiff(layer(dataset, "0"), "0002.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "0"), "0001.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "1"), "0003.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "1"), "0002.tif", 2, 2, 0);

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);

        assertEquals(Arrays.asList("0001.tif", "0002.tif", "0003.tif"), scan.timepointFilenames);
    }

    @Test
    void scanDataset_throwsWhenNoNumericLayerFolders(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        assertTrue(new File(dataset, "max_z").mkdirs());
        assertTrue(new File(dataset, "notes").mkdirs());

        assertThrows(IOException.class, () -> ProjectionInputScanner.scanDataset(dataset));
    }

    @Test
    void scanDataset_throwsWhenLayersExistButHoldNoTiffs(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        layer(dataset, "0");  // numeric z-layer folders exist...
        layer(dataset, "1");  // ...but contain no TIFFs

        assertThrows(IOException.class, () -> ProjectionInputScanner.scanDataset(dataset));
    }

    // ── loadTimepoint: pixel/dim/bit-depth reads + global-index remap ──────────

    @Test
    void loadTimepoint_readsPresentLayers_withCorrectPixelsDimsAndBitDepth(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // 3x2 (w x h) images; each layer filled with its own marker value.
        writeShortTiff(layer(dataset, "-10"), "f1.tif", 3, 2, 10);
        writeShortTiff(layer(dataset, "0"),   "f1.tif", 3, 2, 20);
        writeShortTiff(layer(dataset, "42"),  "f1.tif", 3, 2, 30);

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);
        ProjectionInputScanner.TimepointStack tp =
                ProjectionInputScanner.loadTimepoint(scan, "f1.tif");

        assertEquals(3, tp.width);
        assertEquals(2, tp.height);
        assertEquals(16, tp.sourceBitDepth);
        assertEquals(3, tp.slices.size());
        // All layers present → slices parallel to the full sorted layer list, indices 0,1,2.
        assertArrayEquals(new int[]{0, 1, 2}, tp.globalZIndex);
        assertSliceFilledWith(tp.slices.get(0), 3, 2, 10f);  // layer -10
        assertSliceFilledWith(tp.slices.get(1), 3, 2, 20f);  // layer 0
        assertSliceFilledWith(tp.slices.get(2), 3, 2, 30f);  // layer 42
    }

    @Test
    void loadTimepoint_absentFromSomeLayers_recordsGlobalZIndexNotSlicePosition(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // 3 layers, sorted indices: -10 -> 0, 0 -> 1, 42 -> 2.
        // "f1.tif" exists only in the outer two layers (-10 and 42), absent from the middle (0).
        writeShortTiff(layer(dataset, "-10"), "f1.tif", 2, 2, 10);
        writeShortTiff(layer(dataset, "42"),  "f1.tif", 2, 2, 30);
        // Middle layer exists as a z-layer (has some other timepoint) but not this filename.
        writeShortTiff(layer(dataset, "0"),   "f2.tif", 2, 2, 20);

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);
        ProjectionInputScanner.TimepointStack tp =
                ProjectionInputScanner.loadTimepoint(scan, "f1.tif");

        assertEquals(2, tp.slices.size());
        // The 2nd present slice must carry GLOBAL index 2 (layer 42), not slice position 1.
        assertArrayEquals(new int[]{0, 2}, tp.globalZIndex);
        assertSliceFilledWith(tp.slices.get(0), 2, 2, 10f);
        assertSliceFilledWith(tp.slices.get(1), 2, 2, 30f);
    }

    @Test
    void loadTimepoint_reads32BitFloatValuesExactly(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        writeFloatTiff(layer(dataset, "0"), "f1.tif", 2, 1, new float[]{1.5f, -2.25f});

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);
        ProjectionInputScanner.TimepointStack tp =
                ProjectionInputScanner.loadTimepoint(scan, "f1.tif");

        assertEquals(32, tp.sourceBitDepth);
        assertEquals(1.5f,  tp.slices.get(0)[0][0]);
        assertEquals(-2.25f, tp.slices.get(0)[0][1]);
    }

    @Test
    void loadTimepoint_throwsWhenNoLayerContainsTheTimepoint(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        writeShortTiff(layer(dataset, "0"), "f1.tif", 2, 2, 0);
        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);

        assertThrows(IOException.class,
                () -> ProjectionInputScanner.loadTimepoint(scan, "nope.tif"));
    }

    @Test
    void loadTimepoint_throwsOnInconsistentSliceDimensions(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        writeShortTiff(layer(dataset, "0"), "f1.tif", 2, 2, 0);
        writeShortTiff(layer(dataset, "1"), "f1.tif", 3, 3, 0);  // different size, same timepoint
        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);

        assertThrows(IOException.class,
                () -> ProjectionInputScanner.loadTimepoint(scan, "f1.tif"));
    }

    // ── isDataset + parseZ ────────────────────────────────────────────────────

    @Test
    void isDataset_trueOnlyWhenANumericLayerSubfolderIsPresent(@TempDir Path dir) throws Exception {
        File withLayer = new File(dir.toFile(), "withLayer");
        assertTrue(new File(withLayer, "-300").mkdirs());
        assertTrue(ProjectionInputScanner.isDataset(withLayer));

        File withoutLayer = new File(dir.toFile(), "withoutLayer");
        assertTrue(new File(withoutLayer, "max_z").mkdirs());
        assertFalse(ProjectionInputScanner.isDataset(withoutLayer));
    }

    @Test
    void parseZ_parsesNumericNamesAndRejectsNonNumeric() {
        assertEquals(-300.0, ProjectionInputScanner.parseZ("-300"));
        assertEquals(12.5, ProjectionInputScanner.parseZ(" 12.5 "));  // trimmed
        assertNull(ProjectionInputScanner.parseZ("max_z"));
        assertNull(ProjectionInputScanner.parseZ(""));
    }

    @Test
    void loadTimepoint_rejectsColourImage_ratherThanProjectingPackedRgbValues(@TempDir Path dir)
            throws Exception {
        // ColorProcessor.getf returns the packed ARGB int, so an RGB layer would project on
        // colour values and produce a confident but meaningless z-origin map. Fail instead.
        File dataset = new File(dir.toFile(), "ds");
        writeShortTiff(layer(dataset, "-300"), "t1.tif", 2, 2, 10);
        save(new ColorProcessor(2, 2), new File(layer(dataset, "-299"), "t1.tif"));

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);

        IOException e = assertThrows(IOException.class,
                () -> ProjectionInputScanner.loadTimepoint(scan, "t1.tif"));
        assertTrue(e.getMessage().contains("RGB"), "message should name the cause: " + e.getMessage());
        assertTrue(e.getMessage().contains("-299"),
                "message should name the offending layer: " + e.getMessage());
    }

    @Test
    void loadTimepoint_rejectsMixedBitDepthsAcrossLayers(@TempDir Path dir) throws Exception {
        // The projection compares these layers' raw intensities, and their ceilings differ
        // (255 vs 65535), so a 16-bit layer would win Max-Z on magnitude alone.
        File dataset = new File(dir.toFile(), "ds");
        writeShortTiff(layer(dataset, "-300"), "t1.tif", 2, 2, 10);   // 16-bit
        save(new ByteProcessor(2, 2), new File(layer(dataset, "-299"), "t1.tif")); // 8-bit

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);

        IOException e = assertThrows(IOException.class,
                () -> ProjectionInputScanner.loadTimepoint(scan, "t1.tif"));
        assertTrue(e.getMessage().contains("bit depth") || e.getMessage().contains("bit depths"),
                "message should name the cause: " + e.getMessage());
        assertTrue(e.getMessage().contains("-299"),
                "message should name the offending layer: " + e.getMessage());
        assertTrue(e.getMessage().contains("8-bit") && e.getMessage().contains("16-bit"),
                "message should give both depths: " + e.getMessage());
    }

    @Test
    void loadTimepoint_uniformBitDepthAcrossLayers_stillLoads(@TempDir Path dir) throws Exception {
        // Guard against over-eager rejection: same depth everywhere must remain fine.
        File dataset = new File(dir.toFile(), "ds");
        writeShortTiff(layer(dataset, "-300"), "t1.tif", 2, 2, 10);
        writeShortTiff(layer(dataset, "-299"), "t1.tif", 2, 2, 20);

        ProjectionInputScanner.DatasetScan scan = ProjectionInputScanner.scanDataset(dataset);
        ProjectionInputScanner.TimepointStack ts =
                ProjectionInputScanner.loadTimepoint(scan, "t1.tif");

        assertEquals(2, ts.slices.size());
        assertEquals(16, ts.sourceBitDepth);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static File layer(File dataset, String name) {
        File d = new File(dataset, name);
        if (!d.isDirectory() && !d.mkdirs()) {
            throw new IllegalStateException("Could not create layer dir: " + d);
        }
        return d;
    }

    private static void writeShortTiff(File layerDir, String name, int w, int h, int fill)
            throws IOException {
        short[] pix = new short[w * h];
        Arrays.fill(pix, (short) fill);
        save(new ShortProcessor(w, h, pix, null), new File(layerDir, name));
    }

    private static void writeFloatTiff(File layerDir, String name, int w, int h, float[] pix)
            throws IOException {
        save(new FloatProcessor(w, h, pix), new File(layerDir, name));
    }

    private static void save(ij.process.ImageProcessor ip, File dest) throws IOException {
        if (!new FileSaver(new ImagePlus(dest.getName(), ip)).saveAsTiff(dest.getAbsolutePath())) {
            throw new IOException("Failed to write test TIFF: " + dest.getAbsolutePath());
        }
    }

    private static void assertSliceFilledWith(float[][] slice, int w, int h, float expected) {
        assertEquals(h, slice.length);
        for (int y = 0; y < h; y++) {
            assertEquals(w, slice[y].length);
            for (int x = 0; x < w; x++) {
                assertEquals(expected, slice[y][x], "pixel mismatch at (" + x + "," + y + ")");
            }
        }
    }
}
