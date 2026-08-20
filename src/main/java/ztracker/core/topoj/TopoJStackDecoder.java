package ztracker.core.topoj;

import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * Applies a {@link TopoJZConversion} across a whole loaded TopoJ stack, replacing each encoded
 * value with its physical Z — but only after validating every pixel first.
 *
 * <p><b>Package choice.</b> This lives in {@code core.topoj} rather than {@code io.topoj} because
 * it opens nothing and reads nothing from disk — it works over pixels another class already
 * loaded, exactly as {@link TopoJSampler} and {@link TopoJExtractor} do with the same
 * {@link LoadedFloatStack} type, and {@code io} is where files are read.
 *
 * <p><b>Standalone and unwired.</b> Nothing in the plugin calls this class yet; Tool 3 still reads
 * a TopoJ pixel value as Z directly. A later patch wires it in and collects the four parameters
 * {@link TopoJZConversion} needs.
 *
 * <h3>Two passes, validation strictly before mutation</h3>
 *
 * <p>Pass one classifies every pixel and accumulates statistics; pass two converts, and runs
 * <b>only</b> if pass one found nothing disqualifying. The order is the point: a stack that is
 * partly decoded and partly still raw is worse than either, because both halves are plausible
 * floats and <b>nothing downstream could detect the mixture</b> — the sampler would read µm from
 * one frame and slice counters from the next and report both as depths. So a failure leaves the
 * pixel array <b>byte-for-byte untouched</b>. Both passes run over already-resident memory; no
 * file is re-read.
 *
 * <h3>NaN is not a file-level failure</h3>
 *
 * <p>{@link TopoJZConversion#classify} returns {@link TopoJZConversion.ValueClass#INVALID} for
 * NaN, which is right per value — NaN is neither sentinel nor a point on the encoding lattice.
 * At <i>file</i> level it must not be, and this class therefore counts NaN <b>separately</b> from
 * INVALID, passes it through unchanged, and never lets it trigger a refusal. Tool 3 already treats
 * a NaN pixel as no-data: the loader keeps it, {@code ZAggregator} filters it out, and a detection
 * whose samples are all NaN reports {@code STATUS_NO_DATA}. Refusing such a file here would reject
 * maps that load fine today — a behaviour regression, and a loud one, since the user cannot tell
 * it from a genuinely malformed file. Recorded in {@code docs/BACKLOG.md} as
 * <b>"Carve NaN out of any whole-image validation the TopoJ loader gains"</b> (cited by name: that
 * file's numbers are positions, not identities).
 *
 * <h3>A non-NaN INVALID fails the entire stack</h3>
 *
 * <p>An off-lattice or out-of-range value means either the declared parameters are wrong or the
 * file is not what it is claimed to be. Both causes are <b>systematic, not local</b>: whatever
 * produced one such pixel produced the rest of the image too, so dropping the offenders and
 * converting the remainder would yield a fully populated, entirely wrong result — the failure
 * class this project refuses everywhere. Such pixels are therefore never silently dropped.
 *
 * <h3>In-place conversion and what it costs</h3>
 *
 * <p>Conversion writes back into the stack's own array rather than allocating a second one. The
 * motivating dataset is 362 frames of 1051 x 1674 floats — about 2.4 GB — so a copy is measured in
 * gigabytes, and the loader has already committed to holding one copy.
 *
 * <p>Z is computed in {@code double} and stored as {@code float}, so it is rounded once. For a Z
 * of magnitude 1000 µm the float32 spacing is {@code 2^-14} µm, about {@code 6.1e-5}, and storing
 * costs at most half of that — roughly <b>3.1e-5 µm, or 0.03 nm</b>. Against the motivating
 * stack's 2 µm z-step that is one part in ~65,000 of a single step; even a 0.1 µm step is more
 * than three thousand times larger. The rounding is therefore irrelevant at any depth resolution
 * this pipeline can produce, which is why the copy is not worth its memory.
 *
 * <p><b>The raw encoded values do not survive.</b> Once pass two has run, the sentinels and the
 * slice counters are gone — the array holds depths. Every statistic in {@link Report} is therefore
 * gathered in pass one, before anything is written; there is no second chance to compute one.
 *
 * <h3>What the failure means, and how a caller must catch it</h3>
 *
 * <p>The {@link IllegalArgumentException} thrown by {@link #decode} signals <b>user-supplied
 * parameters that do not match the data</b> — a wrong {@code pixelDepth}, a wrong slice count, or
 * the wrong folder entirely. It is not a programming error, and its message is written to be read
 * by the person who typed those parameters.
 *
 * <p>A caller must therefore catch <b>this specific type at the call site</b>, never
 * {@code RuntimeException} or {@code Exception} around a wider block. A blanket catch would
 * present a genuine bug — a null, an index error — to the user as "your parameters are wrong",
 * which is worse than the stack trace it replaces: it sends them to re-check inputs that were
 * correct, and buries the defect. (Note for the patch that wires this in; nothing here catches
 * anything.)
 */
public class TopoJStackDecoder {

    /** How many offending pixels the failure message names individually. */
    private static final int MAX_EXAMPLES = 5;

    private TopoJStackDecoder() {}

    /**
     * Validates every pixel of {@code stack} against {@code conversion} and, if all of them are
     * decodable, replaces each with its physical Z in place.
     *
     * @param stack      loaded TopoJ float stack; its pixel array is mutated on success and left
     *                   untouched on failure
     * @param conversion the decode parameters to apply
     * @return the statistics gathered during validation — before conversion destroyed the raw values
     * @throws IllegalArgumentException if any pixel is neither a sentinel, nor NaN, nor a
     *         legitimate encoded slice. The message states the total count, names up to
     *         {@value #MAX_EXAMPLES} offenders with their frame and coordinates, and restates the
     *         parameters they failed against. Nothing has been converted when it is thrown.
     */
    public static Report decode(LoadedFloatStack stack, TopoJZConversion conversion) {
        if (stack == null)      throw new IllegalArgumentException("stack must not be null");
        if (conversion == null) throw new IllegalArgumentException("conversion must not be null");

        Scan scan = validate(stack, conversion);   // pass one — reads only
        convertInPlace(stack, conversion);          // pass two — reached only if pass one returned
        return scan.toReport(conversion);
    }

    // ── Pass one: classify everything, mutate nothing ─────────────────────────

    /** Per-stack tallies gathered before any pixel is written. */
    private static class Scan {
        long valid, unassigned, belowThreshold, nan, invalid;
        /** Largest VALID value seen, or NaN if there was none. */
        float maxValid = Float.NaN;
        final List<Report.FrameSentinel> perFrame = new ArrayList<>();

        Report toReport(TopoJZConversion conversion) {
            // TopoJZConversion.Stats is used for exactly one thing — the snapped implied-slice-count
            // arithmetic — so only the maximum is fed to it, after the scan. Re-deriving that
            // snapping here would duplicate a rule that already has an owner, and the two could
            // then disagree.
            OptionalInt implied = OptionalInt.empty();
            if (!Float.isNaN(maxValid)) {
                TopoJZConversion.Stats stats = new TopoJZConversion.Stats(conversion);
                stats.record(maxValid);
                implied = OptionalInt.of(stats.impliedMinimumSliceCount());
            }
            return new Report(valid, unassigned, belowThreshold, nan, invalid,
                    implied, conversion.nSlices(), conversion.ambiguity(), perFrame);
        }
    }

    private static Scan validate(LoadedFloatStack stack, TopoJZConversion conversion) {
        Scan scan = new Scan();
        List<String> examples = new ArrayList<>(MAX_EXAMPLES);
        float firstOffender = Float.NaN;

        for (int i = 0; i < stack.pixels.length; i++) {
            float[][] frame = stack.pixels[i];
            long frameUnassigned = 0;

            for (int y = 0; y < stack.height; y++) {
                float[] row = frame[y];
                for (int x = 0; x < stack.width; x++) {
                    float v = row[x];

                    // Checked before classify(), which would call NaN INVALID — see the class
                    // javadoc and the backlog entry it cites.
                    if (Float.isNaN(v)) { scan.nan++; continue; }

                    switch (conversion.classify(v)) {
                        case VALID:
                            scan.valid++;
                            if (Float.isNaN(scan.maxValid) || v > scan.maxValid) scan.maxValid = v;
                            break;
                        case SENTINEL_UNASSIGNED:
                            scan.unassigned++;
                            frameUnassigned++;
                            break;
                        case SENTINEL_BELOW_THRESHOLD:
                            scan.belowThreshold++;
                            break;
                        default:
                            scan.invalid++;
                            if (examples.isEmpty()) firstOffender = v;
                            if (examples.size() < MAX_EXAMPLES) {
                                examples.add(String.format("value %s at frame %d (x=%d, y=%d)",
                                        v, stack.frameNumbers.get(i), x, y));
                            }
                            break;
                    }
                }
            }

            long framePixels = (long) stack.width * stack.height;
            scan.perFrame.add(new Report.FrameSentinel(
                    stack.frameNumbers.get(i), frameUnassigned,
                    framePixels == 0 ? 0.0 : 100.0 * frameUnassigned / framePixels));
        }

        if (scan.invalid > 0) {
            throw new IllegalArgumentException(
                    describeFailure(scan.invalid, examples, firstOffender, conversion));
        }
        return scan;
    }

    /**
     * The refusal message. One stray pixel and six hundred million are very different diagnoses —
     * a wrong parameter versus the wrong file entirely — so the total leads, the examples let the
     * user go and look, and the parameters are restated by letting {@link TopoJZConversion#toZ}
     * describe the first offender in its own words, so that wording has exactly one source.
     */
    private static String describeFailure(long invalidCount, List<String> examples,
                                          float firstOffender, TopoJZConversion conversion) {
        StringBuilder sb = new StringBuilder();
        sb.append(invalidCount).append(" pixel(s) in this stack are neither a sentinel nor a")
          .append(" legitimate encoded slice under the declared parameters, so it was not")
          .append(" converted — the stack is unchanged.");
        sb.append("\nFirst ").append(examples.size()).append(" of them: ");
        sb.append(String.join("; ", examples));
        if (invalidCount > examples.size()) {
            sb.append(" … and ").append(invalidCount - examples.size()).append(" more");
        }
        sb.append("\nWhy the first one failed: ");
        try {
            conversion.toZ(firstOffender);
            // Unreachable: firstOffender classified INVALID, so toZ throws.
            sb.append("(no detail available)");
        } catch (IllegalArgumentException e) {
            sb.append(e.getMessage());
        }
        sb.append("\nEither the declared parameters are wrong, or these images did not come from")
          .append(" a TopoJ run with that pixelDepth. Both are systematic, so no pixel was")
          .append(" converted: decoding the rest would produce a complete result that is wrong")
          .append(" everywhere.");
        return sb.toString();
    }

    // ── Pass two: convert ─────────────────────────────────────────────────────

    /**
     * Replaces every pixel with its physical Z. Reached only once {@link #validate} has returned,
     * so {@link TopoJZConversion#toZ} cannot throw here. NaN passes through untouched because
     * {@code toZ} maps NaN to NaN.
     */
    private static void convertInPlace(LoadedFloatStack stack, TopoJZConversion conversion) {
        for (float[][] frame : stack.pixels) {
            for (int y = 0; y < stack.height; y++) {
                float[] row = frame[y];
                for (int x = 0; x < stack.width; x++) {
                    row[x] = (float) conversion.toZ(row[x]);
                }
            }
        }
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * What the validation pass saw, gathered before conversion destroyed the raw values.
     *
     * <p>This object <b>reports only</b>. It logs nothing, touches no ImageJ type, and applies no
     * threshold or verdict of its own — a caller decides which of these numbers is worth showing
     * and what any of them means.
     */
    public static class Report {

        /** Pixels that decoded to a depth. */
        public final long validCount;
        /** Pixels holding the {@code 1.0f} unassigned sentinel. */
        public final long unassignedCount;
        /** Pixels holding the {@code -1.0f} below-threshold sentinel. */
        public final long belowThresholdCount;
        /**
         * Pixels holding NaN. Counted apart from {@link #invalidCount} on purpose: per value
         * {@code classify} calls NaN INVALID, but at file level NaN is no-data and must never
         * refuse a stack.
         */
        public final long nanCount;
        /**
         * Pixels that were neither sentinel, NaN, nor decodable. <b>Always 0 in a returned
         * report</b> — a non-zero count throws instead of returning — and present so the report
         * states the full classification rather than implying the case cannot arise.
         */
        public final long invalidCount;

        /**
         * Smallest slice count consistent with the largest VALID value seen, or
         * {@link OptionalInt#empty()} when no pixel decoded to a depth at all.
         *
         * <p><b>Why an {@code OptionalInt} and not a sentinel.</b> A {@code -1} would be safe
         * enough here — no one could mistake it for a slice count — but the whole subject of this
         * class is the damage done by a magic value sharing storage with real ones: TopoJ's own
         * {@code 1.0f} is exactly that, and at {@code encodingScale 1} it collides with a
         * legitimate depth and costs those pixels entirely. Java 8 can express absence without
         * borrowing from the value space, so it does. It also puts this field and
         * {@link TopoJZConversion.Stats#impliedMinimumSliceCount()} on the same footing — one
         * returns empty, the other throws, but both state absence outright rather than encoding it.
         */
        public final OptionalInt impliedMinimumSliceCount;

        /** The {@code nSlices} declared to the conversion, carried so both numbers travel together. */
        public final int declaredSliceCount;

        /**
         * Whether {@code 1.0} was ambiguous under these parameters, carrying both candidate depths
         * when it was — so a caller can explain why those pixels became NaN instead of a depth.
         */
        public final TopoJZConversion.Ambiguity ambiguity;

        /** Per-frame unassigned-sentinel tally, in stack order. Unmodifiable. */
        public final List<FrameSentinel> perFrame;

        Report(long validCount, long unassignedCount, long belowThresholdCount,
               long nanCount, long invalidCount, OptionalInt impliedMinimumSliceCount,
               int declaredSliceCount, TopoJZConversion.Ambiguity ambiguity,
               List<FrameSentinel> perFrame) {
            this.validCount               = validCount;
            this.unassignedCount          = unassignedCount;
            this.belowThresholdCount      = belowThresholdCount;
            this.nanCount                 = nanCount;
            this.invalidCount             = invalidCount;
            this.impliedMinimumSliceCount = impliedMinimumSliceCount;
            this.declaredSliceCount       = declaredSliceCount;
            this.ambiguity                = ambiguity;
            this.perFrame                 = Collections.unmodifiableList(perFrame);
        }

        /**
         * Whether the observed data corroborates the declared slice count — true when the implied
         * minimum is present and equals {@link #declaredSliceCount}.
         *
         * <p><b>The comparison is one-sided, and that is worth knowing before reading anything
         * into it.</b> After a successful validation the implied minimum can never <i>exceed</i>
         * the declared count: a value implying more slices would have failed the range test and
         * refused the stack outright. So this flag detects only a declared {@code nSlices} that is
         * too <b>high</b>. A too-low one is already caught, and caught harder — as a validation
         * failure with a message naming the offending pixels.
         *
         * <p><b>What a shortfall actually means:</b> no pixel in the whole stack was brightest at
         * source slice {@code k = 1}, since that is the only slice encoding the largest legitimate
         * value. That is suspicious — it is the slice nearest the top of the acquired range, below
         * only the unassigned {@code k = 0} — but it is entirely legitimate for a surface that
         * never rises that far. It is therefore a <b>diagnostic and never a refusal</b>: a decode
         * that reports {@code false} here has still succeeded, and its Z values are as good as any
         * other.
         *
         * <p>Absent implied count (no VALID pixel anywhere) also reports {@code false} — nothing
         * corroborates the declared count, which is not the same as contradicting it.
         */
        public boolean sliceCountAgrees() {
            return impliedMinimumSliceCount.isPresent()
                    && impliedMinimumSliceCount.getAsInt() == declaredSliceCount;
        }

        /** Every pixel examined, across every frame. */
        public long totalPixels() {
            return validCount + unassignedCount + belowThresholdCount + nanCount + invalidCount;
        }

        /**
         * How much of one frame TopoJ never assigned — the pixels whose brightest slice was
         * {@code k = 0}, which carry the unscaled {@code 1.0f} literal.
         */
        public static class FrameSentinel {
            /** Frame number from the filename, not the index into the stack array. */
            public final int frameNumber;
            public final long unassignedCount;
            /** {@link #unassignedCount} as a percentage of that frame's pixels. */
            public final double unassignedPercent;

            FrameSentinel(int frameNumber, long unassignedCount, double unassignedPercent) {
                this.frameNumber       = frameNumber;
                this.unassignedCount   = unassignedCount;
                this.unassignedPercent = unassignedPercent;
            }
        }
    }
}
