package ztracker.model;

/**
 * Stores the Z-extraction result for every detection in a {@link TrackData}.
 * Arrays are parallel to TrackData's arrays.
 */
public class ExtractionResult {

    /** Extracted Z value in physical units (µm). NaN if extraction failed. */
    public final double[] z;

    /** Standard deviation of Z samples within the sampling region. 0 if only 1 sample. */
    public final double[] zStd;

    /** Number of pixel samples used to compute z[i]. 0 if the frame was missing. */
    public final int[] numSamples;

    /** Number of sampled pixel indices that had no entry in the Z mapping. */
    public final int[] numUnmapped;

    /** Sampling method label used (for logging / export naming). */
    public final String samplingMethod;

    /** Aggregation method label used. */
    public final String aggregationMethod;

    public ExtractionResult(
            double[] z,
            double[] zStd,
            int[]    numSamples,
            int[]    numUnmapped,
            String   samplingMethod,
            String   aggregationMethod) {

        this.z                 = z;
        this.zStd              = zStd;
        this.numSamples        = numSamples;
        this.numUnmapped       = numUnmapped;
        this.samplingMethod    = samplingMethod;
        this.aggregationMethod = aggregationMethod;
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
