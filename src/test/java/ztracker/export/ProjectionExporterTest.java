package ztracker.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.io.TiffStackLoader;
import ztracker.io.ZMappingLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProjectionExporter}, including the <b>produce → consume seam</b>: the
 * z-origin TIFFs and JSON mapping this writer emits are read back through the very loaders
 * the extractor plugin uses ({@link TiffStackLoader}, {@link ZMappingLoader}) and must
 * reproduce the planted indices and Z values exactly. This is what proves the two plugins
 * actually interoperate.
 */
class ProjectionExporterTest {

    // ── JSON mapping ──────────────────────────────────────────────────────────

    @Test
    void mappingJson_parsesBackThroughZMappingLoader_withNegativesAndDecimals(@TempDir Path dir)
            throws Exception {
        double[] zValues = { -300.0, -299.5, 0.0, 12.25 };
        File out = dir.toFile();
        ProjectionExporter.writeMappings(out, zValues, true, true);

        // Both the 16-bit-paired and 32-bit-paired mappings must exist and be identical.
        File m16 = new File(out, "z_layer_mapping.json");
        File m32 = new File(out, "z_layer_mapping_32bit.json");
        assertTrue(m16.isFile());
        assertTrue(m32.isFile());
        assertEquals(new String(Files.readAllBytes(m16.toPath())),
                     new String(Files.readAllBytes(m32.toPath())));

        Map<Integer, Double> parsed = ZMappingLoader.load(m16.toPath());
        assertEquals(4, parsed.size());
        assertEquals(-300.0, parsed.get(0));
        assertEquals(-299.5, parsed.get(1));
        assertEquals(0.0,    parsed.get(2));
        assertEquals(12.25,  parsed.get(3));
    }

    @Test
    void writeMappings_onlyWritesTheSelectedDepthsMapping(@TempDir Path dir) throws Exception {
        double[] zValues = { 0.0, 1.0 };

        File only16 = new File(dir.toFile(), "only16");
        assertTrue(only16.mkdirs());
        ProjectionExporter.writeMappings(only16, zValues, true, false);
        assertTrue(new File(only16, "z_layer_mapping.json").isFile());
        assertFalse(new File(only16, "z_layer_mapping_32bit.json").isFile());

        File only32 = new File(dir.toFile(), "only32");
        assertTrue(only32.mkdirs());
        ProjectionExporter.writeMappings(only32, zValues, false, true);
        assertFalse(new File(only32, "z_layer_mapping.json").isFile());
        assertTrue(new File(only32, "z_layer_mapping_32bit.json").isFile());
    }

    // ── z-origin TIFF round-trips ─────────────────────────────────────────────

    @Test
    void origin16Bit_roundTripsThroughTiffStackLoader_includingValuesAbove32767(@TempDir Path dir)
            throws Exception {
        // 3x2, values include one > Short.MAX_VALUE to prove unsigned 16-bit handling.
        int[][] zOrigin = {
                {0, 1, 5},
                {100, 40000, 65535},
        };
        File z16 = new File(dir.toFile(), "z_origin");
        assertTrue(z16.mkdirs());

        boolean wrote = ProjectionExporter.write16BitOrigin(z16, "0001.tif", zOrigin);
        assertTrue(wrote);

        TiffStackLoader.LoadedStack stack = TiffStackLoader.load(z16);
        assertEquals(1, stack.frameCount());
        assertEquals(3, stack.width);
        assertEquals(2, stack.height);
        int idx = stack.frameToIdx.get(1); // trailing number in "z_origin_0001.tif" is 1
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(zOrigin[y][x], stack.pixels[idx][y][x],
                        "16-bit round-trip mismatch at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void origin32Bit_roundTripsThroughTiffStackLoader(@TempDir Path dir) throws Exception {
        int[][] zOrigin = {
                {0, 7, 70000},   // 70000 > uint16 — legal in 32-bit
                {123, 456, 999999},
        };
        File z32 = new File(dir.toFile(), "z_origin_32bit");
        assertTrue(z32.mkdirs());

        ProjectionExporter.write32BitOrigin(z32, "0003.tif", zOrigin);

        TiffStackLoader.LoadedStack stack = TiffStackLoader.load(z32);
        int idx = stack.frameToIdx.get(3); // last digit run in "z_origin_32bit_0003.tif" is 3
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(zOrigin[y][x], stack.pixels[idx][y][x],
                        "32-bit round-trip mismatch at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void origin16Bit_skipsWhenIndexExceedsUint16Range(@TempDir Path dir) throws Exception {
        int[][] zOrigin = {{0, 70000}}; // 70000 would silently wrap in uint16 — must refuse
        File z16 = new File(dir.toFile(), "z_origin");
        assertTrue(z16.mkdirs());

        boolean wrote = ProjectionExporter.write16BitOrigin(z16, "0001.tif", zOrigin);
        assertFalse(wrote);
        assertFalse(new File(z16, "z_origin_0001.tif").exists());
    }

    // ── Full seam: index TIFF + JSON → real loaders reproduce the Z value ──────

    @Test
    void seam_zOriginIndexMapsBackToCorrectZ_viaExtractorLoaders(@TempDir Path dir) throws Exception {
        // A tiny dataset: 3 z-layers at these physical Z values, and a 2x2 z-origin map
        // whose pixels reference layers 0, 1, and 2.
        double[] zValues = { -10.0, 0.5, 42.0 };
        int[][] zOrigin = {
                {0, 1},
                {2, 0},
        };

        File datasetOut = dir.toFile();
        File z16 = new File(datasetOut, "z_origin");
        assertTrue(z16.mkdirs());

        ProjectionExporter.writeMappings(datasetOut, zValues, true, true);
        ProjectionExporter.write16BitOrigin(z16, "0001.tif", zOrigin);

        // Consume exactly as the extractor plugin would.
        Map<Integer, Double> mapping =
                ZMappingLoader.load(new File(datasetOut, "z_layer_mapping.json").toPath());
        TiffStackLoader.LoadedStack stack = TiffStackLoader.load(z16);
        int idx = stack.frameToIdx.get(1);

        double[][] expectedZ = {
                { -10.0, 0.5 },
                {  42.0, -10.0 },
        };
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int index = stack.pixels[idx][y][x];
                assertEquals(expectedZ[y][x], mapping.get(index),
                        "Z lookup mismatch at (" + x + "," + y + ")");
            }
        }
    }
}
