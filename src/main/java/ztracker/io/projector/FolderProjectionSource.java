package ztracker.io.projector;

import ztracker.io.projector.ProjectionInputScanner.DatasetScan;
import ztracker.io.projector.ProjectionInputScanner.TimepointStack;
import ztracker.project.ZProjector;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * {@link ProjectionSource} over the original z-layer-sub-folder layout — a thin adapter
 * around {@link ProjectionInputScanner}, which is left <b>byte-for-byte unchanged</b>.
 *
 * <p>Every timepoint still goes through the exact same path it always has
 * ({@link ProjectionInputScanner#loadTimepoint} then {@link ZProjector#project}), so adding
 * the stack input type cannot alter this layout's results or its memory profile.
 */
public final class FolderProjectionSource implements ProjectionSource {

    private final DatasetScan scan;

    public FolderProjectionSource(DatasetScan scan) {
        this.scan = scan;
    }

    /** Scans {@code datasetDir} for its numeric z-layer sub-folders and timepoint filenames. */
    public static FolderProjectionSource scanDataset(File datasetDir) throws IOException {
        return new FolderProjectionSource(ProjectionInputScanner.scanDataset(datasetDir));
    }

    @Override public File datasetDir()             { return scan.datasetDir; }
    @Override public List<String> timepointLabels() { return scan.timepointFilenames; }
    @Override public List<String> zLayerNames()     { return scan.zLayerNames; }
    @Override public double[] zValues()             { return scan.zValues; }

    @Override
    public Projected projectTimepoint(ZProjector.Mode mode, String label) throws IOException {
        TimepointStack ts = ProjectionInputScanner.loadTimepoint(scan, label);
        return new Projected(ZProjector.project(mode, ts.slices, ts.globalZIndex),
                ts.sourceBitDepth);
    }
}
