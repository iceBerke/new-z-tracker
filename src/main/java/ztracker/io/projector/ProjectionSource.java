package ztracker.io.projector;

import ztracker.project.ZProjector;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * One dataset's worth of projection input, whatever its on-disk layout — the single
 * contract {@code ZProjectorPlugin} drives so its per-timepoint loop stays layout-agnostic.
 *
 * <p>Two implementations, one per input type the projection tool accepts:
 * <ul>
 *   <li>{@link FolderProjectionSource} — the original layout: one sub-folder per Z depth
 *       (named by its Z value), each holding one TIFF per timepoint. Z comes from the
 *       folder names.</li>
 *   <li>{@link ProjectionStackScanner.StackScan} — one multi-slice TIFF <em>stack</em> per
 *       timepoint, its slices being the Z layers. Z comes from the ImageJ slice labels.</li>
 * </ul>
 *
 * <p>Note the unit of work is <b>project one timepoint</b>, not "load one timepoint":
 * that lets the stack implementation stream slice-by-slice into
 * {@link ZProjector.Accumulator} without ever holding the whole stack in memory (a single
 * timepoint can be hundreds of MB), while the folder implementation keeps its existing
 * load-then-{@link ZProjector#project} behaviour byte-for-byte.
 */
public interface ProjectionSource {

    /** The dataset folder this source reads from (its name is used in output paths). */
    File datasetDir();

    /**
     * The per-timepoint labels, in processing order. These double as the <em>output</em>
     * filenames: a timepoint labelled {@code 00001.tif} produces {@code z_origin_00001.tif}.
     */
    List<String> timepointLabels();

    /** Display names of the Z layers, parallel to {@link #zValues()} — logging only. */
    List<String> zLayerNames();

    /** Physical Z of each layer (µm), ascending; index → Z, exactly as written to the JSON mapping. */
    double[] zValues();

    /**
     * Projects a single timepoint.
     *
     * @param mode  which extreme wins
     * @param label one of {@link #timepointLabels()}
     * @return the projection + z-origin index map, plus the source bit depth
     * @throws IOException if the timepoint cannot be read, or its layers disagree with the
     *                     dataset structure discovered at scan time (the caller skips just
     *                     that timepoint and logs it)
     */
    Projected projectTimepoint(ZProjector.Mode mode, String label) throws IOException;

    /** A projected timepoint plus the bit depth of the images it came from. */
    final class Projected {
        public final ZProjector.Result result;
        /** Bit depth of the source images (8/16/32) — used to normalize the raw projection. */
        public final int sourceBitDepth;

        public Projected(ZProjector.Result result, int sourceBitDepth) {
            this.result         = result;
            this.sourceBitDepth = sourceBitDepth;
        }
    }
}
