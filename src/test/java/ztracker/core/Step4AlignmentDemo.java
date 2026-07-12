package ztracker.core;

import ztracker.core.FrameAligner.AlignmentReport;
import ztracker.io.TiffStackLoader.LoadedStack;
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
        double[] x = new double[f.length], y = new double[f.length], r = new double[f.length];
        String[] id = new String[f.length];
        for (int i = 0; i < f.length; i++) { r[i] = Double.NaN; id[i] = "1"; }
        return new TrackData(x, y, f, r, id, "X", "Y", "Frame", "Track_ID", null, 3.5);
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
        TrackData t = track(csv);
        LoadedStack s = stack(tiff);

        int suggested = FrameAligner.suggestOffset(t, s);
        AlignmentReport rep = FrameAligner.validate(t, s, chosenOffset);

        System.out.println("\n============================================================");
        System.out.println(title);
        System.out.println("  CSV frames : " + Arrays.toString(csv));
        System.out.println("  TIFF frames: " + Arrays.toString(tiff));
        System.out.println("  ----");
        System.out.println("  suggestOffset()  -> " + signed(suggested)
                + "   (start-anchored guess the dialog pre-fills)");
        System.out.println("  suggestOffsetFromEnd() -> " + signed(rep.suggestedOffsetFromEnd)
                + "   (end-anchored cross-check)");
        System.out.println("  ranges consistent? = " + rep.rangesConsistent
                + (rep.rangesConsistent ? "" : "   <-- spans differ, suggestion is a guess"));
        System.out.println("  validate(offset=" + signed(chosenOffset) + "):");
        System.out.println("     mapping: " + mapping(csv, tiff, chosenOffset));
        System.out.println("     missing frames   = " + rep.missingFrameCount + " / " + rep.totalUniqueFrames);
        System.out.printf("     missing fraction = %.0f%%%n", rep.missingFraction() * 100);
        System.out.println("     warning shown?   = " + rep.hasWarning());
    }

    public static void main(String[] args) {
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

        scenario("F) Range mismatch: CSV spans 0-3 (+1 at start) but TIFF spans 1-6 (+3 at end)",
                new int[]{0, 1, 2, 3}, new int[]{1, 2, 3, 4, 5, 6}, 1);
    }
}
