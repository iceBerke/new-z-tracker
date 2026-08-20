package ztracker.topoj;

import org.junit.jupiter.api.Test;
import ztracker.core.topoj.TopoJStackDecoder;
import ztracker.core.topoj.TopoJZConversion;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopoJTrackerPlugin}'s one piece of non-orchestration logic: the text warning
 * the user gets when TopoJ's {@code 1.0} sentinel collides with a legitimate depth.
 *
 * <p>Everything else in that class is dialog sequencing and I/O, which is manual-only. This
 * string is tested because it is <b>the only thing standing between a user and a silently
 * missing Z layer</b>: the visible symptom of the collision is that one end of the Z range never
 * appears, which nobody notices unaided. If the warning omits a number, the user cannot tell
 * what was dropped or how to stop it happening.
 *
 * <p>The report is built by decoding a real (tiny) stack rather than by constructing a
 * {@code Report} directly — its constructor is package-private to {@code core.topoj}, and going
 * through {@link TopoJStackDecoder} also keeps the numbers in the warning genuinely derived.
 */
class TopoJTrackerPluginTest {

    /** The motivating dataset's shape: 401 slices, −400 µm to +400 µm at 2 µm, uncalibrated. */
    private static TopoJStackDecoder.Report ambiguousReport() {
        float[][] frame = {{1.0f, 1.0f}, {2.0f, 4.0f}};   // two of four pixels unassigned
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedFloatStack stack = new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 2, 2);
        return TopoJStackDecoder.decode(stack, new TopoJZConversion(-400.0, 2.0, 401, 1.0));
    }

    @Test
    void ambiguityWarning_namesBothReadings_whatWasDropped_andTheFix() {
        String w = TopoJTrackerPlugin.buildAmbiguityWarning(ambiguousReport());

        // The colliding slice, stated as a slice and not merely implied by a depth.
        assertTrue(w.contains("legitimate encoding of source slice 400"),
                "must name the colliding slice index: " + w);

        // Both depths the same pixel could have meant. Built with the same formatter the message
        // uses, so the assertion pins the VALUE reaching the right slot rather than the locale.
        assertTrue(w.contains(String.format("Z = %.3f", -400.0)),
                "must give the sentinel reading's Z: " + w);
        assertTrue(w.contains(String.format("Z = %.3f", 400.0)),
                "must give the colliding slice's Z: " + w);

        // How much was discarded, as a count and as a share — one pixel and half the image are
        // very different situations and the user has nothing else to judge by.
        assertTrue(w.contains("2 of 4 pixels"), "must state the discarded count: " + w);
        assertTrue(w.contains(String.format("(%.2f%%)", 50.0)),
                "must state the discarded percentage: " + w);

        // The action that removes the ambiguity, not just a description of it.
        assertTrue(w.contains("Voxel depth"), "must name where the fix is applied: " + w);
        assertTrue(w.contains("re-run TopoJ"), "must say the fix requires re-running TopoJ: " + w);
        assertTrue(w.contains("encoding scale"), "must tie the fix back to the Step 2 input: " + w);

        // And the consequence, since the symptom is an absence rather than an error.
        assertTrue(w.contains("DISCARDED"), "must say the pixels were discarded: " + w);
    }

    @Test
    void ambiguityWarning_isEmptyWhenTheSentinelCannotCollide() {
        // The same pixels decoded at encodingScale 2, where 1.0 can only be the sentinel and its
        // pixels became a real depth rather than being dropped. A warning here would be a false
        // alarm about data loss that did not happen — as damaging in its own way as a missing
        // warning, since a user who learns to ignore it will ignore the real one too.
        float[][] frame = {{1.0f, 1.0f}, {2.0f, 4.0f}};
        Map<Integer, Integer> frameToIdx = new HashMap<>();
        frameToIdx.put(0, 0);
        LoadedFloatStack stack = new LoadedFloatStack(new float[][][]{frame}, frameToIdx,
                new ArrayList<>(Arrays.asList(0)), 2, 2);

        TopoJStackDecoder.Report r =
                TopoJStackDecoder.decode(stack, new TopoJZConversion(-400.0, 2.0, 401, 2.0));

        assertEquals("", TopoJTrackerPlugin.buildAmbiguityWarning(r),
                "no collision, so the plugin must emit no warning text at all");
    }
}
