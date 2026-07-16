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
                    if (max ? (v > best) : (v < best)) {
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
}
