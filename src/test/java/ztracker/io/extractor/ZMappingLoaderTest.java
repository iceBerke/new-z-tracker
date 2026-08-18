package ztracker.io.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZMappingLoaderTest {

    private static Path writeJson(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void load_simpleIntegerKeysAndDecimalValues(@TempDir Path dir) throws IOException {
        Path file = writeJson(dir, "mapping.json",
                "{\"0\": -600.0, \"1\": -599.0, \"2\": -598.0}");

        Map<Integer, Double> mapping = ZMappingLoader.load(file);

        assertEquals(3, mapping.size());
        assertEquals(-600.0, mapping.get(0));
        assertEquals(-599.0, mapping.get(1));
        assertEquals(-598.0, mapping.get(2));
    }

    @Test
    void load_negativeAndPositiveValues(@TempDir Path dir) throws IOException {
        Path file = writeJson(dir, "mapping.json",
                "{\"0\": -1.5, \"1\": 0.0, \"2\": 1.5}");

        Map<Integer, Double> mapping = ZMappingLoader.load(file);

        assertEquals(-1.5, mapping.get(0));
        assertEquals(0.0, mapping.get(1));
        assertEquals(1.5, mapping.get(2));
    }

    @Test
    void load_scientificNotationValues(@TempDir Path dir) throws IOException {
        Path file = writeJson(dir, "mapping.json",
                "{\"0\": 1.5e2, \"1\": -2.5E-3}");

        Map<Integer, Double> mapping = ZMappingLoader.load(file);

        assertEquals(150.0, mapping.get(0));
        assertEquals(-0.0025, mapping.get(1));
    }

    @Test
    void load_integerValuesWithoutDecimalPoint(@TempDir Path dir) throws IOException {
        Path file = writeJson(dir, "mapping.json", "{\"0\": 5, \"1\": -10}");

        Map<Integer, Double> mapping = ZMappingLoader.load(file);

        assertEquals(5.0, mapping.get(0));
        assertEquals(-10.0, mapping.get(1));
    }

    @Test
    void load_multiDigitKeysAreParsedAsInts(@TempDir Path dir) throws IOException {
        Path file = writeJson(dir, "mapping.json", "{\"10\": 1.0, \"200\": 2.0}");

        Map<Integer, Double> mapping = ZMappingLoader.load(file);

        assertEquals(2, mapping.size());
        assertEquals(1.0, mapping.get(10));
        assertEquals(2.0, mapping.get(200));
    }

    @Test
    void load_ignoresNonEntryContentAroundValidEntries(@TempDir Path dir) throws IOException {
        // The parser is a regex over the whole file content, not a real JSON parser -- it should
        // still find every "key": value pair regardless of surrounding whitespace/formatting.
        String json = "{\n"
                + "  \"0\": 0.0,\n"
                + "  \"1\": 0.5,\n"
                + "  \"2\": 1.0\n"
                + "}\n";
        Path file = writeJson(dir, "mapping.json", json);

        Map<Integer, Double> mapping = ZMappingLoader.load(file);

        assertEquals(3, mapping.size());
        assertEquals(0.5, mapping.get(1));
    }

    @Test
    void load_duplicateIndex_isRefused_namingTheIndexAndBothValues(@TempDir Path dir)
            throws IOException {
        // put() used to keep the LAST value silently, so this file parsed with index 0 at -1.5
        // and nothing said so. p10.4 (two TIFFs → one frame) and p10.9 (two timepoints → one
        // index) both refuse the analogous collision loudly; this was the last place a
        // duplicate key was still accepted.
        Path file = writeJson(dir, "dup.json",
                "{\"0\": -600.0, \"1\": -599.0, \"0\": -1.5}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ZMappingLoader.load(file));
        assertTrue(e.getMessage().contains("index 0"), e.getMessage());
        assertTrue(e.getMessage().contains("-600.0") && e.getMessage().contains("-1.5"),
                "both colliding values must be named: " + e.getMessage());
        assertFalse(e.getMessage().contains("index 1"),
                "only the colliding index should be listed: " + e.getMessage());
    }

    @Test
    void load_repeatedIndexWithTheSameValue_isStillRefused(@TempDir Path dir) throws IOException {
        // Harmless in effect, but still a file that says one layer twice — refused for the
        // same reason p10.4 refuses two files resolving to one frame whatever their content.
        Path file = writeJson(dir, "dupsame.json", "{\"0\": -600.0, \"0\": -600.0}");

        assertThrows(IllegalArgumentException.class, () -> ZMappingLoader.load(file));
    }

    @Test
    void load_strayEntryOutsideTheObject_collidesAndIsRefused(@TempDir Path dir)
            throws IOException {
        // The parser is a regex over the whole file, so text outside the object counts as an
        // entry. That tolerance is unchanged — but it can now collide, which the message says
        // outright rather than leaving the user to wonder why a "comment" broke the file.
        Path file = writeJson(dir, "stray.json",
                "{\"0\": -600.0, \"1\": -599.0}\nnote: layer \"0\": 12.0 was re-measured\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ZMappingLoader.load(file));
        assertTrue(e.getMessage().contains("whole file"),
                "the message must explain the whole-file scan: " + e.getMessage());
    }

    @Test
    void load_emptyMapping_throwsIllegalArgumentException(@TempDir Path dir) throws IOException {
        Path file = writeJson(dir, "empty.json", "{}");

        assertThrows(IllegalArgumentException.class, () -> ZMappingLoader.load(file));
    }

    @Test
    void load_noValidEntries_throwsIllegalArgumentException(@TempDir Path dir) throws IOException {
        // Non-numeric-key entries (e.g. a stray metadata field) don't match the pattern at all.
        Path file = writeJson(dir, "malformed.json", "{\"note\": \"not a mapping\"}");

        assertThrows(IllegalArgumentException.class, () -> ZMappingLoader.load(file));
    }
}