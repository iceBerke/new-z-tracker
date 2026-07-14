package ztracker.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests for the hand-rolled NumPy v1.0 writer, independent of {@link TrackExportManager}.
 * {@code NpyExporter} must stay byte-compatible with {@code numpy.load()} (see CLAUDE.md), so
 * these tests check the on-disk format itself (magic bytes, dtype, fortran_order, 64-byte header
 * alignment) rather than only round-tripping through the project's own reader helpers.
 */
class NpyExporterTest {

    @Test
    void write_producesCorrectMagicVersionAndDtype(@TempDir Path outDir) throws IOException {
        Path path = outDir.resolve("out.npy");
        NpyExporter.write(new double[][]{{1.0, 2.0}, {3.0, 4.0}}, path);

        byte[] bytes = Files.readAllBytes(path);

        assertEquals((byte) 0x93, bytes[0]);
        assertEquals('N', bytes[1]);
        assertEquals('U', bytes[2]);
        assertEquals('M', bytes[3]);
        assertEquals('P', bytes[4]);
        assertEquals('Y', bytes[5]);
        assertEquals(1, bytes[6], "major version");
        assertEquals(0, bytes[7], "minor version");

        int headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
        String header = new String(bytes, 10, headerLen, StandardCharsets.US_ASCII);
        assertTrue(header.contains("'descr': '<f8'"), "dtype must be little-endian float64");
        assertTrue(header.contains("'fortran_order': False"), "must be C-order, not Fortran-order");
        assertTrue(header.contains("'shape': (2, 2)"));
    }

    @Test
    void write_headerIsPaddedTo64ByteBoundary(@TempDir Path outDir) throws IOException {
        // Try a range of row/column counts so the header length varies and the padding
        // math (rawLen rounded up to the next 64-byte multiple) is exercised repeatedly.
        for (int cols = 1; cols <= 5; cols++) {
            Path path = outDir.resolve("out_" + cols + ".npy");
            double[][] data = new double[3][cols];
            NpyExporter.write(data, path);

            byte[] bytes = Files.readAllBytes(path);
            int headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
            int prefixLen = 6 + 2 + 2; // magic + version + uint16 length field
            assertEquals(0, (prefixLen + headerLen) % 64,
                    "magic+version+header_len+header must align to a 64-byte boundary for cols=" + cols);

            // The header dict must end with a newline just before the data starts.
            assertEquals('\n', (char) bytes[10 + headerLen - 1]);
        }
    }

    @Test
    void write_dataIsLittleEndianFloat64InRowMajorOrder(@TempDir Path outDir) throws IOException {
        Path path = outDir.resolve("out.npy");
        double[][] data = {{1.5, -2.25}, {100.0, 0.0}};
        NpyExporter.write(data, path);

        assertArrayEquals(new double[]{1.5, -2.25, 100.0, 0.0}, readRawFloat64(path), 0.0);
    }

    @Test
    void write2DTrack_writesExactXYTColumnsInOrder(@TempDir Path outDir) throws IOException {
        double[] x = {10.25, 20.5, 30.75};
        double[] y = {1.125, 2.5, -3.0};
        int[]    t = {0, 5, 12};

        Path path = outDir.resolve("track.npy");
        NpyExporter.write2DTrack(x, y, t, path);

        double[][] rows = readRows(path, 3, 3);
        for (int i = 0; i < 3; i++) {
            assertEquals(x[i], rows[i][0], 0.0, "X column mismatch at row " + i);
            assertEquals(y[i], rows[i][1], 0.0, "Y column mismatch at row " + i);
            assertEquals(t[i], rows[i][2], 0.0, "T column mismatch at row " + i);
        }
    }

    @Test
    void write3DTrack_writesExactXYZTColumnsInOrder(@TempDir Path outDir) throws IOException {
        double[] x = {10.25, 20.5};
        double[] y = {1.125, 2.5};
        double[] z = {-50.0, 75.5};
        int[]    t = {3, 4};

        Path path = outDir.resolve("track.npy");
        NpyExporter.write3DTrack(x, y, z, t, path);

        double[][] rows = readRows(path, 2, 4);
        for (int i = 0; i < 2; i++) {
            assertEquals(x[i], rows[i][0], 0.0, "X column mismatch at row " + i);
            assertEquals(y[i], rows[i][1], 0.0, "Y column mismatch at row " + i);
            assertEquals(z[i], rows[i][2], 0.0, "Z column mismatch at row " + i);
            assertEquals(t[i], rows[i][3], 0.0, "T column mismatch at row " + i);
        }
    }

    // ── Local raw .npy readers (independent implementation from TrackExportManagerTest's,
    //    both exist since neither should mask a bug the other would catch) ────────────────

    private static double[] readRawFloat64(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
        int dataStart = 10 + headerLen;
        int count = (bytes.length - dataStart) / 8;
        ByteBuffer buf = ByteBuffer.wrap(bytes, dataStart, bytes.length - dataStart)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) values[i] = buf.getDouble();
        return values;
    }

    private static double[][] readRows(Path path, int expectedRows, int expectedCols) throws IOException {
        double[] flat = readRawFloat64(path);
        assertEquals(expectedRows * expectedCols, flat.length);
        double[][] rows = new double[expectedRows][expectedCols];
        for (int r = 0; r < expectedRows; r++) {
            System.arraycopy(flat, r * expectedCols, rows[r], 0, expectedCols);
        }
        return rows;
    }
}