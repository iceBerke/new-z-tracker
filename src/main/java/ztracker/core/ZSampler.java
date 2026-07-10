package ztracker.core;

import ztracker.io.TiffStackLoader.LoadedStack;

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
 */
public class ZSampler {

    public enum Method {
        RADIUS("Radius-based"),
        FOUR_NEIGHBOR("4-Neighbor"),
        SINGLE_PIXEL("Single Pixel");

        public final String label;
        Method(String label) { this.label = label; }
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
     * @return raw 16-bit index values at the sampled pixels; empty if frame missing
     */
    public static double[] sample(LoadedStack stack, double x, double y,
                                  int csvFrame, int frameOffset,
                                  double radius, Method method) {
        int tiffFrame    = csvFrame + frameOffset;
        Integer stackIdx = stack.frameToIdx.get(tiffFrame);
        if (stackIdx == null) return new double[0]; // frame not in stack

        if (method == Method.RADIUS)        return sampleRadius(stack, x, y, stackIdx, radius);
        if (method == Method.FOUR_NEIGHBOR) return sample4Neighbors(stack, x, y, stackIdx);
        if (method == Method.SINGLE_PIXEL)  return sampleSinglePixel(stack, x, y, stackIdx);
        throw new IllegalArgumentException("Unknown sampling method: " + method);
    }

    // ── Sampling strategies ───────────────────────────────────────────────────

    /**
     * Samples all pixels within a circular disk of the given radius.
     */
    private static double[] sampleRadius(LoadedStack stack, double x, double y,
                                          int stackIdx, double radius) {
        int r       = (int) Math.ceil(radius);
        int cx      = (int) Math.round(x);
        int cy      = (int) Math.round(y);
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
     * Samples the 4 nearest integer-coordinate neighbours (bilinear corners).
     */
    private static double[] sample4Neighbors(LoadedStack stack, double x, double y, int stackIdx) {
        int xf = (int) Math.floor(x), xc = (int) Math.ceil(x);
        int yf = (int) Math.floor(y), yc = (int) Math.ceil(y);
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
     * Samples the single nearest pixel.
     */
    private static double[] sampleSinglePixel(LoadedStack stack, double x, double y, int stackIdx) {
        int px = (int) Math.round(x);
        int py = (int) Math.round(y);
        if (!inBounds(stack, stackIdx, px, py)) return new double[0];
        return new double[]{stack.pixels[stackIdx][py][px]};
    }

    // ── Utility ───────────────────────────────────────────────────────────────

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
