package ztracker.project;

import java.util.List;

/**
 * Core min/max Z-projection with per-pixel z-origin tracking.
 *
 * <p><b>I/O-free by design</b> — it operates on plain intensity arrays so the
 * projection logic is fully unit-testable without ImageJ or the filesystem.
 * Reading TIFFs is {@code ProjectionInputScanner}'s job; writing them is
 * {@code ProjectionExporter}'s.
 *
 * <p>This is the Java port of {@code process_single_image_stack()} from
 * {@code max_z_projection_plus_z_tracking_v2.py} /
 * {@code min_z_projection_plus_z_tracking_v2.py}. Those two scripts differ
 * <em>only</em> in {@code np.max}/{@code np.argmax} vs {@code np.min}/{@code np.argmin};
 * that single difference is captured here by {@link Mode}.
 */
public final class ZProjector {

    /** Which extreme wins the projection. */
    public enum Mode {
        /** Brightest pixel across the stack wins (like {@code np.max}/{@code argmax}). */
        MAX_Z,
        /** Darkest pixel across the stack wins (like {@code np.min}/{@code argmin}). */
        MIN_Z
    }

    /** One timepoint's projection result: the projected image plus its z-origin index map. */
    public static final class Result {
        /** Pixel-wise min/max intensity projection, indexed {@code [y][x]}. */
        public final float[][] projection;
        /**
         * Global z-layer index of the winning pixel, indexed {@code [y][x]}. These are
         * the integer indices written into the z-origin TIFF and keyed in the JSON mapping.
         */
        public final int[][] zOriginIndex;

        public Result(float[][] projection, int[][] zOriginIndex) {
            this.projection   = projection;
            this.zOriginIndex = zOriginIndex;
        }
    }

    private ZProjector() {}

    /**
     * Projects one timepoint's z-stack pixel-by-pixel.
     *
     * <p><b>NaN handling (p10.11).</b> A {@code NaN} is not a measurement, so it never wins:
     * the result is the extremum over the <em>real</em> values at that pixel, whichever layers
     * hold them. Only relevant to 32-bit float input (8-/16-bit cannot represent NaN) — the
     * realistic source being Fiji's {@code Process > Math > NaN Background}.
     *
     * <p><b>Documented limit:</b> if <em>every</em> layer is NaN at a pixel there is no real
     * value to name, and the pixel reports layer index 0 — indistinguishable downstream from a
     * genuine layer-0 origin. The indexed z-origin format carries layer indices with no spare
     * "no data" value, so expressing it would be a format decision, not a local fix; see
     * CLAUDE.md for why that was not taken.
     *
     * @param mode         {@link Mode#MAX_Z} (brightest wins) or {@link Mode#MIN_Z} (darkest wins)
     * @param slices       one intensity array per <em>present</em> z-layer, each {@code [height][width]};
     *                     all slices must share the same dimensions
     * @param globalZIndex parallel to {@code slices}: the global index (position in the full,
     *                     sorted z-layer list for the dataset) of each slice. This lets a
     *                     timepoint that is missing some z-layers still record correct global
     *                     indices, exactly like numpy's {@code np.take(valid_z_indices, argmax)}.
     * @return the projection image and its per-pixel z-origin index map
     * @throws IllegalArgumentException if the stack is empty, lengths disagree, or slice
     *                                  dimensions are inconsistent
     */
    public static Result project(Mode mode, List<float[][]> slices, int[] globalZIndex) {
        if (slices == null || slices.isEmpty()) {
            throw new IllegalArgumentException("Empty z-stack: nothing to project.");
        }
        if (globalZIndex == null || globalZIndex.length != slices.size()) {
            throw new IllegalArgumentException(
                    "globalZIndex length (" + (globalZIndex == null ? "null" : globalZIndex.length)
                    + ") must equal slice count (" + slices.size() + ").");
        }

        final int height = slices.get(0).length;
        final int width  = height == 0 ? 0 : slices.get(0)[0].length;

        // Defensive shape check — np.array(image_stack) would raise on ragged input.
        for (int k = 0; k < slices.size(); k++) {
            float[][] s = slices.get(k);
            if (s.length != height || (height > 0 && s[0].length != width)) {
                throw new IllegalArgumentException(
                        "Inconsistent slice dimensions at index " + k + ": expected "
                        + width + "x" + height + ", got "
                        + (s.length == 0 ? 0 : s[0].length) + "x" + s.length + ".");
            }
        }

        final float[][] projection = new float[height][width];
        final int[][]   zOrigin    = new int[height][width];
        final boolean   max        = (mode == Mode.MAX_Z);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float best      = slices.get(0)[y][x];
                int   bestSlice = 0;
                for (int k = 1; k < slices.size(); k++) {
                    float v = slices.get(k)[y][x];
                    // Strict comparison keeps the FIRST winning slice on ties,
                    // matching numpy's argmax/argmin (which return the first extreme).
                    // The isNaN guard is the p10.11 fix: every comparison against NaN is
                    // false in Java, so a NaN seed could never be beaten and pinned the pixel
                    // to slice 0 — a valid mapping key, hence a confident wrong Z downstream.
                    // A NaN is displaced by any real value; it never displaces one. Inert on
                    // NaN-free input (best is then never NaN, so this collapses to the
                    // comparison above). Accumulator.add carries the identical line.
                    if (Float.isNaN(best) ? !Float.isNaN(v) : (max ? (v > best) : (v < best))) {
                        best      = v;
                        bestSlice = k;
                    }
                }
                projection[y][x] = best;
                zOrigin[y][x]    = globalZIndex[bestSlice];
            }
        }
        return new Result(projection, zOrigin);
    }

    /**
     * Incremental form of {@link #project}: slices are fed in <em>one at a time</em> and only
     * the running result is retained, so peak memory is two full-frame buffers
     * ({@code float[][]} + {@code int[][]}) instead of the entire z-stack.
     *
     * <p>This exists for the TIFF-stack input type, where a single timepoint is one large
     * multi-slice file — a 401-slice 1051×1674 stack is ~700 MB on disk and would be ~2.8 GB
     * once every slice is held as {@code float[][]}. Fed from a virtual (on-demand) stack, the
     * accumulator keeps that flat at a few tens of MB no matter how deep the stack is.
     *
     * <p><b>Identical results to {@link #project}</b>, given the same slices in the same order:
     * same strict comparison, so the <em>first</em> slice added wins ties. Callers that want
     * ties resolved toward the lowest Z (as the folder layout does, since it walks layers in
     * ascending Z order) must add slices in ascending-Z order too. Parity is locked in by
     * {@code ZProjectorAccumulatorTest}.
     */
    public static final class Accumulator {

        private final boolean max;
        private float[][] projection;
        private int[][]   zOrigin;
        private int width  = -1;
        private int height = -1;
        private int sliceCount;

        public Accumulator(Mode mode) {
            this.max = (mode == Mode.MAX_Z);
        }

        /**
         * Folds one slice into the running projection.
         *
         * @param slice        intensities {@code [height][width]}; <b>only read</b>, never
         *                     retained — the caller may reuse the same buffer for every slice
         * @param globalZIndex this slice's global z-layer index (what lands in the z-origin map)
         * @throws IllegalArgumentException if the slice's dimensions differ from earlier slices
         */
        public void add(float[][] slice, int globalZIndex) {
            if (slice == null) {
                throw new IllegalArgumentException("Null slice added to the projection.");
            }
            int h = slice.length;
            int w = (h == 0) ? 0 : slice[0].length;

            if (sliceCount == 0) {
                width      = w;
                height     = h;
                projection = new float[h][w];
                zOrigin    = new int[h][w];
                for (int y = 0; y < h; y++) {
                    System.arraycopy(slice[y], 0, projection[y], 0, w);
                    java.util.Arrays.fill(zOrigin[y], globalZIndex);
                }
                sliceCount = 1;
                return;
            }

            if (h != height || w != width) {
                throw new IllegalArgumentException(
                        "Inconsistent slice dimensions at index " + sliceCount + ": expected "
                        + width + "x" + height + ", got " + w + "x" + h + ".");
            }

            for (int y = 0; y < height; y++) {
                float[] row  = slice[y];
                float[] best = projection[y];
                int[]   org  = zOrigin[y];
                for (int x = 0; x < width; x++) {
                    float v = row[x];
                    // Strict comparison — first slice added keeps the tie, as in project().
                    // The isNaN guard is deliberately the IDENTICAL line project() carries:
                    // both paths must take the same rule or the Accumulator ≡ project
                    // equivalence stops holding. Guarding the comparison rather than seeding
                    // from the first non-NaN is what lets this path express it at all — it is
                    // streaming and cannot look ahead for a seed.
                    if (Float.isNaN(best[x]) ? !Float.isNaN(v)
                                             : (max ? (v > best[x]) : (v < best[x]))) {
                        best[x] = v;
                        org[x]  = globalZIndex;
                    }
                }
            }
            sliceCount++;
        }

        /** How many slices have been folded in so far. */
        public int sliceCount() { return sliceCount; }

        /**
         * @return the projection and z-origin map accumulated so far
         * @throws IllegalStateException if no slice was ever added
         */
        public Result result() {
            if (sliceCount == 0) {
                throw new IllegalStateException("Empty z-stack: nothing to project.");
            }
            return new Result(projection, zOrigin);
        }
    }
}
