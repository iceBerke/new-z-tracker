package ztracker.io.projector;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.project.ZProjector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectionStackScanner} against a <b>real, Fiji-produced TIFF stack</b> — the
 * complement to {@code ProjectionStackScannerTest}, which builds every fixture itself.
 *
 * <p><b>Why both suites exist.</b> The synthetic suite writes its stacks through ImageJ's
 * {@code FileSaver} and reads them back, which proves round-trip fidelity but is inherently
 * a little circular: it only ever parses labels this project itself wrote. This suite closes
 * that gap. The fixture is a byte-for-byte crop of an actual acquisition timepoint — the same
 * 1051×1674 dataset behind the streaming/memory notes in {@code CLAUDE.md} — carrying the
 * label text, byte order, and metadata layout the microscope's export pipeline really
 * produces. Neither suite replaces the other: the synthetic one owns the error paths and
 * edge cases a single real file cannot cover (duplicate Z, missing labels, partial layers,
 * 16-/32-bit reads), this one owns "does it work on the genuine article".
 *
 * <p><b>The fixture</b> ({@code reference_stack_crop.tif}, 11,385 bytes): 3 slices of a
 * 53×68 crop, uncompressed big-endian 8-bit unsigned, single strip, written by ImageJ 1.54p
 * with an {@code IJMetadata} tag on the first IFD only. Its slice labels are
 * {@code "z = -2.000"}, {@code "z = 0.000"}, {@code "z = 2.000"}, already in ascending-Z file
 * order. It must stay untouched — never re-save or convert it, as its authenticity is the
 * entire point. {@link #FIXTURE_BYTES} is asserted at setup so an accidental re-encode fails
 * loudly rather than silently becoming a second synthetic fixture.
 *
 * <p>All expected numbers below were computed directly from the fixture's pixels.
 */
class ProjectionStackScannerRealDataTest {

    /** Sits next to this class on the test classpath, under {@code src/test/resources}. */
    private static final String RESOURCE = "reference_stack_crop.tif";
    /** Size of the untouched original — a guard against the fixture being re-saved. */
    private static final long FIXTURE_BYTES = 11385L;

    /**
     * The fixture is copied in under this name because timepoint ordering keys off the
     * integer a filename ends with, and the fixture's own name has none.
     */
    private static final String TIMEPOINT = "00001.tif";

    private static final int WIDTH  = 53;
    private static final int HEIGHT = 68;
    private static final int SLICES = 3;

    @TempDir
    static Path datasetDir;

    private static ProjectionStackScanner.StackScan scan;
    /** The fixture's raw slices in file order, which is also ascending-Z (asserted at setup). */
    private static float[][][] slices;

    @BeforeAll
    static void copyFixtureAndScan() throws Exception {
        URL resource = ProjectionStackScannerRealDataTest.class.getResource(RESOURCE);
        assertNotNull(resource, "Missing test fixture on the classpath: "
                + "src/test/resources/ztracker/io/projector/" + RESOURCE);

        File file = new File(datasetDir.toFile(), TIMEPOINT);
        try (InputStream in = resource.openStream()) {
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        assertEquals(FIXTURE_BYTES, file.length(),
                "The fixture must be the untouched original acquisition crop — do not re-save it.");

        scan   = ProjectionStackScanner.scanDataset(datasetDir.toFile());
        slices = readSlicesInFileOrder(file);
    }

    // ── 1. Real slice labels ──────────────────────────────────────────────────

    @Test
    void scanDataset_readsTheZValuesFromTheRealFijiSliceLabels() {
        assertArrayEquals(new double[]{-2.0, 0.0, 2.0}, scan.zValues());
        assertEquals(Arrays.asList("-2", "0", "2"), scan.zLayerNames());
        assertEquals(Collections.singletonList(TIMEPOINT), scan.timepointLabels());
    }

    // ── 2 & 3. Whole-frame projection over real pixels ────────────────────────

    @Test
    void projectTimepoint_maxZ_overRealPixels() throws Exception {
        ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MAX_Z, TIMEPOINT);

        assertEquals(8, p.sourceBitDepth);
        assertEquals(101540.0, sum(p.result.projection), 1e-6);
        assertArrayEquals(new int[]{1766, 494, 1344}, zOriginHistogram(p.result.zOriginIndex));
    }

    @Test
    void projectTimepoint_minZ_overRealPixels() throws Exception {
        ProjectionSource.Projected p = scan.projectTimepoint(ZProjector.Mode.MIN_Z, TIMEPOINT);

        assertEquals(8, p.sourceBitDepth);
        assertEquals(59652.0, sum(p.result.projection), 1e-6);
        assertArrayEquals(new int[]{1361, 858, 1385}, zOriginHistogram(p.result.zOriginIndex));
    }

    // ── 4. Named pixels, traced from their slices to both projections ─────────

    @Test
    void namedRealPixels_projectToTheExpectedValueAndZLayer() throws Exception {
        ZProjector.Result max = scan.projectTimepoint(ZProjector.Mode.MAX_Z, TIMEPOINT).result;
        ZProjector.Result min = scan.projectTimepoint(ZProjector.Mode.MIN_Z, TIMEPOINT).result;

        assertSlicePixels(0, 0, 14, 4, 11);
        assertProjected(max, 0, 0, 14f, 0);
        assertProjected(min, 0, 0, 4f, 1);

        assertSlicePixels(26, 34, 233, 222, 197);
        assertProjected(max, 26, 34, 233f, 0);
        assertProjected(min, 26, 34, 197f, 2);

        // Bottom-right corner — also confirms the crop's full extent is read.
        assertSlicePixels(52, 67, 15, 18, 19);
        assertProjected(max, 52, 67, 19f, 2);
        assertProjected(min, 52, 67, 15f, 0);
    }

    // ── 5. Unsigned 8-bit reads on real data ──────────────────────────────────

    @Test
    void realBrightPixels_areReadUnsigned_notSignExtended() throws Exception {
        // 233 > 127: read as a signed Java byte this would arrive as -23, and the whole
        // projection would invert wherever the sample is bright.
        assertEquals(233f, slices[0][34][26], "readInto must mask the backing byte[] with 0xff");

        ZProjector.Result max = scan.projectTimepoint(ZProjector.Mode.MAX_Z, TIMEPOINT).result;
        assertEquals(233f, max.projection[34][26]);

        // Nothing anywhere may go negative — 8-bit intensities are 0..255 by definition.
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                float v = max.projection[y][x];
                assertTrue(v >= 0f && v <= 255f, "out-of-range intensity at (" + x + "," + y + "): " + v);
            }
        }
    }

    // ── 6. Tie-breaking on real data ──────────────────────────────────────────

    @Test
    void tiedRealPixels_resolveToTheLowestZ() throws Exception {
        // 8-bit acquisitions saturate and repeat values, so ties are ordinary here rather
        // than a corner case: this 3604-pixel crop alone has hundreds of them. Slices are
        // folded in ascending-Z order and compared strictly, so the first (lowest-Z) layer
        // holding the extreme value keeps it — numpy's argmax first-occurrence rule, which
        // the original Python scripts relied on.
        ZProjector.Result max = scan.projectTimepoint(ZProjector.Mode.MAX_Z, TIMEPOINT).result;

        // Adjacent tie: layers 0 and 1 both hold 15 — layer 0 wins.
        assertSlicePixels(10, 0, 15, 15, 12);
        assertProjected(max, 10, 0, 15f, 0);

        // Non-adjacent tie: layers 0 and 2 both hold 15, with a lower value between them —
        // the winner must still be layer 0, not the last one seen.
        assertSlicePixels(48, 0, 15, 14, 15);
        assertProjected(max, 48, 0, 15f, 0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Asserts the fixture's raw per-slice values at one position, ordered by ascending Z. */
    private static void assertSlicePixels(int x, int y, int... expectedPerSlice) {
        assertEquals(SLICES, expectedPerSlice.length);
        for (int k = 0; k < expectedPerSlice.length; k++) {
            assertEquals((float) expectedPerSlice[k], slices[k][y][x],
                    "slice " + k + " at (" + x + "," + y + ")");
        }
    }

    private static void assertProjected(ZProjector.Result result, int x, int y,
                                        float expectedValue, int expectedZIndex) {
        String at = " at (" + x + "," + y + ")";
        assertEquals(expectedValue, result.projection[y][x], "projected value" + at);
        assertEquals(expectedZIndex, result.zOriginIndex[y][x], "z-origin index" + at);
    }

    private static double sum(float[][] image) {
        double total = 0;
        for (float[] row : image) {
            for (float v : row) total += v;
        }
        return total;
    }

    /** Counts how many pixels each of the three z-layers won. */
    private static int[] zOriginHistogram(int[][] zOriginIndex) {
        int[] counts = new int[SLICES];
        for (int y = 0; y < zOriginIndex.length; y++) {
            for (int x = 0; x < zOriginIndex[y].length; x++) {
                int idx = zOriginIndex[y][x];
                assertTrue(idx >= 0 && idx < SLICES,
                        "z-origin index out of range at (" + x + "," + y + "): " + idx);
                counts[idx]++;
            }
        }
        return counts;
    }

    /**
     * Reads the fixture's slices with the scanner's own {@link ProjectionStackScanner#readInto},
     * so the expected per-slice values in the tests above are the very pixels the projection
     * path sees. Also asserts the file's slice order is ascending Z, which is what lets slice
     * {@code k} stand for global z-index {@code k} throughout this class.
     */
    private static float[][][] readSlicesInFileOrder(File file) throws IOException {
        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        assertNotNull(imp, "Could not open the fixture: " + file);
        try {
            ImageStack stack = imp.getStack();
            assertEquals(SLICES, stack.getSize());
            assertEquals(WIDTH, stack.getWidth());
            assertEquals(HEIGHT, stack.getHeight());

            double[] z = ProjectionStackScanner.readSliceZValues(stack, file.getName());
            for (int i = 1; i < z.length; i++) {
                assertTrue(z[i] > z[i - 1],
                        "fixture slices are expected in ascending-Z file order: " + Arrays.toString(z));
            }

            float[][][] out = new float[SLICES][][];
            for (int s = 1; s <= SLICES; s++) {
                float[][] buffer = new float[HEIGHT][WIDTH];
                ProjectionStackScanner.readInto(stack.getProcessor(s), buffer);
                out[s - 1] = buffer;
            }
            return out;
        } finally {
            imp.close();
        }
    }
}
