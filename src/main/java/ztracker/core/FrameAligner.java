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
        // Order by track id, numerically when the ids are numbers (so "10" sorts
        // after "2", not before), lexicographically otherwise.
        out.sort((a, b) -> compareTrackIds(a.trackId, b.trackId));
        return out;
    }

    /** Compares track ids numerically when both parse as integers, else lexicographically. */
    private static int compareTrackIds(String a, String b) {
        try {
            return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim()));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
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
        logPerTrack(perTrack, stack, offset, suggested);

        return new AlignmentReport(offset, missing, uniqueCsvFrames.size(), suggested, perTrack);
    }

    /** Max number of off-stack tracks to name in the compact confirm-box summary. */
    private static final int MAX_BOX_TRACKS_SHOWN = 4;

    /**
     * Builds the compact, decision-focused verdict shown in the step-4 confirmation
     * box: a one-line coverage verdict, the off-stack tracks named (capped), and a
     * pointer to the Log for the full per-track table. Does NOT log — safe to call
     * repeatedly as the user edits the offset (live preview).
     *
     * @param perTrack per-track alignment (from {@link #perTrackAlignment})
     * @return multi-line summary string
     */
    public static String buildBoxSummary(List<TrackAlignment> perTrack) {
        int total = perTrack.size();
        if (total == 0) return "No tracks loaded.";

        int fully = 0;
        for (TrackAlignment ta : perTrack) if (ta.fullyMapped()) fully++;

        StringBuilder sb = new StringBuilder();
        if (fully == total) {
            sb.append(String.format("OK — all %d track(s) map fully to the TIFF stack.", total));
        } else {
            sb.append(String.format("WARNING — %d of %d track(s) map fully to the TIFF stack.%n",
                    fully, total));
            sb.append("Off-stack: ");
            int shown = 0;
            for (TrackAlignment ta : perTrack) {
                if (ta.fullyMapped()) continue;
                if (shown >= MAX_BOX_TRACKS_SHOWN) {
                    sb.append(String.format(", +%d more", (total - fully) - shown));
                    break;
                }
                if (shown > 0) sb.append(", ");
                sb.append(String.format("Track %s (%d/%d det.)",
                        ta.trackId, ta.missingCount, ta.detectionCount));
                shown++;
            }
        }
        sb.append(String.format("%nFull per-track table is in the Log window."));
        return sb.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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

    /** Max number of per-track rows written to the log (keeps huge CSVs readable). */
    private static final int MAX_TRACKS_LOGGED = 50;

    /**
     * Logs a full per-track table so the user can verify the offset: every track's
     * span, detection count, and how its FIRST and LAST frames map under the offset
     * (with a ✓/✗ per endpoint), plus the missing count. Capped at
     * {@link #MAX_TRACKS_LOGGED} rows.
     */
    private static void logPerTrack(List<TrackAlignment> perTrack, LoadedStack stack,
                                    int offset, int suggested) {
        Set<Integer> avail = stack.frameToIdx.keySet();
        int fully = 0;
        for (TrackAlignment ta : perTrack) if (ta.fullyMapped()) fully++;

        IJ.log(String.format(
                "[FrameAligner] ── Per-track alignment (offset %+d, suggested %+d) ──",
                offset, suggested));
        IJ.log("[FrameAligner]   track            span        det   first→TIFF     last→TIFF     missing");

        int shown = 0;
        for (TrackAlignment ta : perTrack) {
            if (shown++ >= MAX_TRACKS_LOGGED) break;
            int firstMap = ta.firstFrame + offset;
            int lastMap  = ta.lastFrame  + offset;
            String fTag  = avail.contains(firstMap) ? "✓" : "✗";
            String lTag  = avail.contains(lastMap)  ? "✓" : "✗";
            IJ.log(String.format(
                    "[FrameAligner]   %-14s %5d–%-5d %5d   %5d→%-5d %s   %5d→%-5d %s   %d",
                    truncate(ta.trackId, 14),
                    ta.firstFrame, ta.lastFrame, ta.detectionCount,
                    ta.firstFrame, firstMap, fTag,
                    ta.lastFrame,  lastMap,  lTag,
                    ta.missingCount));
        }

        int remaining = perTrack.size() - Math.min(perTrack.size(), MAX_TRACKS_LOGGED);
        if (remaining > 0) {
            IJ.log(String.format("[FrameAligner]   … %d more track(s) not shown (log cap %d)",
                    remaining, MAX_TRACKS_LOGGED));
        }
        IJ.log(String.format("[FrameAligner]   %d/%d tracks fully mapped under offset %+d",
                fully, perTrack.size(), offset));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
