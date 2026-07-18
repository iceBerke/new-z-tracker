package ztracker.core.extractor;

import ztracker.io.extractor.TiffStackLoader.LoadedStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Samples raw pixel values (Z-layer indices) from a 16-bit or 32-bit indexed
 * TIFF stack at a given (x, y, frame) position.
 *
 * <p>Three strategies are available, matching the Python pipeline:
 * <ul>
 *   <li>{@link #sampleRadius} — all pixels within a circular radius (recommended)</li>
 *   <li>{@link #sample4Neighbors} — the 4 nearest integer-coordinate neighbours</li>
 *   <li>{@link #sampleSinglePixel} — the nearest single pixel</li>
 * </ul>
 *
 * <p>All methods return a {@code double[]} of raw index values. An empty array
 * signals that the frame was missing from the stack or the position was out
 * of bounds.
 *
 * <p>Every strategy also depends on a {@link PixelConvention}: whether an integer
 * X/Y coordinate marks a pixel's <b>center</b> (pixel {@code i} spans
 * {@code [i-0.5, i+0.5)}) or its top-left <b>corner</b> (pixel {@code i} spans
 * {@code [i, i+1)}, center at {@code i+0.5}). The two conventions disagree on
 * which pixel a sub-pixel position belongs to — see README.md's "How sampling
 * works, precisely" section for worked examples.
 */
public class ZSampler {

    public enum Method {
        RADIUS("Radius-based"),
        FOUR_NEIGHBOR("4-Neighbor"),
        SINGLE_PIXEL("Single Pixel");

        public final String label;
        Method(String label) { this.label = label; }
    }

    /** Whether an integer X/Y coordinate marks a pixel's center or its top-left corner. */
    public enum PixelConvention {
        PIXEL_CORNER("Pixel = corner (floor to containing cell)"),
        PIXEL_CENTER("Pixel = center (round to nearest)");

        public final String label;
        PixelConvention(String label) { this.label = label; }
    }

    private ZSampler() {}

    // ── Public dispatch ───────────────────────────────────────────────────────

    /**
     * Routes to the appropriate sampling method.
     *
     * @param stack       loaded TIFF stack
     * @param x           sub-pixel X position (pixels)
     * @param y           sub-pixel Y position (pixels)
     * @param csvFrame    frame number from the CSV (before offset)
     * @param frameOffset offset added to csvFrame to find the TIFF frame
     * @param radius      sampling radius in pixels (used only for RADIUS method)
     * @param method      which sampling strategy to use
     * @param convention  whether integer X/Y mark a pixel's center or corner
     * @return raw 16-bit index values at the sampled pixels; empty if frame missing
     */
    public static double[] sample(LoadedStack stack, double x, double y,
                                  int csvFrame, int frameOffset,
                                  double radius, Method method, PixelConvention convention) {
        int tiffFrame    = csvFrame + frameOffset;
        Integer stackIdx = stack.frameToIdx.get(tiffFrame);
        if (stackIdx == null) return new double[0]; // frame not in stack

        if (method == Method.RADIUS)        return sampleRadius(stack, x, y, stackIdx, radius, convention);
        if (method == Method.FOUR_NEIGHBOR) return sample4Neighbors(stack, x, y, stackIdx, convention);
        if (method == Method.SINGLE_PIXEL)  return sampleSinglePixel(stack, x, y, stackIdx, convention);
        throw new IllegalArgumentException("Unknown sampling method: " + method);
    }

    // ── Sampling strategies ───────────────────────────────────────────────────

    /**
     * Samples all pixels within a circular disk of the given radius, anchored on the
     * pixel containing {@code (x, y)} under {@code convention} (not on true continuous
     * distance from the exact sub-pixel position — this mirrors the existing anchor-then-
     * offset-mask approximation for both conventions).
     */
    private static double[] sampleRadius(LoadedStack stack, double x, double y,
                                          int stackIdx, double radius, PixelConvention convention) {
        int r       = (int) Math.ceil(radius);
        int cx      = containingPixel(x, convention);
        int cy      = containingPixel(y, convention);
        double r2   = radius * radius;

        List<Double> values = new ArrayList<>();
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= r2) {
                    int px = cx + dx;
                    int py = cy + dy;
                    if (inBounds(stack, stackIdx, px, py)) {
                        values.add((double) stack.pixels[stackIdx][py][px]);
                    }
                }
            }
        }
        return toArray(values);
    }

    /**
     * Samples the 4 nearest integer-coordinate neighbours (bilinear corners) — the two
     * pixel centers bracketing {@code x}, and the two bracketing {@code y}.
     */
    private static double[] sample4Neighbors(LoadedStack stack, double x, double y,
                                              int stackIdx, PixelConvention convention) {
        // Under PIXEL_CENTER, integer coordinates already are pixel centers, so the two
        // centers bracketing x are floor(x)/ceil(x). Under PIXEL_CORNER, pixel i's center
        // is at i+0.5, so bracketing x's two nearest centers requires the standard bilinear
        // half-pixel shift: floor(x-0.5) and floor(x-0.5)+1.
        int xf, xc, yf, yc;
        if (convention == PixelConvention.PIXEL_CENTER) {
            xf = (int) Math.floor(x); xc = (int) Math.ceil(x);
            yf = (int) Math.floor(y); yc = (int) Math.ceil(y);
        } else {
            xf = (int) Math.floor(x - 0.5); xc = xf + 1;
            yf = (int) Math.floor(y - 0.5); yc = yf + 1;
        }
        int[][] corners = {{xf, yf}, {xc, yf}, {xf, yc}, {xc, yc}};

        List<Double> values = new ArrayList<>(4);
        for (int[] c : corners) {
            if (inBounds(stack, stackIdx, c[0], c[1])) {
                values.add((double) stack.pixels[stackIdx][c[1]][c[0]]);
            }
        }
        return toArray(values);
    }

    /**
     * Samples the single pixel containing {@code (x, y)} under {@code convention}.
     */
    private static double[] sampleSinglePixel(LoadedStack stack, double x, double y,
                                               int stackIdx, PixelConvention convention) {
        int px = containingPixel(x, convention);
        int py = containingPixel(y, convention);
        if (!inBounds(stack, stackIdx, px, py)) return new double[0];
        return new double[]{stack.pixels[stackIdx][py][px]};
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * The pixel index containing continuous coordinate {@code v} under {@code convention}.
     * PIXEL_CENTER rounds to the nearest integer (pixel {@code i} spans {@code [i-0.5, i+0.5)}).
     * PIXEL_CORNER floors (pixel {@code i} spans {@code [i, i+1)}) — equivalently, the pixel
     * whose center {@code i+0.5} is nearest to {@code v}.
     */
    private static int containingPixel(double v, PixelConvention convention) {
        return convention == PixelConvention.PIXEL_CENTER
                ? (int) Math.round(v)
                : (int) Math.floor(v);
    }

    private static boolean inBounds(LoadedStack stack, int si, int px, int py) {
        return si >= 0 && si < stack.pixels.length
                && py >= 0 && py < stack.height
                && px >= 0 && px < stack.width;
    }

    private static double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
