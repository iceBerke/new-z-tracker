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
        assertTrue(msg.contains("512x384"), msg);   // what was expected
        assertTrue(msg.contains("00001.tif"), msg); // which timepoint set the expectation
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
    void digitlessName_with32BitOnly_explainsTheFrame32Collision_notAMissingNumber() {
        // 'z_origin_32bit_cells.tif' *does* carry a digit run — the 32 of "32bit" — so the
        // failure downstream is a duplicate frame number, not a missing one.
        String msg = ZProjectorPlugin.digitlessNameWarning("cells.tif", false, true);
        assertNotNull(msg);
        assertTrue(msg.contains("z_origin_32bit_cells.tif"), msg);
        assertTrue(msg.contains("frame 32"), msg);
        assertTrue(msg.contains("duplicate"), msg);
        assertTrue(!msg.contains("'z_origin_cells.tif'"), msg); // 16-bit wasn't written
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
