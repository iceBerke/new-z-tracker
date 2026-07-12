package ztracker.model;

/**
 * Stores the Z-extraction result for every detection in a {@link TrackData}.
 * Arrays are parallel to TrackData's arrays.
 */
public class ExtractionResult {

    /** {@link #sampleStatus} value for a detection with a valid Z value. */
    public static final String STATUS_OK = "";
    /** {@link #sampleStatus} value: the detection's TIFF frame isn't in the loaded stack. */
    public static final String STATUS_MISSING_FRAME = "missing frame";
    /** {@link #sampleStatus} value: the frame exists, but the sampled position (or its
     *  whole radius/corner footprint) fell outside the image bounds. */
    public static final String STATUS_OUT_OF_BOUNDS = "out of bounds";
    /** {@link #sampleStatus} value: pixels were sampled, but none had a Z-mapping entry. */
    public static final String STATUS_UNMAPPED_INDEX = "unmapped index";
    /** {@link #sampleStatus} value: the detection's X or Y itself is missing/unparseable
     *  (NaN) — sampling is never attempted for these (see {@link #invalidXYCount}). */
    public static final String STATUS_INVALID_XY = "invalid X/Y";

    /** Extracted Z value in physical units (µm). NaN if extraction failed. */
    public final double[] z;

    /** Standard deviation of Z samples within the sampling region. 0 if only 1 sample. */
    public final double[] zStd;

    /** Number of pixel samples used to compute z[i]. 0 if the frame was missing. */
    public final int[] numSamples;

    /** Number of sampled pixel indices that had no entry in the Z mapping. */
    public final int[] numUnmapped;

    /** Per-detection reason for a NaN {@link #z} value — one of the {@code STATUS_*}
     *  constants, or {@link #STATUS_OK} when z[i] is valid. Lets callers (export,
     *  logging) explain *why* a specific point was dropped instead of just that it was. */
    public final String[] sampleStatus;

    /** Sampling method label used (for logging / export naming). */
    public final String samplingMethod;

    /** Aggregation method label used. */
    public final String aggregationMethod;

    /** Detections whose TIFF frame doesn't exist in the loaded stack at all. */
    public final int missingFrameCount;

    /** Detections whose frame exists, but the sampled position (or its whole
     *  radius/corner footprint) fell outside the image bounds. Distinct from
     *  {@link #missingFrameCount} because the fix differs: a frame-offset problem
     *  vs. a bad detection coordinate. */
    public final int outOfBoundsCount;

    /** Detections whose X or Y itself is NaN — sampling was never attempted for these
     *  (rather than silently sampling pixel 0 via {@code Math.round(NaN) == 0}). */
    public final int invalidXYCount;

    public ExtractionResult(
            double[] z,
            double[] zStd,
            int[]    numSamples,
            int[]    numUnmapped,
            String[] sampleStatus,
            String   samplingMethod,
            String   aggregationMethod,
            int      missingFrameCount,
            int      outOfBoundsCount,
            int      invalidXYCount) {

        this.z                 = z;
        this.zStd              = zStd;
        this.numSamples        = numSamples;
        this.numUnmapped       = numUnmapped;
        this.sampleStatus      = sampleStatus;
        this.samplingMethod    = samplingMethod;
        this.aggregationMethod = aggregationMethod;
        this.missingFrameCount = missingFrameCount;
        this.outOfBoundsCount  = outOfBoundsCount;
        this.invalidXYCount    = invalidXYCount;
    }

    /** Number of detections with a valid (non-NaN) Z value. */
    public int countValid() {
        int count = 0;
        for (double v : z) if (!Double.isNaN(v)) count++;
        return count;
    }

    public int size() {
        return z.length;
    }
}
