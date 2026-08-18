package ztracker.io.projector;

import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.project.ZProjector;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The <b>streaming invariant</b> for the TIFF-stack input type: {@code projectTimepoint} must
 * keep reading one slice at a time into {@link ZProjector.Accumulator}, never assembling the
 * timepoint as a {@code List<float[][]>} first.
 *
 * <h2>Why this test exists at all</h2>
 * CLAUDE.md records "must stay streaming — do not simplify it into load-all-then-project" as a
 * design decision, but until this class <b>nothing asserted it</b>. A refactor into
 * load-all-then-project produces byte-identical numbers, so every other test in the suite —
 * including {@code ZProjectorAccumulatorTest}, which pins the accumulator's own contract but
 * never observes the caller — would keep passing. It would only fail on a real dataset on a
 * real machine, which is exactly the failure the design decision exists to prevent.
 *
 * <h2>How it asserts a memory property</h2>
 * By running inside a heap too small for the forbidden design. This class is <b>not</b> part of
 * the main surefire execution: pom.xml gives it its own {@code streaming-invariant-test}
 * execution forked with {@code -Xmx96m}. Against the fixture below:
 * <ul>
 *   <li><b>load-all</b> = 1024·1024·100·4 B = <b>400 MiB</b> — 4.17× the cap, so the forbidden
 *       design cannot complete (measured: it dies after 22 of the 100 slices);</li>
 *   <li><b>streaming</b> = 1024·1024·13 B = <b>13 MiB</b> — one reused float buffer plus the
 *       accumulator's float projection and int z-origin, plus the source slice's own
 *       {@code ImageProcessor} live alongside them (hence 13 and not 12, for this 8-bit
 *       fixture) — 13.5% of the cap.</li>
 * </ul>
 * The assertion is <b>completion with a correct result</b>, never an expected
 * {@code OutOfMemoryError}: a JVM can thrash for a long time before throwing, which would make
 * this slow and flaky. Passing under the cap is the proof, because only the streaming design
 * can pass under it.
 *
 * <h2>Resource cost</h2>
 * The fixture is ~100 MiB on disk (see {@link #FIXTURE_BYTES}) — by far the largest temporary
 * file the suite produces — written to a {@code @TempDir} and removed by JUnit's own teardown
 * (confirmed by measurement at p10.14: peak 104,873,979 B, nothing left behind). A constrained
 * CI tmpfs is therefore a plausible way for this to fail confusingly. Whole test runs in ~1.3 s.
 * The fixture is generated at test time on purpose: the repo has exactly one committed binary
 * fixture ({@code reference_stack_crop.tif}) and that is deliberate.
 *
 * <h2>What this test does not cover</h2>
 * The fixture's slice labels <b>ascend with file order</b>, so a layer's position in the file and
 * its global z-index coincide. This test therefore does <b>not</b> exercise
 * {@code projectTimepoint}'s ascending-Z sort — {@code ProjectionStackScannerTest} covers that
 * (with slices deliberately stored out of order), and nothing here would catch a regression in
 * it. The scope here is the memory shape, plus enough correctness to prove the run really
 * projected rather than merely completed.
 */
class ProjectionStackScannerMemoryTest {

    // ── Fixture geometry ──────────────────────────────────────────────────────

    private static final int WIDTH  = 1024;
    private static final int HEIGHT = 1024;
    private static final int SLICES = 100;

    /** Exact pixel-data size of the uncompressed 8-bit stack; the file adds a small header. */
    private static final long FIXTURE_BYTES = (long) WIDTH * HEIGHT * SLICES;

    /**
     * One pixel whose extremum is planted rather than derived from {@link #pixelValue}, so a
     * projection that merely re-derived the formula could not satisfy this test by accident.
     * 255 beats every formula value (which top out at {@link #SLICES} = 100).
     */
    private static final int MARKER_X = 500, MARKER_Y = 600, MARKER_LAYER = 7, MARKER_VALUE = 255;

    /** Physical Z of layer {@code k} (µm): −100, −98, … 98 — ascending, so layer k = index k. */
    private static double layerZ(int k) {
        return (k - SLICES / 2) * 2.0;
    }

    /**
     * The fixture's closed-form pixel rule.
     *
     * <p>{@code value(x, y, k) = ((x + y + k) mod SLICES) + 1}. As {@code k} runs over
     * {@code 0 … SLICES-1} — every layer — the modulus runs over {@code 0 … SLICES-1} too, so
     * a pixel's values across the stack are a <b>permutation of 1 … SLICES</b>. That gives two
     * things this test needs: the max is always exactly {@code SLICES}, and it is attained by
     * <b>exactly one</b> layer, so there is no tie and the expected z-origin index is
     * unambiguous. Solving {@code (x + y + k) ≡ SLICES-1} gives {@link #winningLayer}.
     *
     * <p>The rule also varies the winner across the frame (it depends on {@code x + y}), so a
     * z-origin map that was uniform, transposed, or off by a slice fails immediately.
     */
    private static int pixelValue(int x, int y, int k) {
        if (x == MARKER_X && y == MARKER_Y && k == MARKER_LAYER) return MARKER_VALUE;
        return ((x + y + k) % SLICES) + 1;
    }

    /** The layer holding pixel {@code (x, y)}'s maximum — the inverse of {@link #pixelValue}. */
    private static int winningLayer(int x, int y) {
        if (x == MARKER_X && y == MARKER_Y) return MARKER_LAYER;
        return (SLICES - 1 - (x + y) % SLICES) % SLICES;
    }

    // ── The test ──────────────────────────────────────────────────────────────

    @Test
    void stackInput_projects400MiBOfSlicesCorrectlyInside96MiBOfHeap_soItMustBeStreaming(
            @TempDir Path dir) throws Exception {

        File tif = writeGeneratedStack(dir.resolve("00001.tif").toFile());

        assertTrue(tif.length() >= FIXTURE_BYTES,
                "the fixture must be the uncompressed " + FIXTURE_BYTES + "-byte stack the "
                + "sizing argument assumes, but it is only " + tif.length() + " bytes — a "
                + "compressed or truncated file would make the load-all margin meaningless");

        // If this fell back to IJ.openImage the whole timepoint would be read into memory by the
        // opener itself, so the streamed path — the one under test — would never run.
        ImageStack virtual = ProjectionStackScanner.tryOpenVirtual(tif);
        assertNotNull(virtual, "the generated stack must open as a FileInfoVirtualStack; if it "
                + "falls back to IJ.openImage this test proves nothing about streaming");
        assertEquals(SLICES, virtual.getSize());

        ProjectionSource scan = ProjectionStackScanner.scanDataset(dir.toFile());
        assertEquals(SLICES, scan.zValues().length);
        assertEquals(layerZ(0), scan.zValues()[0], 1e-9);
        assertEquals(layerZ(SLICES - 1), scan.zValues()[SLICES - 1], 1e-9);

        ProjectionSource.Projected projected =
                scan.projectTimepoint(ZProjector.Mode.MAX_Z, "00001.tif");
        assertEquals(8, projected.sourceBitDepth);

        float[][] projection = projected.result.projection;
        int[][]   zOrigin    = projected.result.zOriginIndex;
        assertEquals(HEIGHT, projection.length);
        assertEquals(WIDTH,  projection[0].length);

        // Allocation-free verification: every expected value comes from the closed-form rule
        // above, computed from x, y and int arithmetic alone. Building an expected
        // float[][][] over all slices would need the same 400 MiB the streaming design exists
        // to avoid, and would OOM here — the check has to be as memory-frugal as the code it
        // checks. Only the two result frames the projector already returned are held.
        int mismatches = 0;
        String firstMismatch = null;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int expectedLayer = winningLayer(x, y);
                int expectedValue = pixelValue(x, y, expectedLayer);
                if (projection[y][x] != expectedValue || zOrigin[y][x] != expectedLayer) {
                    if (mismatches == 0) {
                        firstMismatch = "at (" + x + "," + y + "): projection="
                                + projection[y][x] + " (expected " + expectedValue
                                + "), zOrigin=" + zOrigin[y][x] + " (expected "
                                + expectedLayer + ")";
                    }
                    mismatches++;
                }
            }
        }
        assertEquals(0, mismatches,
                "projection/z-origin wrong for " + mismatches + " of " + (WIDTH * HEIGHT)
                + " pixels; first " + firstMismatch);

        // The planted marker again, stated on its own so a failure names it directly.
        assertEquals(MARKER_VALUE, projection[MARKER_Y][MARKER_X], 0.0f);
        assertEquals(MARKER_LAYER, zOrigin[MARKER_Y][MARKER_X]);
    }

    // ── Fixture generation ────────────────────────────────────────────────────

    /**
     * Writes the fixture <b>without ever holding it in memory</b>, by handing {@link FileSaver}
     * a {@link VirtualStack} that synthesises each slice on demand. {@code saveAsTiffStack}
     * detects {@code isVirtual()}, harvests the labels via {@code getSliceLabel(i)}, and routes
     * the pixels through {@code ImageWriter.write8BitVirtualStack}, which pulls one slice at a
     * time — peak ~1 MiB. A normal in-memory {@code ImageStack} would need the full 100 MiB and
     * would OOM inside this test's 96 MiB heap before the invariant was ever exercised.
     */
    private static File writeGeneratedStack(File tif) {
        ImagePlus imp = new ImagePlus("generated", new OnDemandStack());

        // That routing is an ij implementation detail. If a future ij stops honouring
        // isVirtual() here, the save silently becomes an in-memory one and this test dies with
        // a bare OutOfMemoryError that looks like the invariant broke when it did not. Fail
        // with an explanation instead. (Catching OutOfMemoryError would be the wrong fix — it
        // would hide exactly the signal this test is built on.)
        assertTrue(imp.getStack().isVirtual(),
                "the fixture must be written slice-on-demand: at " + WIDTH + "x" + HEIGHT + "x"
                + SLICES + " it does not fit in this test's 96 MiB heap. FileSaver only streams "
                + "when ImagePlus.getStack().isVirtual() is true, and here it is not.");

        assertTrue(new FileSaver(imp).saveAsTiffStack(tif.getAbsolutePath()),
                "FileSaver could not write the generated stack to " + tif);
        return tif;
    }

    /** A stack with no backing storage: each slice is computed when it is asked for. */
    private static final class OnDemandStack extends VirtualStack {

        OnDemandStack() {
            super(WIDTH, HEIGHT, SLICES, "8-bit");
        }

        @Override
        public ImageProcessor getProcessor(int n) {
            int k = n - 1;
            byte[] pixels = new byte[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) {
                int row = y * WIDTH;
                for (int x = 0; x < WIDTH; x++) {
                    pixels[row + x] = (byte) pixelValue(x, y, k);
                }
            }
            return new ByteProcessor(WIDTH, HEIGHT, pixels, null);
        }

        /**
         * The ImageJ {@code z = -400.000} form the scanner reads each layer's depth from.
         * {@link Locale#ROOT} is not decoration: a comma-decimal locale would emit
         * {@code z = -100,000}, which {@code parseZLabel} truncates at the comma (a documented
         * tolerance), making this fixture's depths locale-dependent.
         */
        @Override
        public String getSliceLabel(int n) {
            return String.format(Locale.ROOT, "z = %.3f", layerZ(n - 1));
        }
    }
}
