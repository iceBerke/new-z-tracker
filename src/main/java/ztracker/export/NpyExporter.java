package ztracker.export;

import ij.IJ;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes 2-D NumPy {@code .npy} arrays (float64, C-order, no Python required).
 *
 * <p>The NPY v1.0 format is straightforward:
 * <pre>
 *   6 bytes  magic: \x93NUMPY
 *   1 byte   major version: 1
 *   1 byte   minor version: 0
 *   2 bytes  HEADER_LEN (little-endian uint16)
 *   N bytes  ASCII header dict, padded with spaces to a 64-byte boundary,
 *            terminated with '\n'
 *   data     row-major float64 values
 * </pre>
 *
 * <p>Output files follow the pipeline convention:
 * <ul>
 *   <li>2D: columns {@code [X, Y, T]}</li>
 *   <li>3D: columns {@code [X, Y, Z, T]}</li>
 * </ul>
 */
public class NpyExporter {

    private static final byte[] MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};
    private static final byte   MAJOR = 1;
    private static final byte   MINOR = 0;

    private NpyExporter() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Writes a 2-D float64 array to a {@code .npy} file.
     *
     * @param data    row-major data; data[row][col]
     * @param outPath destination file path (will be created/overwritten)
     * @throws IOException on write error
     */
    public static void write(double[][] data, Path outPath) throws IOException {
        Files.createDirectories(outPath.getParent());

        int rows = data.length;
        int cols = (rows == 0) ? 0 : data[0].length;

        // Build header string
        String headerStr = String.format(
                "{'descr': '<f8', 'fortran_order': False, 'shape': (%d, %d), }",
                rows, cols);

        // Pad header so that (MAGIC + version + HEADER_LEN(2) + header) % 64 == 0
        int prefixLen  = MAGIC.length + 2 + 2; // magic + version bytes + uint16
        int rawLen     = prefixLen + headerStr.length() + 1; // +1 for '\n'
        int padded     = ((rawLen + 63) / 64) * 64;
        int headerLen  = padded - prefixLen;               // includes the '\n'
        int spacePad   = headerLen - headerStr.length() - 1;

        StringBuilder sb = new StringBuilder(headerStr);
        for (int i = 0; i < spacePad; i++) sb.append(' ');
        sb.append('\n');
        byte[] headerBytes = sb.toString().getBytes("ASCII");

        // Write
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outPath.toFile())))) {

            out.write(MAGIC);
            out.write(MAJOR);
            out.write(MINOR);

            // HEADER_LEN as little-endian uint16
            out.write(headerBytes.length & 0xFF);
            out.write((headerBytes.length >> 8) & 0xFF);

            out.write(headerBytes);

            // Data: little-endian float64
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            for (double[] row : data) {
                for (double v : row) {
                    buf.clear();
                    buf.putDouble(v);
                    out.write(buf.array());
                }
            }
        }
    }

    // ── Convenience builders ──────────────────────────────────────────────────

    /**
     * Builds a [X, Y, T] array and writes it as a 2D track .npy file.
     *
     * @param x     X coordinates (pixels)
     * @param y     Y coordinates (pixels)
     * @param t     frame numbers
     * @param path  destination .npy file
     */
    public static void write2DTrack(double[] x, double[] y, int[] t, Path path)
            throws IOException {
        double[][] data = new double[x.length][3];
        for (int i = 0; i < x.length; i++) {
            data[i][0] = x[i];
            data[i][1] = y[i];
            data[i][2] = t[i];
        }
        write(data, path);
    }

    /**
     * Builds a [X, Y, Z, T] array and writes it as a 3D track .npy file.
     *
     * @param x     X coordinates (pixels)
     * @param y     Y coordinates (pixels)
     * @param z     Z coordinates (µm)
     * @param t     frame numbers
     * @param path  destination .npy file
     */
    public static void write3DTrack(double[] x, double[] y, double[] z, int[] t, Path path)
            throws IOException {
        double[][] data = new double[x.length][4];
        for (int i = 0; i < x.length; i++) {
            data[i][0] = x[i];
            data[i][1] = y[i];
            data[i][2] = z[i];
            data[i][3] = t[i];
        }
        write(data, path);
    }
}
