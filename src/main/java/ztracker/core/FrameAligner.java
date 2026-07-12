package ztracker.core;

import ij.IJ;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.model.TrackData;

import java.util.*;

/**
 * Computes and validates the integer offset that maps CSV frame numbers
 * to TIFF file frame numbers.
 *
 * <p>The offset is ADDED to each CSV frame: {@code tiffFrame = csvFrame + offset}.
 *
 * <p>Example: if the CSV uses 0-based indexing but TIFFs start at 1, offset = +1.
 */
public class FrameAligner {

    // ── Public result ─────────────────────────────────────────────────────────

    public static class AlignmentReport {
        /** The validated offset to use for extraction. */
        public final int offset;
        /** Number of unique CSV frames that map to a missing TIFF after offset. */
        public final int missingFrameCount;
        /** Total unique CSV frames. */
        public final int totalUniqueFrames;
        /** Suggested offset detected from indexing mismatch (may be 0 if no hint). */
        public final int suggestedOffset;
        /** Offset implied by the LAST frames (tiffLast - csvLast). */
        public final int suggestedOffsetFromEnd;
        /**
         * True when the start-anchored and end-anchored offsets agree, i.e. the CSV
         * and TIFF spans line up as a single constant shift. When false, the ranges
         * have different lengths and any single suggested offset is only a guess.
         */
        public final boolean rangesConsistent;

        AlignmentReport(int offset, int missingFrameCount,
                        int totalUniqueFrames, int suggestedOffset,
                        int suggestedOffsetFromEnd, boolean rangesConsistent) {
            this.offset                 = offset;
            this.missingFrameCount      = missingFrameCount;
            this.totalUniqueFrames      = totalUniqueFrames;
            this.suggestedOffset        = suggestedOffset;
            this.suggestedOffsetFromEnd = suggestedOffsetFromEnd;
            this.rangesConsistent       = rangesConsistent;
        }

        /** Fraction of CSV frames that are unmapped after applying offset (0–1). */
        public double missingFraction() {
            return totalUniqueFrames == 0 ? 0.0
                    : (double) missingFrameCount / totalUniqueFrames;
        }

        public boolean hasWarning() {
            return missingFrameCount > 0;
        }
    }

    private FrameAligner() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Detects the likely offset by comparing the first CSV frame to the first
     * TIFF frame. Returns 0 if they already match.
     *
     * @param track track data (used for its frame array)
     * @param stack loaded TIFF stack
     * @return suggested offset
     */
    public static int suggestOffset(TrackData track, LoadedStack stack) {
        int csvFirst  = Arrays.stream(track.frame).min().orElse(0);
        int tiffFirst = stack.firstFrame();

        if (csvFirst == tiffFirst) return 0;
        // Classic 0-vs-1 mismatch — suggest correcting automatically
        return tiffFirst - csvFirst;
    }

    /**
     * Offset implied by aligning the LAST CSV frame with the LAST TIFF frame:
     * {@code tiffLast - csvLast}. Compared against {@link #suggestOffset} this
     * detects whether the CSV and TIFF spans have the same length (a clean
     * constant shift) or diverge (dropped/extra frames, truncated stack).
     *
     * @param track track data (used for its frame array)
     * @param stack loaded TIFF stack
     * @return end-anchored offset
     */
    public static int suggestOffsetFromEnd(TrackData track, LoadedStack stack) {
        int csvLast  = Arrays.stream(track.frame).max().orElse(0);
        int tiffLast = stack.lastFrame();
        return tiffLast - csvLast;
    }

    /**
     * Validates the given offset and returns a full alignment report.
     * Logs a summary to the Fiji log window.
     *
     * @param track  track data
     * @param stack  loaded TIFF stack
     * @param offset candidate offset to validate
     * @return alignment report
     */
    public static AlignmentReport validate(TrackData track, LoadedStack stack, int offset) {
        Set<Integer> availableFrames = stack.frameToIdx.keySet();

        // Collect unique CSV frames
        SortedSet<Integer> uniqueCsvFrames = new TreeSet<>();
        for (int f : track.frame) uniqueCsvFrames.add(f);

        int missing = 0;
        for (int csvFrame : uniqueCsvFrames) {
            if (!availableFrames.contains(csvFrame + offset)) missing++;
        }

        int suggested        = suggestOffset(track, stack);
        int suggestedFromEnd = suggestOffsetFromEnd(track, stack);
        boolean consistent   = suggested == suggestedFromEnd;

        logReport(uniqueCsvFrames, stack, offset, missing, suggested);
        if (!consistent) {
            IJ.log(String.format(
                    "[FrameAligner] ⚠ Range mismatch: start implies offset %+d but end implies %+d. "
                    + "CSV and TIFF spans differ in length — verify alignment.",
                    suggested, suggestedFromEnd));
        }

        return new AlignmentReport(offset, missing, uniqueCsvFrames.size(),
                suggested, suggestedFromEnd, consistent);
    }

    /**
     * Builds a short alignment preview string (3 sample frames) for display in the GUI.
     *
     * @param track  track data
     * @param stack  loaded TIFF stack
     * @param offset offset to preview
     * @return multi-line preview string
     */
    public static String buildPreview(TrackData track, LoadedStack stack, int offset) {
        Set<Integer> available = stack.frameToIdx.keySet();

        // Pick first, middle, last unique CSV frames
        SortedSet<Integer> unique = new TreeSet<>();
        for (int f : track.frame) unique.add(f);
        List<Integer> sample = pickPreviewFrames(new ArrayList<>(unique));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("TIFF files: frames %d – %d%n",
                stack.firstFrame(), stack.lastFrame()));
        sb.append(String.format("CSV frames: %d – %d (offset = %+d)%n",
                unique.first(), unique.last(), offset));
        sb.append(String.format("─────────────────────────────────%n"));

        for (int csvF : sample) {
            int tiffF  = csvF + offset;
            String tag = available.contains(tiffF) ? "✓" : "✗ MISSING";
            sb.append(String.format("  CSV %5d  →  TIFF %5d   %s%n", csvF, tiffF, tag));
        }

        int startOffset = suggestOffset(track, stack);
        int endOffset   = suggestOffsetFromEnd(track, stack);
        if (startOffset != endOffset) {
            sb.append(String.format(
                    "%n  ⚠ Start implies offset %+d but end implies %+d —%n"
                    + "    CSV and TIFF spans differ in length; verify alignment.%n",
                    startOffset, endOffset));
        }

        int missing = 0;
        for (int f : unique) if (!available.contains(f + offset)) missing++;

        if (missing > 0) {
            sb.append(String.format("%n  ⚠ %d / %d CSV frames map to a missing TIFF%n",
                    missing, unique.size()));
        } else {
            sb.append(String.format("%n  ✓ All %d CSV frames map to an existing TIFF%n",
                    unique.size()));
        }
        return sb.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static List<Integer> pickPreviewFrames(List<Integer> sorted) {
        if (sorted.size() <= 3) return sorted;
        return Arrays.asList(
                sorted.get(0),
                sorted.get(sorted.size() / 2),
                sorted.get(sorted.size() - 1));
    }

    private static void logReport(SortedSet<Integer> uniqueCsv, LoadedStack stack,
                                   int offset, int missing, int suggested) {
        IJ.log(String.format(
                "[FrameAligner] offset=%+d | CSV frames %d–%d | TIFF frames %d–%d | missing=%d/%d",
                offset,
                uniqueCsv.first(), uniqueCsv.last(),
                stack.firstFrame(), stack.lastFrame(),
                missing, uniqueCsv.size()));

        if (missing > 0) {
            IJ.log(String.format(
                    "[FrameAligner] ⚠ %.1f%% of CSV frames unmapped. Suggested offset: %+d",
                    100.0 * missing / uniqueCsv.size(), suggested));
        }
    }
}
