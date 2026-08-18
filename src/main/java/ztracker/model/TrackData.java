package ztracker.model;

/**
 * Holds all per-position data loaded from a TrackMate CSV.
 * Each array is parallel (same length); index i refers to the same detection.
 *
 * Column mapping is stored as plain String fields so downstream code
 * never needs to re-parse headers.
 */
public class TrackData {

    // ── Parallel arrays (length = number of detections) ──────────────────────

    public final double[] x;
    public final double[] y;
    public final int[]    frame;
    /** Per-detection radius in pixels. Never NaN from {@code TrackCsvLoader}: when the CSV has
     *  no radius column, or a cell is blank/unparseable/NaN/infinite, the loader substitutes
     *  {@link #defaultRadius}. Use {@link #hasRadiusColumn()} to tell a real column from the
     *  default, rather than testing these values. */
    public final double[] radius;
    public final String[] trackId;   // String to handle both integer and string IDs

    // ── Metadata ─────────────────────────────────────────────────────────────

    /** Name of the column that was mapped to X. */
    public final String xColName;
    /** Name of the column that was mapped to Y. */
    public final String yColName;
    /** Name of the column that was mapped to Frame. */
    public final String frameColName;
    /** Name of the column that was mapped to Track ID. */
    public final String trackIdColName;
    /** Name of the radius column, or null if not present. */
    public final String radiusColName;
    /** Default radius in pixels used when radiusColName is null. */
    public final double  defaultRadius;

    public TrackData(
            double[] x,
            double[] y,
            int[]    frame,
            double[] radius,
            String[] trackId,
            String   xColName,
            String   yColName,
            String   frameColName,
            String   trackIdColName,
            String   radiusColName,
            double   defaultRadius) {

        if (x.length != y.length || x.length != frame.length
                || x.length != radius.length || x.length != trackId.length) {
            throw new IllegalArgumentException(
                    "All parallel arrays must have the same length.");
        }

        this.x              = x;
        this.y              = y;
        this.frame          = frame;
        this.radius         = radius;
        this.trackId        = trackId;
        this.xColName       = xColName;
        this.yColName       = yColName;
        this.frameColName   = frameColName;
        this.trackIdColName = trackIdColName;
        this.radiusColName  = radiusColName;
        this.defaultRadius  = defaultRadius;
    }

    /** Number of detections (rows). */
    public int size() {
        return x.length;
    }

    /** True if radius values were loaded from the CSV (not all NaN). */
    public boolean hasRadiusColumn() {
        return radiusColName != null;
    }
}
