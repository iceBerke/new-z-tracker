package ztracker.projector;

import org.junit.jupiter.api.Test;

import java.io.File;

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
 *   <li>{@link ZProjectorPlugin#dimensionMismatch} — the p10.8 cross-timepoint size check.</li>
 *   <li>{@link ZProjectorPlugin#digitlessNameWarning} — the p10.8 unusable-output-name warning.</li>
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

    // ── p10.8: digit-less output-name warning ─────────────────────────────────

    @Test
    void timepointNameWithDigits_producesNoWarning() {
        assertNull(ZProjectorPlugin.digitlessNameWarning("00001.tif", true, true));
    }

    @Test
    void digitsAnywhereInTheNameSuffice_matchingTool2sOwnRule() {
        // Tool 2 only asks "is there a digit run?", so neither does this warning — it does not
        // second-guess whether the digits are in a sensible place.
        assertNull(ZProjectorPlugin.digitlessNameWarning("scan32.tif", true, true));
    }

    @Test
    void digitlessName_with16BitOnly_namesTheZOriginFileAndSaysTheFolderIsRefused() {
        String msg = ZProjectorPlugin.digitlessNameWarning("cells.tif", true, false);
        assertNotNull(msg);
        assertTrue(msg.contains("z_origin_cells.tif"), msg);
        assertTrue(msg.contains("refuses"), msg);
        assertTrue(!msg.contains("z_origin_32bit_"), msg); // 32-bit wasn't written — don't claim it was
    }

    @Test
    void digitlessName_with32BitOnly_leadsWithTheSilentWrongFrame_notARefusal() {
        // 'z_origin_32bit_cells.tif' *does* carry a digit run — the 32 of "32bit" — so mixed with
        // normally-named timepoints Tool 2 loads the folder with no error and puts this one at
        // frame 32. Verified empirically: frames [7, 32] load fine for cells.tif + 0007.tif.
        // That silent outcome is worse than a refusal, so the message must not promise a refusal.
        String msg = ZProjectorPlugin.digitlessNameWarning("cells.tif", false, true);
        assertNotNull(msg);
        assertTrue(msg.contains("z_origin_32bit_cells.tif"), msg);
        assertTrue(msg.contains("frame 32"), msg);
        assertTrue(msg.contains("NO error"), msg);      // the silent case is stated outright
        assertTrue(msg.contains("silently"), msg);
        assertTrue(msg.contains("only if"), msg);       // refusal is the conditional case
        assertTrue(msg.contains("duplicate"), msg);
        assertTrue(!msg.contains("'z_origin_cells.tif'"), msg); // 16-bit wasn't written
    }

    @Test
    void digitlessNameBrief_isOneLine_andStillNamesTheOutputFiles() {
        // The second-and-later form: same facts, explanation not restated.
        String brief = ZProjectorPlugin.digitlessNameBrief("cells_b.tif", true, true);
        assertNotNull(brief);
        assertEquals(1, brief.split("\n", -1).length, "must be a single line: " + brief);
        assertTrue(brief.contains("cells_b.tif"), brief);
        assertTrue(brief.contains("z_origin_cells_b.tif"), brief);
        assertTrue(brief.contains("z_origin_32bit_cells_b.tif"), brief);
    }

    @Test
    void digitlessNameBrief_namesOnlyTheDepthsBeingWritten() {
        String only16 = ZProjectorPlugin.digitlessNameBrief("cells.tif", true, false);
        assertTrue(only16.contains("z_origin_cells.tif"), only16);
        assertTrue(!only16.contains("32bit"), only16);

        String only32 = ZProjectorPlugin.digitlessNameBrief("cells.tif", false, true);
        assertTrue(only32.contains("z_origin_32bit_cells.tif"), only32);
        assertTrue(!only32.contains("'z_origin_cells.tif'"), only32);
    }

    @Test
    void digitlessNameBrief_staysSilentForANameWithDigits_sameAsTheFullForm() {
        assertNull(ZProjectorPlugin.digitlessNameBrief("00001.tif", true, true));
    }

    @Test
    void digitlessName_withBothDepths_describesEachOutputSeparately() {
        String msg = ZProjectorPlugin.digitlessNameWarning("cells.tif", true, true);
        assertNotNull(msg);
        assertTrue(msg.contains("z_origin_cells.tif"), msg);
        assertTrue(msg.contains("z_origin_32bit_cells.tif"), msg);
        assertTrue(msg.contains("Rename"), msg);
    }
}
