package ztracker.projector;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests {@link ZProjectorPlugin#resolveDatasetOutDir} — the per-dataset output-folder rule.
 * Single scope drops the redundant {@code <modeFolder>/} grouping level; batch keeps it.
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
}
