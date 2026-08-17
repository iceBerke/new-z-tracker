package ztracker.projector;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the plugin's own decision logic — the pure, I/O-free helpers {@code processDataset}
 * consults:
 *
 * <ul>
 *   <li>{@link ZProjectorPlugin#resolveDatasetOutDir} — the per-dataset output-folder rule.
 *       Single scope drops the redundant {@code <modeFolder>/} grouping level; batch keeps it.</li>
 *   <li>{@link ZProjectorPlugin#dimensionMismatch} / {@link ZProjectorPlugin#dimensionSkipSummary}
 *       — the p10.8 cross-timepoint size check and its per-dataset note.</li>
 *   <li>{@link ZProjectorPlugin#missingTimepointIndexError} — the p10.9 pre-flight refusal of
 *       timepoint filenames that state no index.</li>
 * </ul>
 */
class ZProjectorPluginTest {

    private static final File OUT = new File("output");

    @Test
    void singleScope_putsDatasetFolderDirectlyInOutput_noModeGroupingLevel() {
        File dir = ZProjectorPlugin.resolveDatasetOutDir(OUT, "max_z", "rec15", false);
        assertEquals("max_z_rec15", dir.getName());
        assertEquals(OUT, dir.getParentFile()); // directly inside the output folder
    }

    @Test
    void batchScope_groupsDatasetFolderUnderModeFolder() {
        File dir = ZProjectorPlugin.resolveDatasetOutDir(OUT, "max_z", "rec15", true);
        assertEquals("max_z_rec15", dir.getName());
        assertEquals("max_z", dir.getParentFile().getName());     // extra grouping level
        assertEquals(OUT, dir.getParentFile().getParentFile());
    }

    @Test
    void bothProjections_doNotCollide_evenSharingOneOutputFolderInSingleScope() {
        File max = ZProjectorPlugin.resolveDatasetOutDir(OUT, "max_z", "rec15", false);
        File min = ZProjectorPlugin.resolveDatasetOutDir(OUT, "min_z", "rec15", false);
        assertNotEquals(max.getName(), min.getName());            // distinct folder names
        assertEquals(max.getParentFile(), min.getParentFile());   // same output folder
    }

    // ── p10.8: cross-timepoint dimension check ────────────────────────────────

    @Test
    void sameDimensionsAsFirstWrittenTimepoint_isNotAMismatch() {
        assertNull(ZProjectorPlugin.dimensionMismatch("00002.tif", 512, 384,
                "00001.tif", 512, 384));
    }

    @Test
    void smallerTimepoint_isReported_namingBothFilesAndBothSizes() {
        String msg = ZProjectorPlugin.dimensionMismatch("00002.tif", 256, 384,
                "00001.tif", 512, 384);
        assertNotNull(msg);
        assertTrue(msg.contains("256x384"), msg);   // the offending size
        assertTrue(msg.contains("512x384"), msg);   // the reference size
        assertTrue(msg.contains("00001.tif"), msg); // which timepoint the reference came from
        // The reference is unverified — the message must say where it came from, so a reader
        // can suspect the reference itself rather than assuming the skipped timepoint is wrong.
        assertTrue(msg.contains("first"), msg);
    }

    @Test
    void largerTimepoint_isReportedToo_notSilentlyAccepted() {
        // Mirrors p10.1's consumer-side rule: a bigger frame is a mismatch, not a crop.
        assertNotNull(ZProjectorPlugin.dimensionMismatch("00002.tif", 1024, 768,
                "00001.tif", 512, 384));
    }

    @Test
    void heightAloneDiffering_isStillAMismatch() {
        assertNotNull(ZProjectorPlugin.dimensionMismatch("00002.tif", 512, 385,
                "00001.tif", 512, 384));
    }

    // ── p10.8: per-dataset dimension-skip summary ─────────────────────────────

    @Test
    void noSizeSkips_producesNoSummaryNote() {
        assertNull(ZProjectorPlugin.dimensionSkipSummary(0, 100, "00001.tif", 512, 384));
    }

    @Test
    void aMinorityOfSizeSkips_reportsTheCountAndReference_withoutBlamingTheReference() {
        String note = ZProjectorPlugin.dimensionSkipSummary(3, 100, "00001.tif", 512, 384);
        assertNotNull(note);
        assertTrue(note.contains("3 of 100"), note);
        assertTrue(note.contains("512x384"), note);
        assertTrue(note.contains("00001.tif"), note);
        assertTrue(!note.contains("odd one out"), note); // 3 of 100 — the skipped ones are the outliers
    }

    @Test
    void mostTimepointsSkipped_saysTheReferenceIsProbablyTheOutlier() {
        // The case a "99 skipped" line must not leave a user to infer: timepoint 1 was written
        // first, became the reference unchecked, and is itself the odd-sized one.
        String note = ZProjectorPlugin.dimensionSkipSummary(99, 100, "00001.tif", 256, 256);
        assertNotNull(note);
        assertTrue(note.contains("99 of 100"), note);
        assertTrue(note.contains("odd one out"), note);
        assertTrue(note.contains("written"), note);     // ...only because it was written first
        assertTrue(note.contains("00001.tif"), note);
    }

    // ── p10.9: pre-flight timepoint-index check ───────────────────────────────

    private static List<String> names(String... n) {
        return Arrays.asList(n);
    }

    @Test
    void everyTimepointNameCarryingDigits_isAccepted() {
        assertNull(ZProjectorPlugin.missingTimepointIndexError(
                names("0001.tif", "0002.tif", "0010.tif")));
    }

    @Test
    void digitsAnywhereInTheNameSuffice_notJustATrailingIndex() {
        // The rule is "does the name state a number at all", not where it sits.
        assertNull(ZProjectorPlugin.missingTimepointIndexError(names("scan32.tif", "t7_a.tif")));
    }

    @Test
    void oneDigitlessName_amongGoodOnes_isReportedAndNamed() {
        String msg = ZProjectorPlugin.missingTimepointIndexError(
                names("0001.tif", "cells.tif", "0003.tif"));
        assertNotNull(msg);
        assertTrue(msg.contains("cells.tif"), msg);
    }

    @Test
    void everyOffendingNameIsListedAtOnce_notJustTheFirst() {
        // Mirrors TiffStackLoader.load: report all of them up front, so one run tells the user
        // everything to rename.
        String msg = ZProjectorPlugin.missingTimepointIndexError(
                names("cells.tif", "0002.tif", "other.tif", "third.tiff"));
        assertNotNull(msg);
        assertTrue(msg.contains("cells.tif"), msg);
        assertTrue(msg.contains("other.tif"), msg);
        assertTrue(msg.contains("third.tiff"), msg);
    }

    @Test
    void error_leadsWithTheMissingTimepointIndex_notWithTheExtractorsRule() {
        // The extractor's behaviour is a symptom, not the reason: leading with it would read as
        // a consumer quirk to work around rather than malformed input.
        String msg = ZProjectorPlugin.missingTimepointIndexError(names("cells.tif"));
        assertNotNull(msg);
        assertTrue(msg.indexOf("no timepoint index") >= 0, msg);
        assertTrue(msg.indexOf("no timepoint index") < msg.indexOf("extractor"),
                "the missing index must come before any mention of the extractor: " + msg);
        assertTrue(msg.contains("Nothing has been written."), msg);
    }

    @Test
    void error_isTheSameWhicheverDepthsAreSelected_beingDepthIndependentByConstruction() {
        // The helper takes no depth arguments at all — this pins that it stays that way.
        String msg = ZProjectorPlugin.missingTimepointIndexError(names("cells.tif"));
        assertNotNull(msg);
        assertTrue(msg.contains("z_origin_<name>"), msg);
        assertTrue(msg.contains("z_origin_32bit_<name>"), msg);
    }

    // ── p10.9: pre-flight duplicate timepoint-index check ─────────────────────

    @Test
    void distinctTimepointIndices_areAccepted() {
        assertNull(ZProjectorPlugin.duplicateTimepointIndexError(
                names("0001.tif", "0002.tif", "0003.tif")));
    }

    @Test
    void twoNamesResolvingToTheSameIndex_areReported_groupedByThatIndex() {
        // The p10.4 case, caught at scan time instead of at load time.
        String msg = ZProjectorPlugin.duplicateTimepointIndexError(
                names("run1_0007.tif", "run2_0007.tif"));
        assertNotNull(msg);
        assertTrue(msg.contains("index 7"), msg);
        assertTrue(msg.contains("run1_0007.tif"), msg);
        assertTrue(msg.contains("run2_0007.tif"), msg);
    }

    @Test
    void mixedZeroPaddingWidths_areDistinctIndices_notCollisions() {
        // Guards against over-eager rejection: these are three different timepoints.
        assertNull(ZProjectorPlugin.duplicateTimepointIndexError(
                names("z_7.tif", "z_0008.tif", "z_00000009.tif")));
    }

    @Test
    void sameNumberAtDifferentPaddingWidths_doesCollide() {
        // ...but 7 and 0007 are the same index, which is why grouping is numeric, not textual.
        String msg = ZProjectorPlugin.duplicateTimepointIndexError(
                names("z_7.tif", "z_0007.tif"));
        assertNotNull(msg);
        assertTrue(msg.contains("index 7"), msg);
    }

    @Test
    void theIndexIsTheLastDigitRun_notTheFirst() {
        // 0007_run1.tif resolves to 1, so it does NOT collide with 0007.tif — matching
        // TiffStackLoader.extractFrameNumber, which takes the last run.
        assertNull(ZProjectorPlugin.duplicateTimepointIndexError(
                names("0007.tif", "0007_run1.tif")));
        // ...and it DOES collide with another name ending in 1.
        assertNotNull(ZProjectorPlugin.duplicateTimepointIndexError(
                names("0007_run1.tif", "0009_run1.tif")));
    }

    @Test
    void wellFormedNamesAreNotBlamed_onlyTheCollidingOnesAreListed() {
        String msg = ZProjectorPlugin.duplicateTimepointIndexError(
                names("0001.tif", "run1_0007.tif", "run2_0007.tif", "0002.tif"));
        assertNotNull(msg);
        // Assert on the collision group itself, not the whole message: the rename hint cites
        // "e.g. 0001.tif, 0002.tif" as *good* examples, so a plain contains() on those names
        // would false-alarm (the same trap TiffStackLoader's own help text sets).
        assertTrue(msg.contains("[index 7 ← [run1_0007.tif, run2_0007.tif]]"),
                "only the two colliding names should be grouped: " + msg);
    }

    @Test
    void digitFreeNamesAreLeftToTheOtherCheck_notCollapsedIntoOneIndexHere() {
        // Two digit-free names must not be reported as an index collision — that would blame the
        // wrong thing. The digit-free check runs first and refuses them on its own terms.
        assertNull(ZProjectorPlugin.duplicateTimepointIndexError(names("cells.tif", "other.tif")));
    }

    // ── p10.9: write-nothing dialog text ──────────────────────────────────────

    @Test
    void writeNothingMessage_statesHowManyUnitsProducedNothing_andTheReasonInline() {
        String msg = ZProjectorPlugin.writeNothingMessage(1,
                names("MAX_Z 'rec15': These timepoint files carry no timepoint index."));
        assertTrue(msg.contains("Nothing was written."), msg);
        assertTrue(msg.contains("0 of 1"), msg);
        assertTrue(msg.contains("Reason:"), msg);           // singular for one failure
        assertTrue(msg.contains("rec15"), msg);             // the reason is inline, not deferred
        assertTrue(!msg.contains("Log window"), msg);       // ...so no "see the Log" for one
    }

    @Test
    void writeNothingMessage_pluralisesAndCapsTheReasons_statingHowManyItOmitted() {
        // A batch run with many failed datasets must not build an unreadable dialog — but the
        // count it left out has to be explicit, not silently dropped.
        String msg = ZProjectorPlugin.writeNothingMessage(6,
                names("a: r", "b: r", "c: r", "d: r", "e: r", "f: r"));
        assertTrue(msg.contains("0 of 6"), msg);
        assertTrue(msg.contains("Reasons:"), msg);          // plural
        assertTrue(msg.contains("…and 3 more"), msg);       // 6 - 3 shown
        assertTrue(msg.contains("Log window"), msg);
    }

    @Test
    void writeNothingMessage_wrapsEveryLine_sinceMultiLineLabelDoesNot() {
        // ij's MessageDialog renders through MultiLineLabel, which splits on '\n' only and does
        // no word wrapping — an unwrapped 200-char line becomes a dialog wider than the screen.
        String longReason = "MAX_Z 'rec15': These timepoint files carry no timepoint index — their"
                + " names contain no digits: [cells.tif, other.tif]. A timepoint's identity is the"
                + " number in its filename, so a digit-free name leaves the file with no position"
                + " in the sequence and this dataset with no timepoint ordering at all.";
        String msg = ZProjectorPlugin.writeNothingMessage(1, names(longReason));
        for (String line : msg.split("\n", -1)) {
            assertTrue(line.length() <= 90, "line too wide for a dialog (" + line.length() + "): " + line);
        }
    }

    @Test
    void writeNothingMessage_keepsBlankLinesAsASpace_soTheySurviveMultiLineLabel() {
        // StringTokenizer skips empty tokens, so a truly empty line would collapse.
        String msg = ZProjectorPlugin.writeNothingMessage(1, names("a: r"));
        assertTrue(msg.contains("\n \n"), msg);
    }
}
