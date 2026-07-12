package ztracker.core;

import ij.IJ;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.util.ArrayList;
import java.util.List;
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
        double[] z            = new double[n];
        double[] zStd         = new double[n];
        int[]    numSamples   = new int[n];
        int[]    numUnmapped  = new int[n];
        String[] sampleStatus = new String[n];

        int missingFrames = 0;
        int outOfBounds   = 0;
        int invalidXY     = 0;

        IJ.log(String.format(
                "[ZExtractor] Starting extraction: %d detections | %s + %s | offset=%+d",
                n, sampling.label, aggregation.label, frameOffset));

        for (int i = 0; i < n; i++) {
            if (i % 500 == 0) IJ.showProgress(i, n);

            // A NaN X or Y is never sampled — Math.round(NaN) == 0 in Java, so without this
            // check ZSampler would silently sample a phantom pixel at index 0 instead of
            // failing, producing a bogus "valid" Z for a detection with no real position.
            if (Double.isNaN(track.x[i]) || Double.isNaN(track.y[i])) {
                z[i]            = Double.NaN;
                zStd[i]         = Double.NaN;
                numSamples[i]   = 0;
                numUnmapped[i]  = 0;
                sampleStatus[i] = ExtractionResult.STATUS_INVALID_XY;
                invalidXY++;
                continue;
            }

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
                // An empty sample array means either the TIFF frame itself is missing,
                // or the frame exists but the detection's (x,y) — or its whole radius/
                // corner footprint — falls outside the image bounds. These are distinct
                // causes with different fixes (frame offset vs. bad detection coordinates),
                // so they're counted and logged separately rather than lumped together.
                if (stack.frameToIdx.containsKey(track.frame[i] + frameOffset)) {
                    outOfBounds++;
                    sampleStatus[i] = ExtractionResult.STATUS_OUT_OF_BOUNDS;
                } else {
                    missingFrames++;
                    sampleStatus[i] = ExtractionResult.STATUS_MISSING_FRAME;
                }
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
            // zVal is only NaN here if every sampled index lacked a Z-mapping entry
            // (partial unmapped still yields a valid aggregate over the remaining samples).
            sampleStatus[i] = Double.isNaN(zVal)
                    ? ExtractionResult.STATUS_UNMAPPED_INDEX
                    : ExtractionResult.STATUS_OK;
        }

        IJ.showProgress(1.0);

        // Summary log
        int validCount = 0;
        for (double v : z) if (!Double.isNaN(v)) validCount++;

        IJ.log(String.format(
                "[ZExtractor] Done: %d / %d valid Z values | %d missing frames | "
                + "%d out-of-bounds positions | %d invalid X/Y",
                validCount, n, missingFrames, outOfBounds, invalidXY));

        return new ExtractionResult(z, zStd, numSamples, numUnmapped, sampleStatus,
                sampling.label, aggregation.label, missingFrames, outOfBounds, invalidXY);
    }

    /**
     * Runs {@link #extract} once per combination of {@code samplingMethods} x
     * {@code aggregationMethods} (in that nesting order), e.g. for a Step-5 "All"
     * selection on either or both axes.
     */
    public static List<MethodCombo> extractAll(
            TrackData track,
            LoadedStack stack,
            Map<Integer, Double> zMapping,
            int frameOffset,
            List<ZSampler.Method> samplingMethods,
            List<ZAggregator.Method> aggregationMethods) {

        List<MethodCombo> results = new ArrayList<>();
        for (ZSampler.Method sampling : samplingMethods) {
            for (ZAggregator.Method aggregation : aggregationMethods) {
                ExtractionResult result = extract(
                        track, stack, zMapping, frameOffset, sampling, aggregation);
                results.add(new MethodCombo(sampling, aggregation, result));
            }
        }
        return results;
    }

    /** One (sampling, aggregation) combination paired with its extraction result. */
    public static class MethodCombo {
        public final ZSampler.Method    sampling;
        public final ZAggregator.Method aggregation;
        public final ExtractionResult   result;

        public MethodCombo(ZSampler.Method sampling, ZAggregator.Method aggregation,
                            ExtractionResult result) {
            this.sampling    = sampling;
            this.aggregation = aggregation;
            this.result      = result;
        }
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
