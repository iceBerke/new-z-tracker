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
 *
 * <p>The offset is a single constant that applies to every detection, so the
 * correctness test is simply: does {@code frame + offset} land on an existing
 * TIFF for every detection? A track that only covers part of the recording is
 * perfectly normal and does NOT indicate a bad offset — so validation reports
 * alignment <b>per track</b> (span + how many detections map to a missing TIFF)
 * and leaves the final call to the user, alongside the suggested offset.
 */
public class FrameAligner {

    // ── Public results ────────────────────────────────────────────────────────

    /** Alignment summary for a single track under a given offset. */
    public static class TrackAlignment {
        public final String trackId;
        /** Track's first/last CSV frame (its span within the recording). */
        public final int firstFrame;
        public final int lastFrame;
        /** Number of detections in this track. */
        public final int detectionCount;
        /** Detections whose {@code frame + offset} has no TIFF. */
        public final int missingCount;

        TrackAlignment(String trackId, int firstFrame, int lastFrame,
                       int detectionCount, int missingCount) {
            this.trackId        = trackId;
            this.firstFrame     = firstFrame;
            this.lastFrame      = lastFrame;
            this.detectionCount = detectionCount;
            this.missingCount   = missingCount;
        }

        /** True when every detection in this track maps to an existing TIFF. */
        public boolean fullyMapped() { return missingCount == 0; }
    }

    public static class AlignmentReport {
        /** The validated offset to use for extraction. */
        public final int offset;
        /** Number of unique CSV frames that map to a missing TIFF after offset. */
        public final int missingFrameCount;
        /** Total unique CSV frames. */
        public final int totalUniqueFrames;
        /** Suggested offset detected from indexing mismatch (may be 0 if no hint). */
        public final int suggestedOffset;
        /** Per-track alignment, ordered by first frame then track id. */
        public final List<TrackAlignment> perTrack;

        AlignmentReport(int offset, int missingFrameCount, int totalUniqueFrames,
                        int suggestedOffset, List<TrackAlignment> perTrack) {
            this.offset            = offset;
            this.missingFrameCount = missingFrameCount;
            this.totalUniqueFrames = totalUniqueFrames;
            this.suggestedOffset   = suggestedOffset;
            this.perTrack          = perTrack;
        }

        /** Fraction of CSV frames that are unmapped after applying offset (0–1). */
        public double missingFraction() {
            return totalUniqueFrames == 0 ? 0.0
                    : (double) missingFrameCount / totalUniqueFrames;
        }

        public boolean hasWarning() {
            return missingFrameCount > 0;
        }

        /** Tracks that have at least one detection mapping to a missing TIFF. */
        public List<TrackAlignment> problemTracks() {
            List<TrackAlignment> out = new ArrayList<>();
            for (TrackAlignment ta : perTrack) if (!ta.fullyMapped()) out.add(ta);
            return out;
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
     * Computes per-track alignment under {@code offset}: each track's frame span
     * and how many of its detections map to a missing TIFF. Tracks are ordered by
     * first frame, then track id.
     *
     * @param track  track data
     * @param stack  loaded TIFF stack
     * @param offset offset to evaluate
     * @return per-track alignment summaries
     */
    public static List<TrackAlignment> perTrackAlignment(TrackData track, LoadedStack stack, int offset) {
        Set<Integer> available = stack.frameToIdx.keySet();

        // Group detection indices by track id, preserving first-seen order isn't
        // needed — we sort at the end.
        Map<String, int[]> agg = new HashMap<>(); // trackId -> {first, last, count, missing}
        for (int i = 0; i < track.frame.length; i++) {
            String id = track.trackId[i];
            int f     = track.frame[i];
            boolean missing = !available.contains(f + offset);

            int[] a = agg.get(id);
            if (a == null) {
                agg.put(id, new int[]{f, f, 1, missing ? 1 : 0});
            } else {
                if (f < a[0]) a[0] = f;
                if (f > a[1]) a[1] = f;
                a[2]++;
                if (missing) a[3]++;
            }
        }

        List<TrackAlignment> out = new ArrayList<>(agg.size());
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            int[] a = e.getValue();
            out.add(new TrackAlignment(e.getKey(), a[0], a[1], a[2], a[3]));
        }
        out.sort(Comparator.comparingInt((TrackAlignment t) -> t.firstFrame)
                .thenComparing(t -> t.trackId));
        return out;
    }

    /**
     * Validates the given offset and returns a full alignment report.
     * Logs a summary plus a per-track breakdown to the Fiji log window.
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

        int suggested = suggestOffset(track, stack);
        List<TrackAlignment> perTrack = perTrackAlignment(track, stack, offset);

        logReport(uniqueCsvFrames, stack, offset, missing, suggested);
        logPerTrack(perTrack, offset);

        return new AlignmentReport(offset, missing, uniqueCsvFrames.size(), suggested, perTrack);
    }

    /**
     * Builds a short alignment preview string for display in the GUI: a few sample
     * frame mappings, a per-track coverage summary (with any problem tracks called
     * out), and the overall pass/fail line.
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

        appendPerTrackSummary(sb, perTrackAlignment(track, stack, offset), offset);

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

    /** Max number of problem tracks to enumerate in the GUI preview. */
    private static final int MAX_PROBLEM_TRACKS_SHOWN = 5;

    private static void appendPerTrackSummary(StringBuilder sb, List<TrackAlignment> perTrack, int offset) {
        if (perTrack.isEmpty()) return;

        int fully = 0;
        int minSpan = Integer.MAX_VALUE, maxSpan = Integer.MIN_VALUE;
        for (TrackAlignment ta : perTrack) {
            if (ta.fullyMapped()) fully++;
            int span = ta.lastFrame - ta.firstFrame + 1;
            if (span < minSpan) minSpan = span;
            if (span > maxSpan) maxSpan = span;
        }
        int withMissing = perTrack.size() - fully;

        sb.append(String.format("%n─────────────────────────────────%n"));
        sb.append(String.format("Per-track (offset %+d): %d tracks | %d fully mapped, %d with missing frames%n",
                offset, perTrack.size(), fully, withMissing));
        sb.append(String.format("Track spans: %d – %d frames (shorter tracks are normal, not a bad offset)%n",
                minSpan, maxSpan));

        if (withMissing > 0) {
            int shown = 0;
            for (TrackAlignment ta : perTrack) {
                if (ta.fullyMapped()) continue;
                if (shown++ >= MAX_PROBLEM_TRACKS_SHOWN) break;
                sb.append(String.format("  ⚠ Track %s: frames %d–%d (%d det.), %d map to missing TIFF%n",
                        ta.trackId, ta.firstFrame, ta.lastFrame, ta.detectionCount, ta.missingCount));
            }
            int remaining = withMissing - Math.min(withMissing, MAX_PROBLEM_TRACKS_SHOWN);
            if (remaining > 0) {
                sb.append(String.format("  … and %d more track(s) with missing frames (see Fiji log)%n", remaining));
            }
        }
    }

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

    private static void logPerTrack(List<TrackAlignment> perTrack, int offset) {
        int withMissing = 0;
        for (TrackAlignment ta : perTrack) if (!ta.fullyMapped()) withMissing++;

        IJ.log(String.format("[FrameAligner] per-track (offset %+d): %d tracks, %d with missing frames",
                offset, perTrack.size(), withMissing));

        for (TrackAlignment ta : perTrack) {
            if (ta.fullyMapped()) continue;
            IJ.log(String.format("[FrameAligner]   ⚠ track %s: frames %d–%d (%d det.), %d missing",
                    ta.trackId, ta.firstFrame, ta.lastFrame, ta.detectionCount, ta.missingCount));
        }
    }
}
