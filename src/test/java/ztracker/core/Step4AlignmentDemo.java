package ztracker.core;

import ztracker.core.FrameAligner.AlignmentReport;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;
import ztracker.model.TrackData;

import java.util.*;

/**
 * Human-readable walkthrough of the step-4 frame-offset logic. NOT a test — it
 * has a {@code main} and no {@code @Test} methods, so Surefire ignores it, but it
 * stays compiled against the real {@link FrameAligner} on every {@code mvn test}.
 *
 * <p>It runs the actual {@code suggestOffset} / {@code validate} code over several
 * CSV-vs-TIFF alignment scenarios and prints what each one computes, so the
 * suggestion-and-correction behaviour can be eyeballed rather than just asserted.
 *
 * <p>Run it manually (from the project root, after {@code mvn test-compile}):
 * <pre>
 *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.core.Step4AlignmentDemo
 * </pre>
 *
 * <p>To save the full output to a file instead of (or in addition to) the console, redirect
 * stdout to {@code Step4AlignmentDemo_output.txt} alongside this source file. Java's default
 * stdout encoding on Windows is not UTF-8 — pass {@code -Dfile.encoding=UTF-8
 * -Dstdout.encoding=UTF-8} in case any output ever carries non-ASCII characters:
 * <pre>
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "target/classes;target/test-classes;$(cat cp.txt)" ztracker.core.Step4AlignmentDemo &gt; src/test/java/ztracker/core/Step4AlignmentDemo_output.txt 2&gt;&amp;1
 * </pre>
 */
public class Step4AlignmentDemo {

    private static LoadedStack stack(int... f) {
        Map<Integer, Integer> map = new HashMap<>();
        List<Integer> nums = new ArrayList<>();
        int[][][] px = new int[f.length][1][1];
        for (int i = 0; i < f.length; i++) { map.put(f[i], i); nums.add(f[i]); }
        return new LoadedStack(px, map, nums, 1, 1);
    }

    private static TrackData track(int... f) {
        String[] id = new String[f.length];
        for (int i = 0; i < f.length; i++) id[i] = "1";
        return track(f, id);
    }

    private static TrackData track(int[] f, String[] ids) {
        double[] x = new double[f.length], y = new double[f.length], r = new double[f.length];
        for (int i = 0; i < f.length; i++) r[i] = Double.NaN;
        return new TrackData(x, y, f, r, ids, "X", "Y", "Frame", "Track_ID", null, 3.5);
    }

    private static String signed(int n) { return (n >= 0 ? "+" : "") + n; }

    private static String mapping(int[] csv, int[] tiff, int off) {
        Set<Integer> avail = new TreeSet<>();
        for (int f : tiff) avail.add(f);
        TreeSet<Integer> uniq = new TreeSet<>();
        for (int f : csv) uniq.add(f);
        StringBuilder sb = new StringBuilder();
        for (int f : uniq) {
            int mapped = f + off;
            sb.append(f).append("->").append(mapped)
              .append(avail.contains(mapped) ? "[OK] " : "[MISSING] ");
        }
        return sb.toString().trim();
    }

    private static void scenario(String title, int[] csv, int[] tiff, int chosenOffset) {
        scenario(title, csv, tiff, singleIds(csv.length), chosenOffset);
    }

    private static String[] singleIds(int n) {
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) ids[i] = "1";
        return ids;
    }

    private static void scenario(String title, int[] csv, int[] tiff, String[] ids, int chosenOffset) {
        TrackData t = track(csv, ids);
        LoadedStack s = stack(tiff);

        int suggested = FrameAligner.suggestOffset(t, s);
        AlignmentReport rep = FrameAligner.validate(t, s, chosenOffset);

        System.out.println("\n============================================================");
        System.out.println(title);
        System.out.println("  CSV frames : " + Arrays.toString(csv));
        System.out.println("  Track IDs  : " + Arrays.toString(ids));
        System.out.println("  TIFF frames: " + Arrays.toString(tiff));
        System.out.println("  ----");
        System.out.println("  suggestOffset()  -> " + signed(suggested)
                + "   (start-anchored guess the dialog pre-fills)");
        System.out.println("  validate(offset=" + signed(chosenOffset) + "):");
        System.out.println("     mapping: " + mapping(csv, tiff, chosenOffset));
        System.out.println("     missing frames   = " + rep.missingFrameCount + " / " + rep.totalUniqueFrames);
        System.out.printf("     missing fraction = %.0f%%%n", rep.missingFraction() * 100);
        System.out.println("     warning shown?   = " + rep.hasWarning());
        System.out.println("     per-track:");
        for (FrameAligner.TrackAlignment ta : rep.perTrack) {
            System.out.printf("        Track %-4s frames %d-%d (%d det.)  %s%n",
                    ta.trackId, ta.firstFrame, ta.lastFrame, ta.detectionCount,
                    ta.fullyMapped() ? "all OK" : (ta.missingCount + " MISSING"));
        }
        // Step-4 confirm box: the checkbox defaults ON only when the offset is clean
        // (every detection in every track maps); otherwise it defaults OFF and asks
        // for a deliberate "continue anyway".
        boolean clean = !rep.hasWarning() && rep.problemTracks().isEmpty();
        System.out.println("  confirm checkbox -> " + (clean
                ? "CHECKED   \"Alignment looks correct - continue\""
                : "UNCHECKED \"I have reviewed the warnings above - continue anyway\""));
    }

    public static void main(String[] args) {
        // The committed snapshot must not depend on the machine's decimal separator.
        Locale.setDefault(Locale.ROOT);

        scenario("A) 0-based CSV, 1-based TIFF, user accepts the +1 suggestion (CORRECT fix)",
                new int[]{0, 1, 2, 3}, new int[]{1, 2, 3, 4}, 1);

        scenario("B) Same data but user leaves offset at 0 (WRONG - frame 0 has no TIFF)",
                new int[]{0, 1, 2, 3}, new int[]{1, 2, 3, 4}, 0);

        scenario("C) Already aligned: no shift needed",
                new int[]{1, 2, 3}, new int[]{1, 2, 3}, 0);

        scenario("D) 1-based CSV, 0-based TIFF: suggestion is -1",
                new int[]{1, 2, 3}, new int[]{0, 1, 2}, -1);

        scenario("E) Correct offset +1, but one CSV frame (9) runs past the TIFF range",
                new int[]{0, 1, 2, 9}, new int[]{1, 2, 3, 4}, 1);

        scenario("F) Short track in a long recording: offset +1 is CORRECT even though the\n"
                + "    track (frames 5-7) covers only a sliver of the 1-100 TIFF stack",
                new int[]{5, 6, 7}, range(1, 100), 1);

        scenario("G) Two tracks, per-track breakdown: Track A maps cleanly, Track B (frames\n"
                + "    8-9) runs past the TIFF end under offset +1 — reported per track",
                new int[]{0, 1, 2, 8, 9},
                new int[]{1, 2, 3, 4},
                new String[]{"A", "A", "A", "B", "B"},
                1);

        // Scenario G at offset +1, showing both outputs the plugin produces:
        //  1) the compact decision verdict shown live in the step-4 confirm box, and
        //  2) the full per-track verification table written to the Fiji Log.
        TrackData gT = track(new int[]{0, 1, 2, 8, 9}, new String[]{"A", "A", "A", "B", "B"});
        LoadedStack gS = stack(1, 2, 3, 4);
        java.util.List<FrameAligner.TrackAlignment> gPer =
                FrameAligner.perTrackAlignment(gT, gS, 1);

        System.out.println("\n============================================================");
        System.out.println("Step-4 confirm box verdict (scenario G, offset +1):");
        System.out.println("------------------------------------------------------------");
        System.out.println(FrameAligner.buildBoxSummary(gPer));

        System.out.println("\nFull per-track table logged to Fiji (scenario G, offset +1):");
        System.out.println("------------------------------------------------------------");
        System.out.println(FrameAligner.buildPerTrackTable(gPer, gS, 1));
    }

    private static int[] range(int firstInclusive, int lastInclusive) {
        int[] r = new int[lastInclusive - firstInclusive + 1];
        for (int i = 0; i < r.length; i++) r[i] = firstInclusive + i;
        return r;
    }
}
