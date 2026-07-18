package ztracker.core.topoj;

import ztracker.core.extractor.ZSampler;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool 3 (TopoJ / direct-Z) counterpart to {@link ZSampler}.
 *
 * <p>Samples pixel values from a 32-bit float TopoJ stack at a given
 * {@code (x, y, frame)} position. The geometry is identical to {@link ZSampler}
 * (radius disk, 4-neighbour corners, single pixel; both {@link ZSampler.PixelConvention}
 * conventions) — the only difference is the pixel storage type
 * ({@code float[][][]} rather than {@code int[][][]}) and, crucially, the
 * <b>meaning</b> of the returned values: here each sampled value <b>is</b> the
 * physical Z coordinate in µm, so there is no subsequent index → Z mapping step.
 * {@link ZExtractor} maps its raw indices; {@link TopoJExtractor} aggregates
 * these values as-is.
 *
 * <p>This is a deliberate duplicate of {@link ZSampler}'s geometry so that Tool 1's
 * sampler stays byte-for-byte untouched (matching the plugin's isolate-per-tool
 * philosophy). It reuses {@link ZSampler.Method} and {@link ZSampler.PixelConvention}
 * as the shared method vocabulary rather than redefining them.
 *
 * <p>All methods return a {@code double[]} of sampled Z values. An empty array signals
 * that the frame was missing from the stack or the position was out of bounds.
 */
public class TopoJSampler {

    private TopoJSampler() {}

    // ── Public dispatch ───────────────────────────────────────────────────────

    /**
     * Routes to the appropriate sampling method.
     *
     * @param stack       loaded float stack
     * @param x           sub-pixel X position (pixels)
     * @param y           sub-pixel Y position (pixels)
     * @param csvFrame    frame number from the CSV (before offset)
     * @param frameOffset offset added to csvFrame to find the TIFF frame
     * @param radius      sampling radius in pixels (used only for RADIUS method)
     * @param method      which sampling strategy to use
     * @param convention  whether integer X/Y mark a pixel's center or corner
     * @return sampled Z values (µm) at the sampled pixels; empty if frame missing / out of bounds
     */
    public static double[] sample(LoadedFloatStack stack, double x, double y,
                                  int csvFrame, int frameOffset,
                                  double radius, ZSampler.Method method,
                                  ZSampler.PixelConvention convention) {
        int tiffFrame    = csvFrame + frameOffset;
        Integer stackIdx = stack.frameToIdx.get(tiffFrame);
        if (stackIdx == null) return new double[0]; // frame not in stack

        if (method == ZSampler.Method.RADIUS)        return sampleRadius(stack, x, y, stackIdx, radius, convention);
        if (method == ZSampler.Method.FOUR_NEIGHBOR) return sample4Neighbors(stack, x, y, stackIdx, convention);
        if (method == ZSampler.Method.SINGLE_PIXEL)  return sampleSinglePixel(stack, x, y, stackIdx, convention);
        throw new IllegalArgumentException("Unknown sampling method: " + method);
    }

    // ── Sampling strategies ───────────────────────────────────────────────────

    /**
     * Samples all pixels within a circular disk of the given radius, anchored on the
     * pixel containing {@code (x, y)} under {@code convention} (mirrors {@link ZSampler}'s
     * anchor-then-offset-mask approximation for both conventions).
     */
    private static double[] sampleRadius(LoadedFloatStack stack, double x, double y,
                                         int stackIdx, double radius,
                                         ZSampler.PixelConvention convention) {
        int r     = (int) Math.ceil(radius);
        int cx    = containingPixel(x, convention);
        int cy    = containingPixel(y, convention);
        double r2 = radius * radius;

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
    private static double[] sample4Neighbors(LoadedFloatStack stack, double x, double y,
                                             int stackIdx, ZSampler.PixelConvention convention) {
        int xf, xc, yf, yc;
        if (convention == ZSampler.PixelConvention.PIXEL_CENTER) {
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
    private static double[] sampleSinglePixel(LoadedFloatStack stack, double x, double y,
                                              int stackIdx, ZSampler.PixelConvention convention) {
        int px = containingPixel(x, convention);
        int py = containingPixel(y, convention);
        if (!inBounds(stack, stackIdx, px, py)) return new double[0];
        return new double[]{stack.pixels[stackIdx][py][px]};
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * The pixel index containing continuous coordinate {@code v} under {@code convention}.
     * PIXEL_CENTER rounds to the nearest integer; PIXEL_CORNER floors. Matches
     * {@link ZSampler}'s {@code containingPixel} exactly.
     */
    private static int containingPixel(double v, ZSampler.PixelConvention convention) {
        return convention == ZSampler.PixelConvention.PIXEL_CENTER
                ? (int) Math.round(v)
                : (int) Math.floor(v);
    }

    private static boolean inBounds(LoadedFloatStack stack, int si, int px, int py) {
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
