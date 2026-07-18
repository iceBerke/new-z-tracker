package ztracker.core.topoj;

import ij.IJ;
import ztracker.core.ZAggregator;
import ztracker.core.extractor.ZExtractor.MethodCombo;
import ztracker.core.extractor.ZSampler;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;
import ztracker.model.ExtractionResult;
import ztracker.model.TrackData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tool 3 (TopoJ / direct-Z) counterpart to {@link ZExtractor}.
 *
 * <p>Orchestrates Z-coordinate extraction for every detection in a {@link TrackData},
 * sampling a 32-bit float TopoJ stack via {@link TopoJSampler}. The single structural
 * difference from {@link ZExtractor} is that a TopoJ pixel value <b>is</b> the physical
 * Z in µm — so there is no index → Z mapping step. Sampled values are aggregated as-is:
 *
 * <ol>
 *   <li>Sample Z values from the float stack ({@link TopoJSampler})</li>
 *   <li>Aggregate ({@link ZAggregator})</li>
 * </ol>
 *
 * <p>Consequently {@code numUnmapped} is always 0 and {@link ExtractionResult#STATUS_UNMAPPED_INDEX}
 * never arises; its direct-Z analogue {@link ExtractionResult#STATUS_NO_DATA} is used when
 * pixels were sampled but every one was NaN (a no-data pixel in the float map). Everything
 * else — the invalid-X/Y guard, the missing-frame vs. out-of-bounds disambiguation, the
 * per-run summary log, and the {@link #extractAll} cross-product with its {@code SINGLE_PIXEL}
 * dedup — mirrors {@link ZExtractor} exactly. It reuses {@link ZExtractor.MethodCombo} and
 * {@link ZExtractor#resolveComboOutputDir} as shared, stack-agnostic helpers.
 */
public class TopoJExtractor {

    private TopoJExtractor() {}

    /**
     * Runs extraction for all detections.
     *
     * @param track       parsed track data
     * @param stack       loaded 32-bit float TopoJ stack
     * @param frameOffset CSV-to-TIFF frame offset (from {@link FrameAligner})
     * @param sampling    sampling strategy
     * @param aggregation aggregation strategy
     * @param convention  whether integer X/Y mark a pixel's center or corner
     * @return extraction result parallel to {@code track}'s arrays
     */
    public static ExtractionResult extract(
            TrackData track,
            LoadedFloatStack stack,
            int frameOffset,
            ZSampler.Method sampling,
            ZAggregator.Method aggregation,
            ZSampler.PixelConvention convention) {

        int n = track.size();
        double[] z            = new double[n];
        double[] zStd         = new double[n];
        int[]    numSamples   = new int[n];
        int[]    numUnmapped  = new int[n]; // always 0 for direct-Z (no mapping)
        String[] sampleStatus = new String[n];

        int missingFrames = 0;
        int outOfBounds   = 0;
        int invalidXY     = 0;

        IJ.log(String.format(
                "[TopoJExtractor] Starting extraction: %d detections | %s + %s | %s | offset=%+d",
                n, sampling.label, aggregation.label, convention.label, frameOffset));

        for (int i = 0; i < n; i++) {
            if (i % 500 == 0) IJ.showProgress(i, n);

            // A NaN X or Y is never sampled — Math.round(NaN) == 0 in Java, so without this
            // check TopoJSampler would silently sample a phantom pixel at index 0 instead of
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

            // 1. Sample Z values from the stack (these ARE the Z coordinates — no mapping)
            double[] zSamples = TopoJSampler.sample(
                    stack, track.x[i], track.y[i],
                    track.frame[i], frameOffset,
                    radius, sampling, convention);

            if (zSamples.length == 0) {
                z[i]           = Double.NaN;
                zStd[i]        = Double.NaN;
                numSamples[i]  = 0;
                numUnmapped[i] = 0;
                // An empty sample array means either the TIFF frame itself is missing, or the
                // frame exists but the detection's footprint fell outside the image bounds —
                // distinct causes with different fixes, counted separately (see ZExtractor).
                if (stack.frameToIdx.containsKey(track.frame[i] + frameOffset)) {
                    outOfBounds++;
                    sampleStatus[i] = ExtractionResult.STATUS_OUT_OF_BOUNDS;
                } else {
                    missingFrames++;
                    sampleStatus[i] = ExtractionResult.STATUS_MISSING_FRAME;
                }
                continue;
            }

            // 2. Aggregate (ZAggregator NaN-filters, so a partly-NaN sample still aggregates)
            double zVal = ZAggregator.aggregate(zSamples, aggregation);

            z[i]           = zVal;
            zStd[i]        = ZAggregator.std(zSamples);
            numSamples[i]  = zSamples.length;
            numUnmapped[i] = 0;
            // zVal is only NaN here if every sampled pixel was itself NaN (a no-data pixel);
            // the direct-Z analogue of ZExtractor's STATUS_UNMAPPED_INDEX.
            sampleStatus[i] = Double.isNaN(zVal)
                    ? ExtractionResult.STATUS_NO_DATA
                    : ExtractionResult.STATUS_OK;
        }

        IJ.showProgress(1.0);

        int validCount = 0;
        for (double v : z) if (!Double.isNaN(v)) validCount++;

        IJ.log(String.format(
                "[TopoJExtractor] Done: %d / %d valid Z values | %d missing frames | "
                + "%d out-of-bounds positions | %d invalid X/Y",
                validCount, n, missingFrames, outOfBounds, invalidXY));

        return new ExtractionResult(z, zStd, numSamples, numUnmapped, sampleStatus,
                sampling.label, aggregation.label, convention.label,
                missingFrames, outOfBounds, invalidXY);
    }

    /**
     * Runs {@link #extract} once per combination of {@code samplingMethods} x
     * {@code aggregationMethods}, mirroring {@link ZExtractor#extractAll}.
     * {@link ZSampler.Method#SINGLE_PIXEL} samples exactly one pixel, so every
     * aggregation method yields an identical result — it is run only once.
     */
    public static List<MethodCombo> extractAll(
            TrackData track,
            LoadedFloatStack stack,
            int frameOffset,
            List<ZSampler.Method> samplingMethods,
            List<ZAggregator.Method> aggregationMethods,
            ZSampler.PixelConvention convention) {

        List<MethodCombo> results = new ArrayList<>();
        for (ZSampler.Method sampling : samplingMethods) {
            List<ZAggregator.Method> effectiveAggregations = (sampling == ZSampler.Method.SINGLE_PIXEL)
                    ? Collections.singletonList(aggregationMethods.get(0))
                    : aggregationMethods;
            for (ZAggregator.Method aggregation : effectiveAggregations) {
                ExtractionResult result = extract(
                        track, stack, frameOffset, sampling, aggregation, convention);
                results.add(new MethodCombo(sampling, aggregation, result));
            }
        }
        return results;
    }
}
