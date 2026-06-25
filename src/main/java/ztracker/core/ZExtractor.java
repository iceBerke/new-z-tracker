package ztracker.core;

import ij.IJ;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.util.Map;

/**
 * Orchestrates Z-coordinate extraction for every detection in a {@link TrackData}.
 *
 * <p>For each detection:
 * <ol>
 *   <li>Sample raw pixel indices from the TIFF stack ({@link ZSampler})</li>
 *   <li>Convert indices → Z coordinates via the JSON mapping</li>
 *   <li>Aggregate Z values ({@link ZAggregator})</li>
 * </ol>
 *
 * <p>Progress is reported via {@code IJ.showProgress()}.
 */
public class ZExtractor {

    private ZExtractor() {}

    /**
     * Runs extraction for all detections.
     *
     * @param track      parsed track data
     * @param stack      loaded TIFF stack
     * @param zMapping   index → Z coordinate map (from {@link ztracker.io.ZMappingLoader})
     * @param frameOffset CSV-to-TIFF frame offset (from {@link FrameAligner})
     * @param sampling   sampling strategy
     * @param aggregation aggregation strategy
     * @return extraction result parallel to {@code track}'s arrays
     */
    public static ExtractionResult extract(
            TrackData track,
            LoadedStack stack,
            Map<Integer, Double> zMapping,
            int frameOffset,
            ZSampler.Method sampling,
            ZAggregator.Method aggregation) {

        int n = track.size();
        double[] z          = new double[n];
        double[] zStd       = new double[n];
        int[]    numSamples = new int[n];
        int[]    numUnmapped= new int[n];

        int missingFrames = 0;

        IJ.log(String.format(
                "[ZExtractor] Starting extraction: %d detections | %s + %s | offset=%+d",
                n, sampling.label, aggregation.label, frameOffset));

        for (int i = 0; i < n; i++) {
            if (i % 500 == 0) IJ.showProgress(i, n);

            double radius = track.radius[i];

            // 1. Sample raw indices from the stack
            double[] indices = ZSampler.sample(
                    stack, track.x[i], track.y[i],
                    track.frame[i], frameOffset,
                    radius, sampling);

            if (indices.length == 0) {
                z[i]           = Double.NaN;
                zStd[i]        = Double.NaN;
                numSamples[i]  = 0;
                numUnmapped[i] = 0;
                missingFrames++;
                continue;
            }

            // 2. Convert indices to Z values
            double[] zSamples  = indicesToZ(indices, zMapping);
            int unmapped       = countNaN(zSamples);

            // 3. Aggregate (aggregate() correctly returns the lone value for length-1 input)
            double zVal = ZAggregator.aggregate(zSamples, aggregation);

            z[i]           = zVal;
            zStd[i]        = ZAggregator.std(zSamples);
            numSamples[i]  = indices.length;
            numUnmapped[i] = unmapped;
        }

        IJ.showProgress(1.0);

        // Summary log
        int validCount = 0;
        for (double v : z) if (!Double.isNaN(v)) validCount++;

        IJ.log(String.format(
                "[ZExtractor] Done: %d / %d valid Z values | %d missing frames",
                validCount, n, missingFrames));

        return new ExtractionResult(z, zStd, numSamples, numUnmapped,
                sampling.label, aggregation.label);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Converts raw pixel index values to Z coordinates using the mapping. */
    private static double[] indicesToZ(double[] indices, Map<Integer, Double> zMapping) {
        double[] zValues = new double[indices.length];
        for (int i = 0; i < indices.length; i++) {
            int idx = (int) Math.round(indices[i]);
            Double zVal = zMapping.get(idx);
            zValues[i] = (zVal != null) ? zVal : Double.NaN;
        }
        return zValues;
    }

    private static int countNaN(double[] arr) {
        int count = 0;
        for (double v : arr) if (Double.isNaN(v)) count++;
        return count;
    }
}
