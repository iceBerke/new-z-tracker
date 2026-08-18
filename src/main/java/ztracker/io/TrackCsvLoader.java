package ztracker.io;

import ij.IJ;
import ztracker.model.TrackData;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses a TrackMate-style CSV file into a {@link TrackData} object.
 *
 * <p>TrackMate CSVs have a specific structure:
 * <pre>
 *   Row 0:  column headers
 *   Rows 1–3: metadata rows (units, etc.) — typically skipped
 *   Row 4+: actual data
 * </pre>
 * Both the header row index and the number of skip rows are configurable.
 *
 * <p>Column names are auto-detected from a priority list of known aliases
 * (case-insensitive). If auto-detection fails, the caller must supply
 * the column name explicitly via the {@link ColumnConfig}.
 */
public class TrackCsvLoader {

    // ── Known column name aliases (upper-cased for comparison) ───────────────

    private static final String[] X_ALIASES      = {"X", "POSITION_X", "X_POSITION"};
    private static final String[] Y_ALIASES      = {"Y", "POSITION_Y", "Y_POSITION"};
    private static final String[] FRAME_ALIASES  = {"FRAME", "T", "TIME", "TIMEPOINT", "SLICE N°"};
    private static final String[] TRACKID_ALIASES= {"TRACK_ID", "TRACKID", "ID", "TRACK N°"};
    private static final String[] RADIUS_ALIASES = {"RADIUS", "R", "SIZE"};

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Specifies CSV parsing options.
     * Use {@link #defaults()} for typical TrackMate files.
     */
    public static class CsvConfig {
        /** Zero-based row index of the header line. */
        public final int headerRow;
        /** Number of rows to skip immediately after the header (metadata rows). */
        public final int skipRowsAfterHeader;
        /** Default radius in pixels, used when no radius column is present. */
        public final double defaultRadius;

        public CsvConfig(int headerRow, int skipRowsAfterHeader, double defaultRadius) {
            this.headerRow            = headerRow;
            this.skipRowsAfterHeader  = skipRowsAfterHeader;
            this.defaultRadius        = defaultRadius;
        }

        public static CsvConfig defaults() {
            return new CsvConfig(0, 3, 3.5);
        }
    }

    /**
     * Holds the resolved column names after auto-detection.
     * Null fields indicate columns that were not found.
     */
    public static class ColumnConfig {
        public String xCol;
        public String yCol;
        public String frameCol;
        public String trackIdCol;
        public String radiusCol; // null if not present

        /** Returns true if all mandatory columns have been resolved. */
        public boolean isComplete() {
            return xCol != null && yCol != null && frameCol != null && trackIdCol != null;
        }
    }

    private TrackCsvLoader() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Auto-detects column names from the CSV header.
     *
     * @param csvPath path to the CSV file
     * @param config  parsing configuration
     * @return detected column mapping (may be incomplete — check {@link ColumnConfig#isComplete()})
     * @throws IOException on file read errors
     */
    public static ColumnConfig detectColumns(Path csvPath, CsvConfig config) throws IOException {
        String[] headers = readHeaders(csvPath, config.headerRow);
        ColumnConfig cols = new ColumnConfig();
        cols.xCol       = findColumn(headers, X_ALIASES);
        cols.yCol       = findColumn(headers, Y_ALIASES);
        cols.frameCol   = findColumn(headers, FRAME_ALIASES);
        cols.trackIdCol = findColumn(headers, TRACKID_ALIASES);
        cols.radiusCol  = findColumn(headers, RADIUS_ALIASES);
        return cols;
    }

    /**
     * Loads the CSV file into a {@link TrackData} object.
     *
     * @param csvPath path to the CSV file
     * @param config  parsing configuration
     * @param cols    resolved column mapping (must be complete)
     * @return loaded track data
     * @throws IOException              on file read errors
     * @throws IllegalArgumentException if column mapping is incomplete or data is malformed
     */
    public static TrackData load(Path csvPath, CsvConfig config, ColumnConfig cols)
            throws IOException {
        if (!cols.isComplete()) {
            throw new IllegalArgumentException(
                    "Column mapping is incomplete. Resolve all mandatory columns first.");
        }

        String[] headers = readHeaders(csvPath, config.headerRow);
        Map<String, Integer> headerIndex = buildHeaderIndex(headers);

        int xIdx      = requireIndex(headerIndex, cols.xCol);
        int yIdx      = requireIndex(headerIndex, cols.yCol);
        int frameIdx  = requireIndex(headerIndex, cols.frameCol);
        int trackIdx  = requireIndex(headerIndex, cols.trackIdCol);
        int radiusIdx = (cols.radiusCol != null) ? headerIndex.getOrDefault(cols.radiusCol, -1) : -1;

        List<Double> xList      = new ArrayList<>();
        List<Double> yList      = new ArrayList<>();
        List<Integer> frameList = new ArrayList<>();
        List<Double>  radList   = new ArrayList<>();
        List<String>  tidList   = new ArrayList<>();

        int dataStartLine = config.headerRow + 1 + config.skipRowsAfterHeader;
        int skippedNaN    = 0;
        int skippedBadXY  = 0;
        int skippedOther  = 0;
        int lineNum       = 0;
        // Rows whose radius cell stated no usable radius, so the default was substituted.
        // Unlike the three counters above these rows are KEPT — only the radius is replaced.
        int substitutedRadius = 0;
        List<String> badRadiusCells = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum <= dataStartLine) continue;  // skip header + metadata rows
                if (line.trim().isEmpty())    continue;

                String[] parts = line.split(",", -1);

                // Skip rows where frame or track ID are empty/NaN (TrackMate quirk)
                if (isBlankOrNaN(safeGet(parts, frameIdx))
                        || isBlankOrNaN(safeGet(parts, trackIdx))) {
                    skippedNaN++;
                    continue;
                }

                // Skip rows where X or Y is blank, malformed, or literally "NaN" text —
                // counted and logged separately, same as the Frame/Track_ID skip above,
                // rather than being silently absorbed by the catch-all below (which
                // previously swallowed this case with no visibility at all).
                Double x = parseCoordinate(safeGet(parts, xIdx));
                Double y = parseCoordinate(safeGet(parts, yIdx));
                if (x == null || y == null) {
                    skippedBadXY++;
                    continue;
                }

                try {
                    int    frame = (int) Double.parseDouble(safeGet(parts, frameIdx).trim());
                    String tid   = safeGet(parts, trackIdx).trim();

                    // A radius cell that states no usable radius falls back to the default —
                    // but never silently: substituting a value the CSV did not ask for is
                    // itself a quiet failure, so each one is counted and the first few are
                    // named with their line number and raw text so the cell can be found.
                    double rad = config.defaultRadius;
                    if (radiusIdx >= 0) {
                        String rawRadius = safeGet(parts, radiusIdx);
                        Double parsedRadius = parseRadius(rawRadius);
                        if (parsedRadius != null) {
                            rad = parsedRadius;
                        } else {
                            substitutedRadius++;
                            if (badRadiusCells.size() < MAX_BAD_RADIUS_LISTED) {
                                badRadiusCells.add("line " + lineNum + ": \""
                                        + rawRadius.trim() + "\"");
                            }
                        }
                    }

                    xList.add(x);
                    yList.add(y);
                    frameList.add(frame);
                    radList.add(rad);
                    tidList.add(tid);
                } catch (NumberFormatException ignored) {
                    skippedOther++;
                }
            }
        }

        if (skippedNaN > 0) {
            IJ.log("[TrackCsvLoader] Skipped " + skippedNaN + " rows with empty Frame or Track_ID.");
        }
        if (substitutedRadius > 0) {
            String examples = badRadiusCells.toString();
            if (substitutedRadius > badRadiusCells.size()) {
                examples = examples.substring(0, examples.length() - 1)
                        + ", …and " + (substitutedRadius - badRadiusCells.size()) + " more]";
            }
            IJ.log("[TrackCsvLoader] Used the default radius (" + config.defaultRadius
                    + " px) for " + substitutedRadius + " row(s) whose radius cell was blank,"
                    + " unparseable, NaN, infinite, or not positive — the rows themselves were"
                    + " kept: " + examples);
        }
        if (skippedBadXY > 0) {
            IJ.log("[TrackCsvLoader] Skipped " + skippedBadXY + " rows with missing/unparseable X or Y.");
        }
        if (skippedOther > 0) {
            IJ.log("[TrackCsvLoader] Skipped " + skippedOther + " rows with other malformed data.");
        }
        IJ.log(String.format("[TrackCsvLoader] Loaded %d detections from %s",
                xList.size(), csvPath.getFileName()));

        return new TrackData(
                toDoubleArray(xList),
                toDoubleArray(yList),
                toIntArray(frameList),
                toDoubleArray(radList),
                tidList.toArray(new String[0]),
                cols.xCol, cols.yCol, cols.frameCol, cols.trackIdCol,
                cols.radiusCol,
                config.defaultRadius
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String[] readHeaders(Path csvPath, int headerRow) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                if (lineNum == headerRow) return line.split(",", -1);
                lineNum++;
            }
        }
        throw new IOException("Header row " + headerRow + " not found in CSV.");
    }

    private static Map<String, Integer> buildHeaderIndex(String[] headers) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            index.put(headers[i].trim(), i);
        }
        return index;
    }

    /** Returns the first header that matches any alias (case-insensitive), or null. */
    private static String findColumn(String[] headers, String[] aliases) {
        Set<String> aliasSet = new HashSet<>();
        for (String a : aliases) aliasSet.add(a.toUpperCase(Locale.ROOT));

        for (String h : headers) {
            if (aliasSet.contains(h.trim().toUpperCase(Locale.ROOT))) return h.trim();
        }
        return null;
    }

    private static int requireIndex(Map<String, Integer> index, String colName) {
        Integer i = index.get(colName);
        if (i == null) {
            throw new IllegalArgumentException("Column not found in CSV: '" + colName + "'");
        }
        return i;
    }

    /** How many offending radius cells the log names before summarising the rest. */
    private static final int MAX_BAD_RADIUS_LISTED = 5;

    private static String safeGet(String[] parts, int idx) {
        return (idx >= 0 && idx < parts.length) ? parts[idx] : "";
    }

    private static boolean isBlankOrNaN(String s) {
        if (s == null || s.trim().isEmpty()) return true;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.equals("NAN") || t.equals("NA") || t.equals("NULL");
    }

    /**
     * Parses a radius cell, or {@code null} when it does not state a usable radius — blank,
     * unparseable, {@code NaN}, infinite, or not <b>positive</b>. The caller substitutes the
     * default and counts the substitution.
     *
     * <p>The NaN and infinity checks are explicit for the same reason {@link #parseCoordinate}
     * needs them: {@code Double.parseDouble("NaN")} and {@code ("Infinity")} both <b>succeed</b>
     * in Java, so such a cell sails past the {@code catch} and becomes a real
     * {@code NaN}/{@code Infinity} radius. That guard was applied to X/Y and missed here.
     *
     * <p>These misbehave differently downstream and the infinite case is the serious one.
     * A {@code NaN} radius makes {@code ZSampler.sampleRadius} take zero samples — the detection
     * is then reported as <em>out of bounds</em>, sending the user to check coordinates or the
     * frame offset when the fault is a radius cell. An <b>infinite</b> radius is worse than
     * misleading: {@code (int) Math.ceil(Infinity)} is {@code Integer.MAX_VALUE}, so the disk
     * loop would run about 1.8e19 iterations and hang Fiji with no error and no progress.
     *
     * <p><b>Non-positive values are folded in too</b>, because leaving them out left an
     * arbitrary split: {@code -1.0} and below make the disk loop never run (zero samples, then
     * misreported as out of bounds), while {@code -0.4} and {@code 0} ceil to 0 and quietly
     * sample a single pixel — behaving like {@code SINGLE_PIXEL} without saying so. The second
     * is the worse of the two precisely because it <em>succeeds</em>. Anything that is not a
     * positive finite number is treated as stating no radius at all.
     *
     * <p>Note this does <b>not</b> cover a large <em>finite</em> radius, which produces the same
     * hang ({@code 1e300} also ceils to {@code Integer.MAX_VALUE}) — that needs a bound at the
     * sampling site rather than a parse guard, and is deliberately out of scope here.
     */
    private static Double parseRadius(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t);
            return (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses an X/Y coordinate; returns null if blank, malformed, or literally "NaN"
     *  ({@code Double.parseDouble("NaN")} succeeds in Java, so that case needs an
     *  explicit check rather than relying on the parse to throw). */
    private static Double parseCoordinate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t);
            return Double.isNaN(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
