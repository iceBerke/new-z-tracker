package ztracker.core.topoj;

/**
 * Decodes a Fiji <b>TopoJ</b> height-map pixel value into a physical Z coordinate.
 *
 * <p><b>Where it is used.</b> {@link TopoJStackDecoder} applies this across a whole loaded
 * stack, and {@link ztracker.topoj.TopoJTrackerPlugin} runs that decode immediately after
 * loading, before any sampling happens. The four parameters come from the user, collected in
 * the dialog's Step 2 — see the note on why they are declared rather than read from the file
 * in {@code docs/DECISIONS.md}. This class itself stays pure computation: no ImageJ types,
 * no I/O, no logging.
 *
 * <h3>What TopoJ actually writes</h3>
 *
 * <p>{@code Compute_Topography} does <b>not</b> write a physical depth. For a source stack
 * of {@code nSlices} slices it writes
 *
 * <pre>    v = (nSlices - k) * pixelDepth</pre>
 *
 * where {@code k} is the <b>0-based source slice index</b> of the brightest slice and
 * {@code pixelDepth} is the source stack's voxel depth <i>as calibrated when TopoJ ran</i>.
 * So {@code v} is a <b>reversed slice counter scaled by that calibration</b> — large
 * {@code v} means a <i>low</i> {@code k}. The loop runs {@code k = 1..nSlices-1}, so
 * {@code k = 0} is unreachable through the formula and the value
 * {@code nSlices * pixelDepth} never appears in a height map.
 *
 * <p>Two sentinels share the same array and are <b>not</b> on that lattice:
 *
 * <ul>
 *   <li>{@code 1.0f} — the hardcoded initialisation literal, <b>never scaled by
 *       pixelDepth</b>. It means the brightest slice was {@code k = 0}: the surface sits at
 *       or above the top of the acquired range.</li>
 *   <li>{@code -1.0f} — written where the maximum intensity fell below the user's
 *       threshold, i.e. no surface was found. The threshold defaults to {@code 0} and the
 *       test is {@code < threshold}, so <b>a default-settings TopoJ run cannot produce
 *       this value</b>. It is handled because a non-default run can, not because it is
 *       expected.</li>
 * </ul>
 *
 * <h3>{@code encodingScale} is not {@code zStep}</h3>
 *
 * <p>{@link #zStep} is the true physical spacing between source slices. {@code encodingScale}
 * is whatever {@code pixelDepth} the source stack carried <i>at the moment TopoJ ran</i>.
 * They are equal only when the stack was calibrated correctly, and the motivating dataset is
 * precisely the case where they are not: an uncalibrated stack ({@code pixelDepth = 1})
 * whose true slice spacing is {@code 2} µm. Conflating them, or defaulting one from the
 * other, silently rescales every Z. They are therefore two independent constructor
 * parameters with no relationship enforced between them.
 *
 * <h3>Z convention</h3>
 *
 * <p>Physical Z is {@code zFirst + zStep * k} for source slice {@code k}, so {@code zFirst}
 * is by definition the Z of slice {@code k = 0}. {@code zStep} may be negative (Z decreasing
 * with slice index); nothing here assumes an orientation.
 */
public class TopoJZConversion {

    /** What a raw TopoJ height-map value means. */
    public enum ValueClass {
        /** Exactly {@code 1.0f}: TopoJ never assigned this pixel, so its brightest slice was {@code k = 0}. */
        SENTINEL_UNASSIGNED,
        /** Exactly {@code -1.0f}: maximum intensity was below the threshold, so no surface was found. */
        SENTINEL_BELOW_THRESHOLD,
        /** On the encoding lattice and within the reachable slice range. */
        VALID,
        /** Anything else — off-lattice, out of range, non-finite. */
        INVALID
    }

    /** The initialisation literal TopoJ leaves in a pixel it never assigned. Never scaled. */
    private static final float UNASSIGNED = 1.0f;

    /** The value TopoJ writes where max intensity fell below the threshold. */
    private static final float BELOW_THRESHOLD = -1.0f;

    /**
     * Relative tolerance for "is {@code v / encodingScale} an integer": {@code 2^-21}, i.e.
     * <b>eight times</b> float32's {@code 2^-24} half-ulp.
     *
     * <p><b>Why relative and not absolute.</b> TopoJ computes {@code (nSlices - k) * pixelDepth}
     * in {@code double} and stores it as {@code float32}, so the stored value carries a
     * <i>relative</i> error of at most {@code 2^-24}. Dividing by {@code encodingScale}
     * recovers the lattice index {@code q = nSlices - k} with that same relative error, so the
     * <i>absolute</i> error in {@code q} is proportional to {@code q} itself: it is largest at
     * the top of the range ({@code q = nSlices-1}, i.e. {@code k = 1}, the largest stored
     * value) and smallest at {@code q = 1} ({@code k = nSlices-1}). A single absolute epsilon
     * cannot serve both ends — tight enough for {@code q = 1} it rejects genuine values at
     * {@code q = 400}; loose enough for {@code q = 400} it accepts off-lattice values at
     * {@code q = 1}. The tolerance therefore scales with the magnitude of the recovered index:
     * {@code TOLERANCE * max(1, |q|)}. The {@code max(1, ...)} floor keeps the window from
     * collapsing to zero as {@code q} approaches 0, where the relative model has nothing left
     * to scale.
     *
     * <p><b>Why 8x the half-ulp.</b> The error budget is one float32 rounding
     * ({@code 2^-24}) plus one double division ({@code 2^-53}, negligible); {@code 8x} leaves
     * an order of magnitude of headroom for a {@code pixelDepth} the caller reproduced to a
     * few ulps rather than bit-exactly, while staying more than three orders of magnitude
     * below the {@code 0.5} that would let a genuinely off-lattice value pass. At
     * {@code nSlices = 401} the widest window is {@code 400 * 2^-21 ~ 1.9e-4}.
     *
     * <p><b>What it does not protect against.</b> (1) A wrong {@code encodingScale} that is an
     * integer multiple or divisor of the true one — reading a {@code pixelDepth 2} map as
     * {@code 1} leaves every value on the lattice and in range, at the wrong {@code k}. This
     * check cannot see it, and it is exactly the motivating dataset's hazard. (2) A wrong
     * {@code nSlices}, which shifts every Z by a constant (see {@link Stats#impliedMinimumSliceCount()}).
     * (3) A value corrupted from one lattice point onto another. (4) A file that is not a
     * TopoJ height map at all but whose values happen to be multiples of {@code encodingScale}.
     * The lattice test is a consistency check, never a proof of provenance.
     *
     * <p><b>Smallest usable {@code encodingScale}.</b> Because the test is relative it is
     * scale-invariant, so the only floor is float32's own representable range: the test stays
     * reliable for {@code nSlices = 401} while every lattice value
     * {@code q * encodingScale, q in [1, 400]} is a <b>normal</b> finite float — i.e. down to
     * {@code encodingScale ~ 1.2e-38} (below that the smallest value {@code 1 * encodingScale}
     * goes subnormal and loses the {@code 2^-24} guarantee) and up to
     * {@code encodingScale ~ 8.4e35} (above that the largest value {@code 400 * encodingScale}
     * overflows to infinity). Every physically meaningful µm step sits many orders of
     * magnitude inside that window; non-terminating binary fractions such as {@code 0.3} or
     * {@code 0.1} are fully supported and are the reason the tolerance exists.
     */
    private static final double LATTICE_TOLERANCE = 0x1.0p-21;

    private final double zFirst;
    private final double zStep;
    private final int    nSlices;
    private final double encodingScale;
    private final Ambiguity ambiguity;

    /**
     * @param zFirst        physical Z of source slice {@code k = 0}
     * @param zStep         physical Z per slice; may be negative, must not be zero
     * @param nSlices       source stack slice count; at least 2
     * @param encodingScale the {@code pixelDepth} TopoJ was run with; must be positive.
     *                      <b>Not</b> {@code zStep} — see the class javadoc.
     * @throws IllegalArgumentException if any parameter is non-finite, {@code nSlices < 2},
     *                                  {@code encodingScale <= 0}, or {@code zStep == 0}
     */
    public TopoJZConversion(double zFirst, double zStep, int nSlices, double encodingScale) {
        // Finiteness first: NaN fails every ordering comparison silently (NaN <= 0 is false),
        // so a NaN encodingScale would slip past the positivity check below.
        if (!Double.isFinite(zFirst)) {
            throw new IllegalArgumentException("zFirst must be finite, got: " + zFirst);
        }
        if (!Double.isFinite(zStep)) {
            throw new IllegalArgumentException("zStep must be finite, got: " + zStep);
        }
        if (!Double.isFinite(encodingScale)) {
            throw new IllegalArgumentException("encodingScale must be finite, got: " + encodingScale);
        }
        if (nSlices < 2) {
            throw new IllegalArgumentException(
                    "nSlices must be at least 2 (TopoJ encodes k = 1..nSlices-1), got: " + nSlices);
        }
        if (encodingScale <= 0.0) {
            throw new IllegalArgumentException(
                    "encodingScale must be positive (it is the pixelDepth TopoJ was run with), got: "
                    + encodingScale);
        }
        if (zStep == 0.0) {
            throw new IllegalArgumentException(
                    "zStep must not be zero — every slice would map to the same Z");
        }

        this.zFirst        = zFirst;
        this.zStep         = zStep;
        this.nSlices       = nSlices;
        this.encodingScale = encodingScale;

        // Derived, never configured: 1.0 is ambiguous exactly when it is ALSO a legitimate
        // encoded value under these parameters — which is precisely what isEncodedSlice asks.
        // Asking the classifier rather than re-deriving the condition keeps the two from
        // drifting apart.
        if (isEncodedSlice(UNASSIGNED)) {
            int collidingK = nSlices - (int) latticeIndex(UNASSIGNED);
            this.ambiguity = Ambiguity.colliding(collidingK, zFirst, zFirst + zStep * collidingK);
        } else {
            this.ambiguity = Ambiguity.none();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Classifies a raw height-map value.
     *
     * <p>Both sentinels are tested <b>first, by exact float equality</b>, before the lattice
     * test — otherwise {@code 1.0f} would be silently decoded as a depth whenever it also
     * happens to sit on the lattice (see {@link #ambiguity()}).
     *
     * <p><b>Can {@code -1.0f} also be on-lattice? No, and not by luck.</b> A legitimate
     * encoded value is {@code q * encodingScale} with {@code q in [1, nSlices-1]} and
     * {@code encodingScale > 0} (enforced at construction), so every legitimate value is
     * <b>strictly positive</b>. The range test therefore excludes every negative value under
     * every parameter set, and the ordering of the two tests is not load-bearing for
     * {@code -1.0f}. It is load-bearing for {@code 1.0f}.
     *
     * <p>{@code NaN} and the infinities classify as {@link ValueClass#INVALID}: they are not
     * sentinels and cannot be on the lattice. {@link #toZ(float)} handles {@code NaN}
     * separately, before classification, so a {@code NaN} pixel passes through rather than
     * throwing.
     */
    public ValueClass classify(float v) {
        if (v == UNASSIGNED)       return ValueClass.SENTINEL_UNASSIGNED;
        if (v == BELOW_THRESHOLD)  return ValueClass.SENTINEL_BELOW_THRESHOLD;
        return isEncodedSlice(v) ? ValueClass.VALID : ValueClass.INVALID;
    }

    /**
     * Converts a raw height-map value to a physical Z coordinate in the same units as
     * {@code zFirst}/{@code zStep}.
     *
     * <ul>
     *   <li>{@link ValueClass#VALID} → {@code zFirst + zStep * k}, {@code k = nSlices - v/encodingScale}.</li>
     *   <li>{@link ValueClass#SENTINEL_UNASSIGNED} → {@code zFirst} when 1.0 is
     *       <b>unambiguous</b> under these parameters: it can then only mean {@code k = 0}, so
     *       clamping to the top of the range is provably the value TopoJ meant. When 1.0 is
     *       <b>ambiguous</b> → {@code NaN}, because the pixel is genuinely two different
     *       depths and picking either would invent data. {@link #ambiguity()} carries both
     *       candidates so a caller can say what was refused and why.</li>
     *   <li>{@link ValueClass#SENTINEL_BELOW_THRESHOLD} → always {@code NaN}. It means no
     *       surface was found, which is not the same claim as a surface at {@code k = 0}, so
     *       it is never clamped.</li>
     *   <li>{@code NaN} in → {@code NaN} out.</li>
     *   <li>{@link ValueClass#INVALID} → throws.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the value is neither sentinel nor a legitimate
     *         encoded slice, naming the value and the parameters it failed against
     */
    public double toZ(float v) {
        if (Float.isNaN(v)) return Double.NaN;

        switch (classify(v)) {
            case SENTINEL_UNASSIGNED:
                return ambiguity.isAmbiguous() ? Double.NaN : zFirst;
            case SENTINEL_BELOW_THRESHOLD:
                return Double.NaN;
            case VALID:
                // No automated test reaches this formula from a real TopoJ file — every test of
                // this class and of TopoJStackDecoder builds arrays in memory, and
                // TopoJStackLoaderTest stops at the loader. The suite can stay green while these
                // numbers move; only a manual Fiji run checks them. See docs/BACKLOG.md,
                // "An end-to-end decode test over a committed TopoJ fixture".
                return zFirst + zStep * (nSlices - latticeIndex(v));
            default:
                throw new IllegalArgumentException(describeInvalid(v));
        }
    }

    /** How {@code 1.0} behaves under these parameters — derived at construction, never configured. */
    public Ambiguity ambiguity() {
        return ambiguity;
    }

    /**
     * The declared source-stack slice count, as given at construction.
     *
     * <p>Read-only view of an immutable field, exposed so a caller comparing an <i>observed</i>
     * slice count against the declared one — see {@link Stats#impliedMinimumSliceCount()} — can
     * read both from the same object instead of carrying the number alongside it, where the two
     * could drift apart.
     */
    public int nSlices() {
        return nSlices;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** True when {@code v} sits on the encoding lattice AND within the reachable slice range. */
    private boolean isEncodedSlice(float v) {
        double q = latticeIndex(v);
        return !Double.isNaN(q) && q >= 1.0 && q <= nSlices - 1;
    }

    /**
     * The lattice index {@code q = v / encodingScale} snapped to the nearest integer, or
     * {@code NaN} when {@code v} is not within {@link #LATTICE_TOLERANCE} of one. Snapping
     * happens only <i>inside</i> the tolerance — a value outside it is rejected, never rounded.
     */
    private double latticeIndex(float v) {
        double q = ((double) v) / encodingScale;
        if (!Double.isFinite(q)) return Double.NaN;
        double nearest = Math.rint(q);
        double window  = LATTICE_TOLERANCE * Math.max(1.0, Math.abs(q));
        return Math.abs(q - nearest) <= window ? nearest : Double.NaN;
    }

    private String describeInvalid(float v) {
        double q = ((double) v) / encodingScale;
        return String.format(
                "TopoJ height-map value %s is not a valid encoded slice: "
                + "%s / encodingScale %s = %s, which is not an integer in [1, %d] "
                + "within the relative tolerance %s "
                + "(nSlices=%d, so legitimate values are k*%s for k = 1..%d, plus the "
                + "sentinels 1.0 and -1.0)",
                v, v, encodingScale, q, nSlices - 1, LATTICE_TOLERANCE,
                nSlices, encodingScale, nSlices - 1);
    }

    // ── Ambiguity report ──────────────────────────────────────────────────────

    /**
     * Whether the {@code 1.0} sentinel collides with a legitimate encoded value, and — when it
     * does — both readings it could carry.
     *
     * <p>The collision happens exactly when {@code 1 / encodingScale} is a positive integer
     * {@code <= nSlices-1}, since then some slice {@code k = nSlices - 1/encodingScale} encodes
     * to {@code 1.0} as well. True at {@code encodingScale} 1, 0.5, 0.25 and 0.2; false at 2, 3
     * and 0.3.
     *
     * <p>This report carries <b>no policy of its own</b>: it states the collision and the two
     * candidate depths, and {@link TopoJZConversion#toZ(float)} derives its behaviour from it.
     * Exposing both candidates is what lets a caller explain a dropped pixel — "1.0 here is
     * either the top of the stack or slice 400, {@code -400.0} or {@code +400.0} µm; there is
     * no way to tell, so it was dropped" — rather than dropping it silently.
     */
    public static class Ambiguity {

        private final boolean ambiguous;
        private final int     collidingSliceIndex;
        private final double  sentinelCandidateZ;
        private final double  sliceCandidateZ;

        private Ambiguity(boolean ambiguous, int collidingSliceIndex,
                          double sentinelCandidateZ, double sliceCandidateZ) {
            this.ambiguous           = ambiguous;
            this.collidingSliceIndex = collidingSliceIndex;
            this.sentinelCandidateZ  = sentinelCandidateZ;
            this.sliceCandidateZ     = sliceCandidateZ;
        }

        static Ambiguity none() {
            return new Ambiguity(false, -1, Double.NaN, Double.NaN);
        }

        static Ambiguity colliding(int collidingSliceIndex,
                                   double sentinelCandidateZ, double sliceCandidateZ) {
            return new Ambiguity(true, collidingSliceIndex, sentinelCandidateZ, sliceCandidateZ);
        }

        /** True when {@code 1.0} is both the unassigned sentinel and a legitimate encoded value. */
        public boolean isAmbiguous() {
            return ambiguous;
        }

        /**
         * The source slice index {@code nSlices - 1/encodingScale} whose encoded value is also
         * {@code 1.0}.
         *
         * @throws IllegalStateException if {@code 1.0} is not ambiguous — there is no colliding
         *         slice to name, and returning a sentinel index would invite it being used as one
         */
        public int collidingSliceIndex() {
            requireAmbiguous();
            return collidingSliceIndex;
        }

        /** Z if {@code 1.0} is read as the sentinel ({@code k = 0}): {@code zFirst}.
         *  @throws IllegalStateException if {@code 1.0} is not ambiguous */
        public double sentinelCandidateZ() {
            requireAmbiguous();
            return sentinelCandidateZ;
        }

        /** Z if {@code 1.0} is read as the colliding slice: {@code zFirst + zStep * collidingSliceIndex}.
         *  @throws IllegalStateException if {@code 1.0} is not ambiguous */
        public double sliceCandidateZ() {
            requireAmbiguous();
            return sliceCandidateZ;
        }

        private void requireAmbiguous() {
            if (!ambiguous) {
                throw new IllegalStateException(
                        "1.0 is unambiguous under these parameters — there is no colliding slice; "
                        + "check isAmbiguous() first");
            }
        }
    }

    // ── Statistics accumulator ────────────────────────────────────────────────

    /**
     * Mutable tally of how a set of height-map values classified, plus the largest
     * {@link ValueClass#VALID} value seen. Counting and arithmetic only — no thresholds, no
     * verdicts, no logging; a caller decides what any of it means.
     *
     * <p>Not thread-safe, and deliberately so: it is meant to be fed by one pass over one
     * stack.
     */
    public static class Stats {

        private final TopoJZConversion conversion;
        private final long[] counts = new long[ValueClass.values().length];
        /** Kept as the raw {@code float} so {@link #impliedMinimumSliceCount()} can re-snap it
         *  through the same lattice rule that classified it. */
        private float maxValid = Float.NaN;

        public Stats(TopoJZConversion conversion) {
            if (conversion == null) {
                throw new IllegalArgumentException("conversion must not be null");
            }
            this.conversion = conversion;
        }

        /** Classifies {@code v} and folds it into the tally. */
        public void record(float v) {
            ValueClass c = conversion.classify(v);
            counts[c.ordinal()]++;
            if (c == ValueClass.VALID && (Float.isNaN(maxValid) || v > maxValid)) {
                maxValid = v;
            }
        }

        /** How many recorded values fell into {@code c}. */
        public long count(ValueClass c) {
            return counts[c.ordinal()];
        }

        /** The largest VALID value recorded, or {@code NaN} if none was. */
        public double maxValidValue() {
            return Float.isNaN(maxValid) ? Double.NaN : maxValid;
        }

        /**
         * The smallest {@code nSlices} consistent with the largest VALID value seen:
         * {@code maxValid / encodingScale} snapped to its lattice index, plus one.
         *
         * <p><b>Why this is the only cross-check available.</b> {@code nSlices} enters the
         * decode as {@code k = nSlices - q}, i.e. purely as an additive constant, so a wrong
         * {@code nSlices} shifts <b>every</b> Z by the same amount and leaves the lattice and
         * range tests completely satisfied. No per-pixel check can see it. The largest encoded
         * value does bound it from below, though: some pixel encoded slice
         * {@code k = nSlices - maxValid/encodingScale >= 1}, so {@code nSlices} is at least
         * {@code maxValid/encodingScale + 1}.
         *
         * <p><b>Diagnostic, not a guarantee.</b> It is a lower bound from observed data: a
         * stack whose top slice is never the brightest anywhere reports less than its true
         * {@code nSlices}, which is normal and not an error. Only a declared {@code nSlices}
         * <i>below</i> this number is provably wrong.
         *
         * <p><b>Why the quotient is snapped rather than floored.</b> {@link ValueClass#VALID}
         * guarantees the quotient is within {@link #LATTICE_TOLERANCE} of an integer — <i>not</i>
         * that it sits on the high side of it. Float round-trip error routinely lands it a hair
         * below: at {@code encodingScale = 1.1}, slice {@code k = 1} of a 401-slice stack stores
         * {@code 440.0f}, whose quotient is {@code 399.99999999999994}, and a raw
         * {@code floor(...) + 1} reports <b>400</b> where the true implied minimum is 401. That
         * is precisely the off-by-one this number exists to detect, so a raw floor makes the
         * cross-check silently fail to fire in the one case it is for — it would agree with a
         * declared {@code nSlices} of 400 and flag a correct 401. Re-snapping through the same
         * rule that classified the value costs nothing and cannot disagree with it.
         *
         * @throws IllegalStateException if no VALID value has been recorded — there is nothing
         *         to infer from, and any number returned here would be compared against the
         *         declared {@code nSlices} as though it meant something
         */
        public int impliedMinimumSliceCount() {
            if (Float.isNaN(maxValid)) {
                throw new IllegalStateException(
                        "no VALID value recorded — implied slice count is undefined");
            }
            // Non-NaN by construction: maxValid was classified VALID by this same conversion,
            // which is immutable, so the lattice test that accepted it still accepts it.
            return (int) conversion.latticeIndex(maxValid) + 1;
        }
    }
}
