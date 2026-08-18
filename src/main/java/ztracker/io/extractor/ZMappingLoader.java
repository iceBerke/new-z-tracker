package ztracker.io.extractor;

import ij.IJ;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a Z-layer mapping JSON file into an integer-keyed map.
 *
 * <p>Expected format (as produced by the Python pipeline):
 * <pre>{"0": -600.0, "1": -599.0, "2": -598.0, ...}</pre>
 *
 * <p>Parsing is done with a simple regex rather than a JSON library,
 * so there are zero external dependencies. The regex captures every
 * {@code "key": value} pair regardless of the actual index values or
 * Z coordinate range, making it dataset-agnostic.
 */
public class ZMappingLoader {

    // Matches: "integer_key" : numeric_value  (handles negatives, decimals, sci notation)
    private static final Pattern ENTRY_PATTERN =
            Pattern.compile("\"(\\d+)\"\\s*:\\s*(-?[\\d.]+(?:[eE][+-]?\\d+)?)");

    private ZMappingLoader() {}

    /**
     * Loads and parses the JSON mapping file.
     *
     * @param jsonPath path to the JSON file
     * @return map from Z-layer index (int) to Z coordinate (double)
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if no valid entries are found
     */
    public static Map<Integer, Double> load(Path jsonPath) throws IOException {
        String content = new String(Files.readAllBytes(jsonPath));
        Map<Integer, Double> mapping = new HashMap<>();
        List<String> duplicates = new ArrayList<>();

        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            int    index   = Integer.parseInt(matcher.group(1));
            double zValue  = Double.parseDouble(matcher.group(2));
            // put() would silently keep the LAST value for a repeated index, leaving the
            // mapping quietly wrong with no way to tell. Collect every collision instead and
            // refuse the file below, naming both values — the same treatment p10.4 gives two
            // TIFFs resolving to one frame number and p10.9 gives two timepoints claiming one
            // index. This was the last place a duplicate key was still accepted silently.
            Double previous = mapping.put(index, zValue);
            if (previous != null) {
                duplicates.add("index " + index + " ← " + previous + " and " + zValue);
            }
        }

        if (mapping.isEmpty()) {
            throw new IllegalArgumentException(
                    "No valid entries found in Z-mapping JSON: " + jsonPath);
        }

        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "These Z-mapping entries claim the same index: " + duplicates
                    + "\nThe mapping is index → Z, so a repeated index gives one layer two"
                    + " depths and the file does not say which is meant."
                    + "\nNote this parser is a regex over the whole file rather than a JSON"
                    + " parser, so a stray \"<index>\": <value> anywhere — including outside"
                    + " the main object — counts as an entry and can collide with a real one."
                    + "\nRemove the duplicate entries and re-run: " + jsonPath);
        }

        int    minIdx = mapping.keySet().stream().mapToInt(Integer::intValue).min().orElse(0);
        int    maxIdx = mapping.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        double minZ   = mapping.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxZ   = mapping.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        IJ.log(String.format(
                "[ZMappingLoader] Loaded %d entries | index %d–%d | Z %.1f–%.1f µm",
                mapping.size(), minIdx, maxIdx, minZ, maxZ));

        return mapping;
    }
}
