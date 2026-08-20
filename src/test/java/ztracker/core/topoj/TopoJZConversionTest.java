package ztracker.core.topoj;

import org.junit.jupiter.api.Test;
import ztracker.core.topoj.TopoJZConversion.ValueClass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopoJZConversion} — the standalone TopoJ value → physical Z decode.
 *
 * <p>The core of the class, and of this suite, is the contrast between the first two tests:
 * <b>the same stack, the same {@code 1.0f} pixel, opposite outcomes</b>, decided by nothing
 * but whether {@code 1.0} also happens to be a legitimate encoded value under the given
 * {@code encodingScale}. Everything else — the lattice tolerance, the range checks, the
 * statistics — exists to make that decision trustworthy.
 *
 * <p>The reference stack throughout is the motivating dataset's shape: 401 slices spanning
 * −400 µm (slice 0) to +400 µm (slice 400) at 2 µm per slice, encoded once at
 * {@code pixelDepth = 1} (uncalibrated, ambiguous) and once at {@code pixelDepth = 2}
 * (correctly calibrated, unambiguous).
 */
class TopoJZConversionTest {

    private static final int    N_SLICES = 401;
    private static final double Z_FIRST  = -400.0;
    private static final double Z_STEP   = 2.0;

    /** The reference stack encoded by an uncalibrated TopoJ run (pixelDepth 1). */
    private static TopoJZConversion ambiguousStack() {
        return new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 1.0);
    }

    /** The same stack encoded by a correctly calibrated TopoJ run (pixelDepth 2). */
    private static TopoJZConversion unambiguousStack() {
        return new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 2.0);
    }

    /** The value TopoJ would store for source slice {@code k} at {@code scale}. */
    private static float encoded(int k, double scale) {
        return (float) ((N_SLICES - k) * scale);
    }

    // ── The contrast this class exists for ────────────────────────────────────

    @Test
    void ambiguousScale_decodesBothEnds_butRefusesTheCollidingSentinel() {
        TopoJZConversion c = ambiguousStack();

        // v = 400 is slice k = 1, one step below the top of the range.
        assertEquals(ValueClass.VALID, c.classify(400.0f));
        assertEquals(-398.0, c.toZ(400.0f), 1e-9);

        // v = 2 is slice k = 399, one step above the bottom.
        assertEquals(ValueClass.VALID, c.classify(2.0f));
        assertEquals(398.0, c.toZ(2.0f), 1e-9);

        // v = 1 is BOTH the unassigned sentinel (k = 0) and slice k = 400. Two different
        // depths 800 µm apart, no way to tell them apart — so it must not become either.
        assertEquals(ValueClass.SENTINEL_UNASSIGNED, c.classify(1.0f));
        assertTrue(Double.isNaN(c.toZ(1.0f)),
                "an ambiguous 1.0 must be dropped, not resolved to one of its two readings");
    }

    @Test
    void unambiguousScale_sameStack_clampsTheSentinelToTheTopOfTheRange() {
        TopoJZConversion c = unambiguousStack();

        // Same slices as above, encoded at pixelDepth 2 — the values double, the Z does not.
        assertEquals(-398.0, c.toZ(800.0f), 1e-9);   // k = 1
        assertEquals(400.0, c.toZ(2.0f), 1e-9);      // k = 400, the bottom slice

        // 1.0 is off-lattice here (1/2 is not a slice index), so it can only be the sentinel:
        // the brightest slice was k = 0 and zFirst is provably the value TopoJ meant.
        assertEquals(ValueClass.SENTINEL_UNASSIGNED, c.classify(1.0f));
        assertEquals(Z_FIRST, c.toZ(1.0f), 1e-9);
    }

    // ── The other sentinel ────────────────────────────────────────────────────

    @Test
    void belowThresholdSentinel_isAlwaysNaN_andNeverClassifiedInvalid() {
        // Not expected from a default-settings TopoJ run (threshold defaults to 0 and the test
        // is `< threshold`), but a non-default run can produce it.
        for (TopoJZConversion c : new TopoJZConversion[]{ambiguousStack(), unambiguousStack()}) {
            assertEquals(ValueClass.SENTINEL_BELOW_THRESHOLD, c.classify(-1.0f));
            assertTrue(Double.isNaN(c.toZ(-1.0f)),
                    "-1.0 means no surface was found — never a depth, never a clamp to zFirst");
        }
    }

    // ── Ambiguity report ──────────────────────────────────────────────────────

    @Test
    void ambiguity_holdsExactlyWhenOneOverScaleIsAReachableSliceIndex() {
        // 1/scale is a positive integer <= nSlices-1 → 1.0 collides with that slice.
        assertTrue(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 1.0).ambiguity().isAmbiguous());
        assertTrue(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.5).ambiguity().isAmbiguous());
        assertTrue(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.25).ambiguity().isAmbiguous());
        assertTrue(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.2).ambiguity().isAmbiguous());

        // 1/scale is not an integer (2, 3) or not integral at all (0.3) → no collision.
        assertFalse(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 2.0).ambiguity().isAmbiguous());
        assertFalse(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 3.0).ambiguity().isAmbiguous());
        assertFalse(new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.3).ambiguity().isAmbiguous());

        // The colliding slice is nSlices - 1/scale.
        assertEquals(N_SLICES - 1,
                new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 1.0).ambiguity().collidingSliceIndex());
        assertEquals(N_SLICES - 2,
                new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.5).ambiguity().collidingSliceIndex());
        assertEquals(N_SLICES - 4,
                new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.25).ambiguity().collidingSliceIndex());
        assertEquals(N_SLICES - 5,
                new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.2).ambiguity().collidingSliceIndex());
    }

    @Test
    void ambiguity_reportsBothCandidateDepths_soTheDropCanBeExplained() {
        TopoJZConversion.Ambiguity a = ambiguousStack().ambiguity();

        assertEquals(400, a.collidingSliceIndex());
        assertEquals(-400.0, a.sentinelCandidateZ(), 1e-9);  // read as the k = 0 sentinel
        assertEquals(400.0, a.sliceCandidateZ(), 1e-9);      // read as slice 400

        // With no collision there is nothing to report, and no sentinel index to mistake for one.
        TopoJZConversion.Ambiguity none = unambiguousStack().ambiguity();
        assertThrows(IllegalStateException.class, none::collidingSliceIndex);
        assertThrows(IllegalStateException.class, none::sentinelCandidateZ);
        assertThrows(IllegalStateException.class, none::sliceCandidateZ);
    }

    // ── The lattice tolerance ─────────────────────────────────────────────────

    @Test
    void nonTerminatingScale_acceptsGenuineValuesAtBothEndsOfTheRange() {
        // 0.3 has no exact binary representation, so the float32 round-trip leaves the recovered
        // index a little off an integer — by an amount proportional to the index itself. This is
        // the case a single absolute epsilon cannot cover, and the whole reason the tolerance is
        // relative. Values are built exactly as TopoJ would store them.
        TopoJZConversion c = new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.3);

        float top = encoded(1, 0.3);              // largest stored value, largest absolute error
        assertEquals(ValueClass.VALID, c.classify(top));
        assertEquals(Z_FIRST + Z_STEP, c.toZ(top), 1e-9);

        float bottom = encoded(N_SLICES - 1, 0.3); // smallest stored value
        assertEquals(ValueClass.VALID, c.classify(bottom));
        assertEquals(Z_FIRST + Z_STEP * (N_SLICES - 1), c.toZ(bottom), 1e-9);
    }

    // ── Rejections ────────────────────────────────────────────────────────────

    @Test
    void offLatticeValue_throwsRatherThanRounding() {
        TopoJZConversion c = unambiguousStack(); // scale 2 — odd values are not on the lattice
        assertEquals(ValueClass.INVALID, c.classify(3.0f));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> c.toZ(3.0f));
        assertTrue(e.getMessage().contains("3.0"), "message must name the offending value");
        assertTrue(e.getMessage().contains("401"), "message must name the parameters it failed against");
    }

    @Test
    void outOfRangeValues_throw() {
        TopoJZConversion c = ambiguousStack();

        // nSlices * encodingScale is the k = 0 value the encoding loop can never produce.
        assertEquals(ValueClass.INVALID, c.classify(N_SLICES * 1.0f));
        assertThrows(IllegalArgumentException.class, () -> c.toZ(N_SLICES * 1.0f));

        // 0 would be slice k = nSlices, one past the bottom of the stack.
        assertEquals(ValueClass.INVALID, c.classify(0.0f));
        assertThrows(IllegalArgumentException.class, () -> c.toZ(0.0f));

        // Negatives other than the -1.0 sentinel are not encodable at all.
        assertEquals(ValueClass.INVALID, c.classify(-2.0f));
        assertThrows(IllegalArgumentException.class, () -> c.toZ(-2.0f));
    }

    @Test
    void nanIn_nanOut() {
        assertTrue(Double.isNaN(ambiguousStack().toZ(Float.NaN)));
    }

    @Test
    void constructor_rejectsUnusableParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Z_FIRST, Z_STEP, 1, 1.0));            // nSlices < 2
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 0.0));     // scale not positive
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, -1.0));    // scale negative
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Z_FIRST, 0.0, N_SLICES, 1.0));        // zStep zero
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Double.NaN, Z_STEP, N_SLICES, 1.0));  // non-finite zFirst
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Z_FIRST, Double.POSITIVE_INFINITY, N_SLICES, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, Double.NaN));
    }

    // ── Orientation ───────────────────────────────────────────────────────────

    @Test
    void negativeZStep_invertsZ_includingTheClampedSentinel() {
        // Same stack read the other way up: slice 0 at +400 µm, descending 2 µm per slice.
        TopoJZConversion c = new TopoJZConversion(400.0, -2.0, N_SLICES, 2.0);

        assertEquals(398.0, c.toZ(encoded(1, 2.0)), 1e-9);                 // k = 1
        assertEquals(-400.0, c.toZ(encoded(N_SLICES - 1, 2.0)), 1e-9);     // k = 400
        assertEquals(400.0, c.toZ(1.0f), 1e-9);                            // sentinel → zFirst
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    @Test
    void stats_countEachClass_andImplyTheSliceCountFromTheLargestValidValue() {
        TopoJZConversion c = ambiguousStack();
        TopoJZConversion.Stats s = new TopoJZConversion.Stats(c);

        s.record(400.0f);  // VALID, k = 1 — the largest encodable value
        s.record(2.0f);    // VALID, k = 399
        s.record(1.0f);    // unassigned sentinel
        s.record(-1.0f);   // below-threshold sentinel
        s.record(3.5f);    // off-lattice at scale 1

        assertEquals(2, s.count(ValueClass.VALID));
        assertEquals(1, s.count(ValueClass.SENTINEL_UNASSIGNED));
        assertEquals(1, s.count(ValueClass.SENTINEL_BELOW_THRESHOLD));
        assertEquals(1, s.count(ValueClass.INVALID));
        assertEquals(400.0, s.maxValidValue(), 1e-9);
        assertEquals(401, s.impliedMinimumSliceCount());

        // Same stack at pixelDepth 2: the largest value doubles, the implied count does not.
        TopoJZConversion.Stats s2 = new TopoJZConversion.Stats(unambiguousStack());
        s2.record(800.0f);
        assertEquals(401, s2.impliedMinimumSliceCount());

        // Nothing VALID recorded → nothing to infer, and no number that could be compared.
        TopoJZConversion.Stats empty = new TopoJZConversion.Stats(c);
        empty.record(1.0f);
        assertThrows(IllegalStateException.class, empty::impliedMinimumSliceCount);
    }

    @Test
    void impliedSliceCount_snapsAQuotientThatLandsJustBelowItsInteger() {
        // At encodingScale 1.1 the top slice stores 440.0f, whose quotient is
        // 399.99999999999994 — inside the lattice tolerance, but BELOW its integer. Flooring the
        // raw quotient would report 400 and agree with a declared nSlices of 400, i.e. the
        // cross-check would fail to fire on exactly the off-by-one it exists to catch.
        TopoJZConversion c = new TopoJZConversion(Z_FIRST, Z_STEP, N_SLICES, 1.1);
        TopoJZConversion.Stats s = new TopoJZConversion.Stats(c);

        float top = encoded(1, 1.1);
        assertEquals(ValueClass.VALID, c.classify(top));
        s.record(top);

        assertEquals(N_SLICES, s.impliedMinimumSliceCount());
    }
}
