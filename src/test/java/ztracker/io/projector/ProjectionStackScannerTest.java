package ztracker.io.projector;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.export.projector.ProjectionExporter;
import ztracker.io.extractor.TiffStackLoader;
import ztracker.io.extractor.ZMappingLoader;
import ztracker.project.ZProjector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProjectionStackScanner} — the TIFF-stack input type, where one multi-slice
 * file is one timepoint and the Z depths come from the ImageJ slice labels.
 *
 * <p>Stacks are written headlessly through ImageJ's real {@link FileSaver} and read back
 * through the scanner's own virtual-stack path, so the slice labels, pixel values, and bit
 * depths are genuine round-trips rather than mocks (same reasoning as
 * {@code ProjectionInputScannerTest} and {@code ProjectionExporterTest}).
 *
 * <p>The last test is a <b>seam test</b>: it runs a stack dataset all the way through
 * projection and export, then reads the results back with the <em>extractor's</em> own
 * loaders — proving this new input type produces output Tool 2 can consume, exactly as the
 * folder layout does.
 */
class ProjectionStackScannerTest {

    // ── Z from slice labels ───────────────────────────────────────────────────

    @Test
    void scanDataset_readsZFromSliceLabels_andSortsThemAscending(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        // Slices deliberately stored in DESCENDING Z order in the file.
        writeShortStack(dataset, "00001.tif", 2, 1,
                new double[]{400, 0, -400}, new int[]{1, 2, 3});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        assertArrayEquals(new double[]{-400, 0, 400}, scan.zValues());
        assertEquals(Arrays.asList("-400", "0", "400"), scan.zLayerNames());
        assertEquals(dataset, scan.datasetDir());
    }

    @Test
    void scanDataset_acceptsRealFijiLabelFormat_andFractionalNegativeZ(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // "z = -400.000" is exactly what Fiji writes for a Z stack.
        writeStackWithLabels(dataset, "00001.tif", 2, 1,
                new String[]{"z = -400.000", "z = -397.500", "z = -395.000"}, new int[]{1, 2, 3});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        assertArrayEquals(new double[]{-400.0, -397.5, -395.0}, scan.zValues());
        assertEquals(Arrays.asList("-400", "-397.5", "-395"), scan.zLayerNames());
    }

    @Test
    void parseZLabel_acceptsLabelledAndBareNumbers_rejectsArbitraryText() {
        assertEquals(-400.0, ProjectionStackScanner.parseZLabel("z = -400.000"));
        assertEquals(12.5,   ProjectionStackScanner.parseZLabel("Z: 12.5"));
        assertEquals(-3.0,   ProjectionStackScanner.parseZLabel("slice 4\nz = -3\nExposure=20ms"));
        assertEquals(-400.0, ProjectionStackScanner.parseZLabel(" -400.000 "));  // bare number
        assertEquals(1.5e-3, ProjectionStackScanner.parseZLabel("z = 1.5e-3"));

        // A filename-ish label must NOT be mined for a stray number — a confidently wrong
        // depth is worse than a clear error.
        assertNull(ProjectionStackScanner.parseZLabel("00001-0003.tif"));
        assertNull(ProjectionStackScanner.parseZLabel("Camera 2 exposure 20"));
        assertNull(ProjectionStackScanner.parseZLabel(""));
        assertNull(ProjectionStackScanner.parseZLabel(null));
    }

    @Test
    void scanDataset_throwsWhenASliceLabelHasNoZ(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        writeStackWithLabels(dataset, "00001.tif", 2, 1,
                new String[]{"z = -1", "some other label"}, new int[]{1, 2});

        IOException e = assertThrows(IOException.class,
                () -> ProjectionStackScanner.scanDataset(dataset));
        assertTrue(e.getMessage().contains("slice label") || e.getMessage().contains("Slice 2"),
                "message should point at the offending slice: " + e.getMessage());
    }

    @Test
    void scanDataset_throwsOnDuplicateZValues(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{0, 5, 5}, new int[]{1, 2, 3});

        assertThrows(IOException.class, () -> ProjectionStackScanner.scanDataset(dataset));
    }

    @Test
    void scanDataset_throwsOnSingleSliceFile_pointingAtTheOtherInputType(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{0}, new int[]{1});

        IOException e = assertThrows(IOException.class,
                () -> ProjectionStackScanner.scanDataset(dataset));
        assertTrue(e.getMessage().contains("Z-layer sub-folders"),
                "message should suggest the other input type: " + e.getMessage());
    }

    @Test
    void scanDataset_throwsWhenFolderHoldsNoTiffs(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> ProjectionStackScanner.scanDataset(dir.toFile()));
    }

    @Test
    void projectTimepoint_rejectsColourSlices_ratherThanProjectingPackedRgbValues(@TempDir Path dir)
            throws Exception {
        // ColorProcessor.getf returns the packed ARGB int, so an RGB stack would project on
        // colour values and produce a confident but meaningless z-origin map. Fail instead.
        File dataset = dir.toFile();
        writeColourStack(dataset, "00001.tif", 2, 2, new double[]{-400, 0, 400});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        IOException e = assertThrows(IOException.class,
                () -> scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00001.tif"));
        assertTrue(e.getMessage().contains("RGB"), "message should name the cause: " + e.getMessage());
        assertTrue(e.getMessage().contains("00001.tif"),
                "message should name the offending file: " + e.getMessage());
    }

    // ── Timepoint discovery / ordering ────────────────────────────────────────

    @Test
    void scanDataset_ordersTimepointsByTrailingInteger_whateverThePadding(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // Created out of order; "00010" must follow "00002" (numeric, not lexicographic),
        // and an unpadded name sorts by its number too.
        for (String name : new String[]{"00010.tif", "00002.tif", "7.tif", "00001.tif"}) {
            writeShortStack(dataset, name, 2, 1, new double[]{0, 1}, new int[]{1, 2});
        }
        new File(dataset, "notes.txt").createNewFile(); // non-TIFF sibling is ignored

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        assertEquals(Arrays.asList("00001.tif", "00002.tif", "7.tif", "00010.tif"),
                scan.timepointLabels());
    }

    @Test
    void isDataset_trueForFolderHoldingTiffs_falseForFolderLayoutOrEmptyFolder(@TempDir Path dir)
            throws Exception {
        File stacks = new File(dir.toFile(), "stacks");
        assertTrue(stacks.mkdirs());
        writeShortStack(stacks, "00001.tif", 2, 1, new double[]{0, 1}, new int[]{1, 2});
        assertTrue(ProjectionStackScanner.isDataset(stacks));

        // A folder-layout dataset holds only sub-folders — not a stack dataset.
        File folderLayout = new File(dir.toFile(), "folderLayout");
        File layer = new File(folderLayout, "-300");
        assertTrue(layer.mkdirs());
        writeShortStack(layer, "f1.tif", 2, 1, new double[]{0, 1}, new int[]{1, 2});
        assertFalse(ProjectionStackScanner.isDataset(folderLayout));

        File empty = new File(dir.toFile(), "empty");
        assertTrue(empty.mkdirs());
        assertFalse(ProjectionStackScanner.isDataset(empty));
    }

    // ── projectTimepoint ──────────────────────────────────────────────────────

    @Test
    void projectTimepoint_maxAndMin_useGlobalAscendingZIndices_regardlessOfSliceOrderInFile(
            @TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        // File order is Z = 400, 0, -400 (descending) with fills 30, 10, 20.
        // Ascending Z → index 0 = -400 (fill 20), 1 = 0 (fill 10), 2 = 400 (fill 30).
        writeShortStack(dataset, "00001.tif", 2, 1,
                new double[]{400, 0, -400}, new int[]{30, 10, 20});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        ProjectionSource.Projected max =
                scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00001.tif");
        assertArrayEquals(new float[]{30f, 30f}, max.result.projection[0]);
        assertArrayEquals(new int[]{2, 2}, max.result.zOriginIndex[0]);   // Z = 400
        assertEquals(16, max.sourceBitDepth);

        ProjectionSource.Projected min =
                scan.projectTimepoint(ZProjector.Mode.MIN_Z, "00001.tif");
        assertArrayEquals(new float[]{10f, 10f}, min.result.projection[0]);
        assertArrayEquals(new int[]{1, 1}, min.result.zOriginIndex[0]);   // Z = 0
    }

    @Test
    void projectTimepoint_tieGoesToTheLowestZ_matchingTheFolderLayout(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // Same value everywhere, slices stored highest-Z-first: the winner must still be the
        // LOWEST Z (index 0), because slices are folded in ascending-Z order.
        writeShortStack(dataset, "00001.tif", 2, 1,
                new double[]{10, 5, 0}, new int[]{7, 7, 7});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        for (ZProjector.Mode mode : ZProjector.Mode.values()) {
            ProjectionSource.Projected p = scan.projectTimepoint(mode, "00001.tif");
            assertArrayEquals(new int[]{0, 0}, p.result.zOriginIndex[0], mode.toString());
        }
    }

    @Test
    void projectTimepoint_timepointCoveringOnlySomeLayers_keepsGlobalZIndices(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // Timepoint 1 defines the dataset's three layers: -10, 0, 42.
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{-10, 0, 42}, new int[]{1, 2, 3});
        // Timepoint 2 has only the outer two — like a timepoint absent from the middle folder.
        writeShortStack(dataset, "00002.tif", 2, 1, new double[]{-10, 42}, new int[]{5, 9});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);
        ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00002.tif");

        assertArrayEquals(new float[]{9f, 9f}, p.result.projection[0]);
        // Global index 2 (layer 42), NOT slice position 1.
        assertArrayEquals(new int[]{2, 2}, p.result.zOriginIndex[0]);
    }

    @Test
    void projectTimepoint_throwsWhenASliceZIsNotOneOfTheDatasetsLayers(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{0, 1}, new int[]{1, 2});
        writeShortStack(dataset, "00002.tif", 2, 1, new double[]{0, 99}, new int[]{1, 2});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        // The first timepoint is fine; the second names a depth the mapping has no key for.
        scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00001.tif");
        IOException e = assertThrows(IOException.class,
                () -> scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00002.tif"));
        assertTrue(e.getMessage().contains("99"), e.getMessage());
    }

    @Test
    void projectTimepoint_throwsWhenTheFileIsMissing(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{0, 1}, new int[]{1, 2});
        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        assertThrows(IOException.class,
                () -> scan.projectTimepoint(ZProjector.Mode.MAX_Z, "nope.tif"));
    }

    // ── Pixel reads: bit depths and unsigned values ───────────────────────────

    @Test
    void projectTimepoint_reads8BitValuesUnsigned_notSignExtended(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // 200 > 127: read as a signed byte this would come back as -56.
        ImageStack st = new ImageStack(2, 1);
        st.addSlice("z = 0", new ByteProcessor(2, 1, new byte[]{(byte) 200, (byte) 200}, null));
        st.addSlice("z = 1", new ByteProcessor(2, 1, new byte[]{5, 5}, null));
        save(new ImagePlus("b", st), new File(dataset, "00001.tif"));

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);
        ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00001.tif");

        assertEquals(8, p.sourceBitDepth);
        assertArrayEquals(new float[]{200f, 200f}, p.result.projection[0]);
        assertArrayEquals(new int[]{0, 0}, p.result.zOriginIndex[0]);
    }

    @Test
    void projectTimepoint_reads16BitValuesUnsigned_notSignExtended(@TempDir Path dir)
            throws Exception {
        File dataset = dir.toFile();
        // 40000 > 32767: read as a signed short this would come back negative.
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{0, 1}, new int[]{40000, 5});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);
        ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00001.tif");

        assertEquals(16, p.sourceBitDepth);
        assertArrayEquals(new float[]{40000f, 40000f}, p.result.projection[0]);
    }

    @Test
    void projectTimepoint_reads32BitFloatValuesExactly(@TempDir Path dir) throws Exception {
        File dataset = dir.toFile();
        ImageStack st = new ImageStack(2, 1);
        st.addSlice("z = 0", new FloatProcessor(2, 1, new float[]{1.5f, -2.25f}));
        st.addSlice("z = 1", new FloatProcessor(2, 1, new float[]{-9.75f, -9.75f}));
        save(new ImagePlus("f", st), new File(dataset, "00001.tif"));

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);
        ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MIN_Z, "00001.tif");

        assertEquals(32, p.sourceBitDepth);
        assertArrayEquals(new float[]{-9.75f, -9.75f}, p.result.projection[0]);
        assertArrayEquals(new int[]{1, 1}, p.result.zOriginIndex[0]);
    }

    // ── Virtual vs in-memory reads ────────────────────────────────────────────

    @Test
    void virtualAndInMemoryReads_agreeOnLabelsAndPixels(@TempDir Path dir) throws Exception {
        // A plain uncompressed multi-slice TIFF is read on demand (that's what keeps a
        // 700 MB timepoint out of memory); anything else falls back to a normal open. Both
        // routes must see the same labels and pixels.
        File dataset = dir.toFile();
        writeShortStack(dataset, "00001.tif", 2, 1, new double[]{-1, 2.5}, new int[]{11, 22});
        File file = new File(dataset, "00001.tif");

        ImageStack virtual = ProjectionStackScanner.tryOpenVirtual(file);
        assertNotNull(virtual, "an uncompressed multi-slice TIFF should open virtually");

        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        assertNotNull(imp);
        ImageStack inMemory = imp.getStack();

        assertArrayEquals(ProjectionStackScanner.readSliceZValues(virtual, "00001.tif"),
                ProjectionStackScanner.readSliceZValues(inMemory, "00001.tif"));
        for (int s = 1; s <= 2; s++) {
            float[][] a = new float[1][2];
            float[][] b = new float[1][2];
            ProjectionStackScanner.readInto(virtual.getProcessor(s), a);
            ProjectionStackScanner.readInto(inMemory.getProcessor(s), b);
            assertArrayEquals(a[0], b[0], "slice " + s);
        }
        imp.close();
    }

    @Test
    void tryOpenVirtual_returnsNullForSingleImageTiff_soTheNormalOpenHandlesIt(@TempDir Path dir)
            throws Exception {
        File single = new File(dir.toFile(), "single.tif");
        save(new ImagePlus("single", new ShortProcessor(2, 1, new short[]{1, 2}, null)), single);

        assertNull(ProjectionStackScanner.tryOpenVirtual(single));
    }

    // ── Seam test: stack input → export → the extractor's own loaders ─────────

    @Test
    void seam_stackInputProjectsAndExportsIntoSomethingTheExtractorCanRead(@TempDir Path dir)
            throws Exception {
        File dataset = new File(dir.toFile(), "movie");
        assertTrue(dataset.mkdirs());
        // Two timepoints, three Z layers each (-400, 0, 400 µm), brightest layer differs per
        // pixel and per timepoint.
        writeShortStackPerPixel(dataset, "00001.tif", new double[]{-400, 0, 400},
                new int[][]{{5, 90}, {50, 10}, {30, 20}});
        writeShortStackPerPixel(dataset, "00002.tif", new double[]{-400, 0, 400},
                new int[][]{{5, 10}, {50, 20}, {30, 90}});

        ProjectionStackScanner.StackScan scan = ProjectionStackScanner.scanDataset(dataset);

        File outDir = new File(dir.toFile(), "out");
        File z16Dir = new File(outDir, "z_origin");
        assertTrue(z16Dir.mkdirs());
        ProjectionExporter.writeMappings(outDir, scan.zValues(), true, false);
        for (String label : scan.timepointLabels()) {
            ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MAX_Z, label);
            assertTrue(ProjectionExporter.write16BitOrigin(z16Dir, label, p.result.zOriginIndex));
        }

        // Read it back the way Tool 2 does.
        Map<Integer, Double> mapping =
                ZMappingLoader.load(new File(outDir, "z_layer_mapping.json").toPath());
        assertEquals(3, mapping.size());
        assertEquals(-400.0, mapping.get(0));
        assertEquals(0.0, mapping.get(1));
        assertEquals(400.0, mapping.get(2));

        TiffStackLoader.LoadedStack stack = TiffStackLoader.load(z16Dir);
        // Filenames are z_origin_00001.tif / z_origin_00002.tif → frames 1 and 2.
        assertEquals(Arrays.asList(1, 2), stack.frameNumbers);

        // Timepoint 1: pixel (0,0) is brightest at Z=0 (index 1), pixel (1,0) at Z=-400 (index 0).
        int[][] f1 = stack.pixels[stack.frameToIdx.get(1)];
        assertEquals(1, f1[0][0]);
        assertEquals(0, f1[0][1]);
        assertEquals(0.0,    mapping.get(f1[0][0]));
        assertEquals(-400.0, mapping.get(f1[0][1]));

        // Timepoint 2: pixel (1,0) is now brightest at Z=400 (index 2).
        int[][] f2 = stack.pixels[stack.frameToIdx.get(2)];
        assertEquals(1, f2[0][0]);
        assertEquals(2, f2[0][1]);
        assertEquals(400.0, mapping.get(f2[0][1]));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes a 16-bit stack whose slice {@code i} is filled with {@code fills[i]} at Z {@code z[i]}. */
    private static void writeColourStack(File dir, String name, int w, int h, double[] z)
            throws IOException {
        ImageStack st = new ImageStack(w, h);
        for (double zi : z) st.addSlice("z = " + zi, new ColorProcessor(w, h));
        save(new ImagePlus(name, st), new File(dir, name));
    }

    private static void writeShortStack(File dir, String name, int w, int h,
                                        double[] z, int[] fills) throws IOException {
        String[] labels = new String[z.length];
        for (int i = 0; i < z.length; i++) labels[i] = "z = " + z[i];
        writeStackWithLabels(dir, name, w, h, labels, fills);
    }

    private static void writeStackWithLabels(File dir, String name, int w, int h,
                                             String[] labels, int[] fills) throws IOException {
        ImageStack st = new ImageStack(w, h);
        for (int i = 0; i < labels.length; i++) {
            short[] px = new short[w * h];
            Arrays.fill(px, (short) fills[i]);
            st.addSlice(labels[i], new ShortProcessor(w, h, px, null));
        }
        save(new ImagePlus(name, st), new File(dir, name));
    }

    /**
     * Writes a 1-row 16-bit stack where {@code perSlice[i]} holds slice {@code i}'s pixel row —
     * so different pixels can win at different Z layers.
     */
    private static void writeShortStackPerPixel(File dir, String name, double[] z, int[][] perSlice)
            throws IOException {
        int w = perSlice[0].length;
        ImageStack st = new ImageStack(w, 1);
        for (int i = 0; i < z.length; i++) {
            short[] px = new short[w];
            for (int x = 0; x < w; x++) px[x] = (short) perSlice[i][x];
            st.addSlice("z = " + z[i], new ShortProcessor(w, 1, px, null));
        }
        save(new ImagePlus(name, st), new File(dir, name));
    }

    private static void save(ImagePlus imp, File dest) throws IOException {
        if (!new FileSaver(imp).saveAsTiff(dest.getAbsolutePath())) {
            throw new IOException("Failed to write test TIFF: " + dest.getAbsolutePath());
        }
    }
}
