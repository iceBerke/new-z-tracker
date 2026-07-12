package ztracker.core;

import org.junit.jupiter.api.Test;
import ztracker.core.FrameAligner.AlignmentReport;
import ztracker.core.FrameAligner.TrackAlignment;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.model.TrackData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the step-4 offset detection and validation logic.
 *
 * <p>The contract under test: {@code tiffFrame = csvFrame + offset}. The aligner
 * must suggest the classic 0-vs-1 correction, count how many CSV frames fail to
 * map after a candidate offset, and report a clean run when everything aligns.
 */
class FrameAlignerTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Builds a stack whose only meaningful content is the set of frame numbers present. */
    private static LoadedStack stackWithFrames(int... frames) {
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        List<Integer> frameNumbers = new ArrayList<>();
        int[][][] pixels = new int[frames.length][1][1];
        for (int i = 0; i < frames.length; i++) {
            frameToIdx.put(frames[i], i);
            frameNumbers.add(frames[i]);
        }
        return new LoadedStack(pixels, frameToIdx, frameNumbers, 1, 1);
    }

    /** Builds track data whose only meaningful content is the per-detection frame numbers. */
    private static TrackData trackWithFrames(int... frames) {
        double[] x = new double[frames.length];
        double[] y = new double[frames.length];
        double[] radius = new double[frames.length];
        String[] trackId = new String[frames.length];
        for (int i = 0; i < frames.length; i++) {
            radius[i] = Double.NaN;
            trackId[i] = "1";
        }
        return new TrackData(x, y, frames, radius, trackId,
                "X", "Y", "Frame", "Track_ID", null, 3.5);
    }

    // ── suggestOffset ───────────────────────────────────────────────────────────

    @Test
    void suggestOffset_zeroBasedCsvOneBasedTiff_suggestsPlusOne() {
        // CSV frames start at 0, TIFF files start at 1 — the classic mismatch.
        TrackData track = trackWithFrames(0, 1, 2, 3);
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);

        assertEquals(1, FrameAligner.suggestOffset(track, stack));
    }

    @Test
    void suggestOffset_alreadyAligned_suggestsZero() {
        TrackData track = trackWithFrames(1, 2, 3);
        LoadedStack stack = stackWithFrames(1, 2, 3);

        assertEquals(0, FrameAligner.suggestOffset(track, stack));
    }

    @Test
    void suggestOffset_oneBasedCsvZeroBasedTiff_suggestsMinusOne() {
        TrackData track = trackWithFrames(1, 2, 3);
        LoadedStack stack = stackWithFrames(0, 1, 2);

        assertEquals(-1, FrameAligner.suggestOffset(track, stack));
    }

    @Test
    void suggestOffset_usesMinimumFrameNotFirstRow() {
        // Detections are unordered; the suggestion must key off the minimum CSV frame.
        TrackData track = trackWithFrames(5, 2, 8, 0);
        LoadedStack stack = stackWithFrames(1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertEquals(1, FrameAligner.suggestOffset(track, stack));
    }

    // ── validate ────────────────────────────────────────────────────────────────

    @Test
    void validate_correctOffset_reportsNoMissingFrames() {
        TrackData track = trackWithFrames(0, 1, 2, 3);
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);

        AlignmentReport report = FrameAligner.validate(track, stack, 1);

        assertEquals(1, report.offset);
        assertEquals(0, report.missingFrameCount);
        assertEquals(4, report.totalUniqueFrames);
        assertEquals(0.0, report.missingFraction());
        assertFalse(report.hasWarning());
    }

    @Test
    void validate_wrongOffset_flagsMissingAndKeepsSuggestion() {
        // Applying offset 0 to a 0-based CSV leaves frame 0 unmapped (TIFF starts at 1).
        TrackData track = trackWithFrames(0, 1, 2, 3);
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);

        AlignmentReport report = FrameAligner.validate(track, stack, 0);

        assertEquals(1, report.missingFrameCount); // only CSV frame 0 lacks a TIFF; 1,2,3 exist
        assertTrue(report.hasWarning());
        assertEquals(1, report.suggestedOffset); // still recommends the correct fix
    }

    @Test
    void validate_partialMismatch_countsOnlyUnmappedFrames() {
        // Offset 1 maps CSV {0,1,2,9} → TIFF {1,2,3,10}; frame 10 is absent.
        TrackData track = trackWithFrames(0, 1, 2, 9);
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);

        AlignmentReport report = FrameAligner.validate(track, stack, 1);

        assertEquals(1, report.missingFrameCount);
        assertEquals(4, report.totalUniqueFrames);
        assertEquals(0.25, report.missingFraction());
        assertTrue(report.hasWarning());
    }

    @Test
    void validate_duplicateFrames_countUniqueOnly() {
        // Many detections share frames; the report must be over UNIQUE frames.
        TrackData track = trackWithFrames(0, 0, 1, 1, 1, 2);
        LoadedStack stack = stackWithFrames(1, 2, 3);

        AlignmentReport report = FrameAligner.validate(track, stack, 1);

        assertEquals(3, report.totalUniqueFrames);
        assertEquals(0, report.missingFrameCount);
    }

    // ── per-track alignment ───────────────────────────────────────────────────────

    /** Builds multi-track data: frames[] paired with a parallel trackIds[]. */
    private static TrackData trackWithIds(int[] frames, String[] ids) {
        double[] x = new double[frames.length];
        double[] y = new double[frames.length];
        double[] radius = new double[frames.length];
        for (int i = 0; i < frames.length; i++) radius[i] = Double.NaN;
        return new TrackData(x, y, frames, radius, ids,
                "X", "Y", "Frame", "Track_ID", null, 3.5);
    }

    @Test
    void perTrack_shortTrackFullyInsideRange_notFlagged() {
        // A track covering frames 5-7 of a 1-100 recording is normal: offset +1
        // maps 5->6, 6->7, 7->8 — all present. It must NOT be reported as a problem.
        int[] tiff = new int[100];
        for (int i = 0; i < 100; i++) tiff[i] = i + 1; // TIFF frames 1..100
        LoadedStack stack = stackWithFrames(tiff);
        TrackData track = trackWithIds(new int[]{5, 6, 7}, new String[]{"A", "A", "A"});

        AlignmentReport report = FrameAligner.validate(track, stack, 1);

        assertEquals(0, report.missingFrameCount);
        assertTrue(report.problemTracks().isEmpty());
        assertEquals(1, report.perTrack.size());
        TrackAlignment a = report.perTrack.get(0);
        assertEquals(5, a.firstFrame);
        assertEquals(7, a.lastFrame);
        assertEquals(3, a.detectionCount);
        assertTrue(a.fullyMapped());
    }

    @Test
    void perTrack_separatesTracksAndCountsPerTrackMissing() {
        // Track A: frames 0-2 (offset +1 -> 1,2,3 all present).
        // Track B: frames 8-9 (offset +1 -> 9,10; TIFF only goes to 4, so both missing).
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);
        TrackData track = trackWithIds(
                new int[]{0, 1, 2, 8, 9},
                new String[]{"A", "A", "A", "B", "B"});

        AlignmentReport report = FrameAligner.validate(track, stack, 1);

        assertEquals(2, report.perTrack.size());
        // Ordered by first frame: A (0) before B (8).
        TrackAlignment a = report.perTrack.get(0);
        TrackAlignment b = report.perTrack.get(1);
        assertEquals("A", a.trackId);
        assertTrue(a.fullyMapped());
        assertEquals("B", b.trackId);
        assertEquals(2, b.detectionCount);
        assertEquals(2, b.missingCount);
        assertFalse(b.fullyMapped());

        assertEquals(1, report.problemTracks().size());
        assertEquals("B", report.problemTracks().get(0).trackId);
    }

    @Test
    void perTrack_orderedByFirstFrame() {
        LoadedStack stack = stackWithFrames(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        TrackData track = trackWithIds(
                new int[]{7, 1, 4},
                new String[]{"late", "early", "mid"});

        AlignmentReport report = FrameAligner.validate(track, stack, 0);

        assertEquals("early", report.perTrack.get(0).trackId);
        assertEquals("mid",   report.perTrack.get(1).trackId);
        assertEquals("late",  report.perTrack.get(2).trackId);
    }

    // ── buildBoxSummary (compact confirm-box verdict) ─────────────────────────────

    @Test
    void buildBoxSummary_allClean_reportsOkWithTrackCount() {
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);
        TrackData track = trackWithIds(
                new int[]{0, 1, 2, 3},
                new String[]{"A", "A", "B", "B"});

        String summary = FrameAligner.buildBoxSummary(
                FrameAligner.perTrackAlignment(track, stack, 1));

        assertTrue(summary.contains("OK"), summary);
        assertTrue(summary.contains("all 2 track(s)"), summary);
        assertTrue(summary.contains("Log window"), summary);
    }

    @Test
    void buildBoxSummary_someOffStack_namesOffendingTracks() {
        // Track A clean (0-2 -> 1-3); Track B off-stack (8-9 -> 9,10; stack ends at 4).
        LoadedStack stack = stackWithFrames(1, 2, 3, 4);
        TrackData track = trackWithIds(
                new int[]{0, 1, 2, 8, 9},
                new String[]{"A", "A", "A", "B", "B"});

        String summary = FrameAligner.buildBoxSummary(
                FrameAligner.perTrackAlignment(track, stack, 1));

        assertTrue(summary.contains("WARNING"), summary);
        assertTrue(summary.contains("1 of 2 track(s)"), summary);
        assertTrue(summary.contains("Track B (2/2 det.)"), summary);
    }

    @Test
    void buildBoxSummary_manyOffStack_capsListWithMoreCount() {
        // Six tracks, all off-stack under offset 100; the box names at most 4 then "+2 more".
        LoadedStack stack = stackWithFrames(1, 2, 3);
        TrackData track = trackWithIds(
                new int[]{0, 1, 2, 3, 4, 5},
                new String[]{"t0", "t1", "t2", "t3", "t4", "t5"});

        String summary = FrameAligner.buildBoxSummary(
                FrameAligner.perTrackAlignment(track, stack, 100));

        assertTrue(summary.contains("WARNING"), summary);
        assertTrue(summary.contains("+2 more"), summary);
    }
}
