package ztracker.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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