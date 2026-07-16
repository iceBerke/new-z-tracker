package ztracker.export;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes the projection tool's outputs, byte-compatible with what the extractor plugin
 * reads back ({@code TiffStackLoader} + {@code ZMappingLoader}). Mirrors the outputs of
 * {@code max_z_projection_plus_z_tracking_v2.py} / {@code min_z_..._v2.py}:
 *
 * <ul>
 *   <li>{@code z_origin/z_origin_<name>.tif} — 16-bit indexed TIFF (skipped, with a
 *       returned flag, if any index exceeds the uint16 range — matching the script,
 *       which refuses to let {@code astype(uint16)} silently wrap).</li>
 *   <li>{@code z_origin_32bit/z_origin_32bit_<name>.tif} — 32-bit indexed TIFF (always safe).</li>
 *   <li>{@code z_layer_mapping.json} + {@code z_layer_mapping_32bit.json} — identical
 *       {@code {"index": Z}} maps, hand-rolled (no JSON library) so they parse under
 *       {@code ZMappingLoader}'s regex.</li>
 *   <li>{@code raw/<mode>_projection_<name>.tif} — 8-bit min-max-normalized projection
 *       (visualization only; the extractor never reads it).</li>
 * </ul>
 *
 * <p>The z-origin index maps are written so that a {@code ShortProcessor} (16-bit, read
 * back via {@code getPixel}) and a {@code FloatProcessor} (32-bit, read back via
 * {@code getf} + {@code Math.round}) both reproduce the exact integer indices.
 */
public final class ProjectionExporter {

    /** Highest index storable in an unsigned 16-bit TIFF. */
    public static final int UINT16_MAX = 65535;

    private ProjectionExporter() {}

    // ── JSON mapping ──────────────────────────────────────────────────────────

    /**
     * Writes both z-layer mapping JSONs (16-bit-paired and 32-bit-paired, identical
     * content) into {@code datasetOutDir}.
     */
    public static void writeMappings(File datasetOutDir, double[] zValues) throws IOException {
        String json = buildMappingJson(zValues);
        writeText(new File(datasetOutDir, "z_layer_mapping.json"), json);
        writeText(new File(datasetOutDir, "z_layer_mapping_32bit.json"), json);
    }

    /**
     * Builds the {@code {"0": z0, "1": z1, ...}} JSON text. Values use {@link Double#toString},
     * which always emits a decimal point (or scientific notation) — both accepted by
     * {@code ZMappingLoader}'s {@code "(\d+)"\s*:\s*(-?[\d.]+(?:[eE][+-]?\d+)?)} regex —
     * and mirrors the Python {@code float(z)} repr (e.g. {@code -300.0}).
     */
    static String buildMappingJson(double[] zValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (int i = 0; i < zValues.length; i++) {
            sb.append("  \"").append(i).append("\": ").append(Double.toString(zValues[i]));
            if (i < zValues.length - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ── z-origin TIFFs ────────────────────────────────────────────────────────

    /**
     * Writes the 16-bit z-origin TIFF as {@code z_origin_<filename>} into {@code z16Dir}.
     *
     * @return {@code true} if written, {@code false} if skipped because an index exceeded
     *         {@link #UINT16_MAX} (the caller logs the skip, as the script does)
     */
    public static boolean write16BitOrigin(File z16Dir, String filename, int[][] zOrigin)
            throws IOException {
        int height = zOrigin.length;
        int width  = height == 0 ? 0 : zOrigin[0].length;

        short[] pix = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = zOrigin[y][x];
                if (idx > UINT16_MAX) return false; // would wrap in uint16 — refuse, like the script
                pix[y * width + x] = (short) idx;   // 0..65535 fits in unsigned short storage
            }
        }
        ShortProcessor sp = new ShortProcessor(width, height, pix, null);
        saveTiff(sp, new File(z16Dir, "z_origin_" + filename));
        return true;
    }

    /** Writes the 32-bit z-origin TIFF as {@code z_origin_32bit_<filename>} into {@code z32Dir}. */
    public static void write32BitOrigin(File z32Dir, String filename, int[][] zOrigin)
            throws IOException {
        int height = zOrigin.length;
        int width  = height == 0 ? 0 : zOrigin[0].length;

        float[] pix = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pix[y * width + x] = zOrigin[y][x]; // exact for indices well under 2^24
            }
        }
        FloatProcessor fp = new FloatProcessor(width, height, pix);
        saveTiff(fp, new File(z32Dir, "z_origin_32bit_" + filename));
    }

    // ── Raw projection ────────────────────────────────────────────────────────

    /**
     * Writes the 8-bit min-max-normalized projection as {@code <rawPrefix><filename>}
     * (e.g. {@code max_z_projection_frame_0001.tif}). Visualization only.
     *
     * <p>An already-8-bit source is written through unchanged (values are copied, not
     * re-scaled), matching the script's {@code if dtype == uint8} short-circuit.
     */
    public static void writeRaw(File rawDir, String rawPrefix, String filename,
                                float[][] projection, int sourceBitDepth) throws IOException {
        int height = projection.length;
        int width  = height == 0 ? 0 : projection[0].length;

        byte[] pix = normalizeToByte(projection, width, height, sourceBitDepth);
        ByteProcessor bp = new ByteProcessor(width, height, pix, null);
        saveTiff(bp, new File(rawDir, rawPrefix + filename));
    }

    /**
     * Min-max normalization to 0..255 (the Java equivalent of {@code cv2.normalize(..,
     * NORM_MINMAX)}). Already-8-bit input is copied directly; a flat image maps to all zeros.
     */
    static byte[] normalizeToByte(float[][] projection, int width, int height, int sourceBitDepth) {
        byte[] out = new byte[width * height];

        if (sourceBitDepth == 8) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[y * width + x] = (byte) Math.round(projection[y][x]);
                }
            }
            return out;
        }

        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = projection[y][x];
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        float range = max - min;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = (range <= 0f)
                        ? 0
                        : Math.round((projection[y][x] - min) / range * 255f);
                out[y * width + x] = (byte) v;
            }
        }
        return out;
    }

    // ── Shared I/O ────────────────────────────────────────────────────────────

    private static void saveTiff(ij.process.ImageProcessor ip, File dest) throws IOException {
        ImagePlus imp = new ImagePlus(dest.getName(), ip);
        boolean ok = new FileSaver(imp).saveAsTiff(dest.getAbsolutePath());
        if (!ok) {
            throw new IOException("Failed to write TIFF: " + dest.getAbsolutePath());
        }
    }

    private static void writeText(File dest, String text) throws IOException {
        Files.write(dest.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
