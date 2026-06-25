package ztracker.ui;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ztracker.core.FrameAligner;
import ztracker.core.ZAggregator;
import ztracker.core.ZSampler;
import ztracker.export.TrackExportManager.ExportConfig;
import ztracker.io.TiffStackLoader.LoadedStack;
import ztracker.io.TrackCsvLoader.ColumnConfig;
import ztracker.io.TrackCsvLoader.CsvConfig;
import ztracker.model.TrackData;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

/**
 * Collects all plugin parameters via a sequence of {@link GenericDialog} panels.
 *
 * <p>Because loading must happen between dialog steps, the dialog exposes three
 * run-groups that the plugin interleaves with I/O:
 * <ol>
 *   <li>{@link #runSteps1And2()} — file paths + CSV format</li>
 *   <li>{@link #runStep3Columns()} — column name confirmation (after CSV headers are read)</li>
 *   <li>{@link #runSteps4To6()} — frame offset, methods, export (after data is loaded)</li>
 * </ol>
 *
 * <p>All dialogs are modal. Returns false on any cancellation.
 */
public class ZTrackerDialog {

    // ── Collected parameters (populated after run groups complete) ────────────

    public File jsonFile;
    public File tiffFolder;
    public File csvFile;

    public CsvConfig    csvConfig;
    public ColumnConfig columnConfig;   // set by plugin after auto-detection, then confirmed here

    public int                frameOffset;
    public ZSampler.Method    samplingMethod;
    public ZAggregator.Method aggregationMethod;

    public ExportConfig exportConfig;
    public File         outputDir;

    // ── State injected by the plugin between steps ────────────────────────────

    private TrackData   loadedTrack;
    private LoadedStack loadedStack;

    public void setLoadedData(TrackData track, LoadedStack stack) {
        this.loadedTrack = track;
        this.loadedStack = stack;
    }

    // ── Run groups ────────────────────────────────────────────────────────────

    /** Shows Steps 1 and 2 (file paths + CSV format). */
    public boolean runSteps1And2() {
        return step1_files() && step2_csvFormat();
    }

    /** Shows Step 3 (column confirmation). Requires {@link #columnConfig} to be set first. */
    public boolean runStep3Columns() {
        return step3_columns();
    }

    /** Shows Steps 4–6 (frame offset, methods, export). Requires loaded data via
     *  {@link #setLoadedData(TrackData, LoadedStack)}. */
    public boolean runSteps4To6() {
        return step4_frameOffset() && step5_methods() && step6_export();
    }

    // ── Step dialogs ──────────────────────────────────────────────────────────

    /** Step 1: Select JSON, TIFF folder, and CSV. */
    private boolean step1_files() {
        Frame parent = IJ.getInstance();
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "ZTracker — Step 1: Input Files", true);
        dlg.setLayout(new BorderLayout(8, 8));

        // Header
        Panel headerPanel = new Panel(new GridLayout(2, 1));
        headerPanel.add(new Label("=== 3D Z-Coordinate Extractor ===", Label.CENTER));
        headerPanel.add(new Label("Extracts Z depth from 16-bit indexed TIFF projections.", Label.CENTER));

        // File rows
        final TextField jsonField = new TextField("", 50);
        final TextField tiffField = new TextField("", 50);
        final TextField csvField  = new TextField("", 50);
        Button jsonBrowse = new Button("Browse...");
        Button tiffBrowse = new Button("Browse...");
        Button csvBrowse  = new Button("Browse...");

        Panel grid = new Panel(new GridBagLayout());
        addFileRow(grid, 0, "Z-mapping JSON:",         jsonField, jsonBrowse);
        addFileRow(grid, 1, "TIFF projection folder:", tiffField, tiffBrowse);
        addFileRow(grid, 2, "Tracking CSV:",           csvField,  csvBrowse);

        // Browse actions
        jsonBrowse.addActionListener(e -> {
            FileDialog fd = new FileDialog(dlg, "Select Z-mapping JSON", FileDialog.LOAD);
            fd.setFile("*.json");
            fd.setVisible(true);
            if (fd.getFile() != null) jsonField.setText(fd.getDirectory() + fd.getFile());
            fd.dispose();
        });
        tiffBrowse.addActionListener(e -> {
            DirectoryChooser dc = new DirectoryChooser("Select TIFF projection folder");
            String dir = dc.getDirectory();
            if (dir != null) tiffField.setText(dir);
        });
        csvBrowse.addActionListener(e -> {
            FileDialog fd = new FileDialog(dlg, "Select tracking CSV", FileDialog.LOAD);
            fd.setFile("*.csv");
            fd.setVisible(true);
            if (fd.getFile() != null) csvField.setText(fd.getDirectory() + fd.getFile());
            fd.dispose();
        });

        // OK / Cancel
        final boolean[] confirmed = {false};
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel btnPanel   = new Panel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);

        okBtn.addActionListener(e     -> { confirmed[0] = true; dlg.setVisible(false); });
        cancelBtn.addActionListener(e -> dlg.setVisible(false));
        dlg.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { dlg.setVisible(false); }
        });

        dlg.add(headerPanel, BorderLayout.NORTH);
        dlg.add(grid,        BorderLayout.CENTER);
        dlg.add(btnPanel,    BorderLayout.SOUTH);
        dlg.pack();
        dlg.setMinimumSize(dlg.getSize());
        if (parent != null) dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true); // blocks until hidden
        dlg.dispose();

        if (!confirmed[0]) return false;

        jsonFile   = new File(jsonField.getText().trim());
        tiffFolder = new File(tiffField.getText().trim());
        csvFile    = new File(csvField.getText().trim());

        if (!jsonFile.exists()) {
            IJ.error("ZTracker", "JSON file not found:\n" + jsonFile.getAbsolutePath());
            return false;
        }
        if (!tiffFolder.isDirectory()) {
            IJ.error("ZTracker", "TIFF folder not found:\n" + tiffFolder.getAbsolutePath());
            return false;
        }
        if (!csvFile.exists()) {
            IJ.error("ZTracker", "CSV file not found:\n" + csvFile.getAbsolutePath());
            return false;
        }
        return true;
    }

    private static void addFileRow(Panel grid, int row, String labelText,
                                   TextField field, Button browse) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row;
        lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(4, 8, 4, 4);
        grid.add(new Label(labelText), lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = row;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(4, 0, 4, 4);
        grid.add(field, fc);

        GridBagConstraints bc = new GridBagConstraints();
        bc.gridx = 2; bc.gridy = row;
        bc.insets = new Insets(4, 0, 4, 8);
        grid.add(browse, bc);
    }

    /** Step 2: Configure CSV parsing (header row, skip rows, default radius). */
    private boolean step2_csvFormat() {
        GenericDialog gd = new GenericDialog("ZTracker — Step 2: CSV Format");
        gd.addMessage("TrackMate default: header=0, skip 3 metadata rows.");
        gd.addNumericField("Header row index:", 0, 0);
        gd.addNumericField("Rows to skip after header:", 3, 0);
        gd.addNumericField("Default radius (pixels):", 3.5, 1);
        gd.showDialog();

        if (gd.wasCanceled()) return false;

        int    headerRow = (int) gd.getNextNumber();
        int    skipRows  = (int) gd.getNextNumber();
        double defRad    = gd.getNextNumber();
        csvConfig = new CsvConfig(headerRow, skipRows, defRad);
        return true;
    }

    /**
     * Step 3: Show auto-detected column names; let user override if needed.
     * {@link #columnConfig} must be set before calling this step.
     */
    private boolean step3_columns() {
        if (columnConfig == null) {
            IJ.error("ZTracker", "Internal error: column config not set before Step 3.");
            return false;
        }

        GenericDialog gd = new GenericDialog("ZTracker — Step 3: CSV Columns");
        gd.addMessage("Auto-detected columns are shown below.\nEdit any that are incorrect.");
        gd.addStringField("X column name:", orEmpty(columnConfig.xCol), 20);
        gd.addStringField("Y column name:", orEmpty(columnConfig.yCol), 20);
        gd.addStringField("Frame column name:", orEmpty(columnConfig.frameCol), 20);
        gd.addStringField("Track ID column name:", orEmpty(columnConfig.trackIdCol), 20);
        gd.addStringField("Radius column (blank = use default):",
                orEmpty(columnConfig.radiusCol), 20);
        gd.showDialog();

        if (gd.wasCanceled()) return false;

        columnConfig.xCol       = blankToNull(gd.getNextString().trim());
        columnConfig.yCol       = blankToNull(gd.getNextString().trim());
        columnConfig.frameCol   = blankToNull(gd.getNextString().trim());
        columnConfig.trackIdCol = blankToNull(gd.getNextString().trim());
        columnConfig.radiusCol  = blankToNull(gd.getNextString().trim());

        if (!columnConfig.isComplete()) {
            IJ.error("ZTracker", "X, Y, Frame, and Track ID columns are all required.");
            return false;
        }
        return true;
    }

    /**
     * Step 4: Frame offset — shows alignment preview, lets user confirm or re-enter.
     * Requires {@link #setLoadedData(TrackData, LoadedStack)} to have been called.
     */
    private boolean step4_frameOffset() {
        if (loadedTrack == null || loadedStack == null) {
            IJ.error("ZTracker", "Internal error: data not loaded before Step 4.");
            return false;
        }

        int suggested = FrameAligner.suggestOffset(loadedTrack, loadedStack);

        while (true) {
            GenericDialog gd = new GenericDialog("ZTracker — Step 4: Frame Alignment");
            gd.addMessage(
                    "The offset is ADDED to each CSV frame to find the matching TIFF.\n"
                    + "Example: offset=+1 means CSV frame 0 → TIFF file 1.\n"
                    + "Suggested offset: " + (suggested >= 0 ? "+" : "") + suggested);
            gd.addNumericField("CSV-to-TIFF frame offset:", suggested, 0);
            gd.showDialog();

            if (gd.wasCanceled()) return false;

            int offset    = (int) gd.getNextNumber();
            String preview = FrameAligner.buildPreview(loadedTrack, loadedStack, offset);

            // Confirmation dialog with alignment preview
            GenericDialog confirm = new GenericDialog("ZTracker — Step 4: Confirm Alignment");
            confirm.addMessage(preview);
            confirm.addCheckbox("Alignment looks correct — continue", true);
            confirm.showDialog();

            if (confirm.wasCanceled()) return false;
            if (confirm.getNextBoolean()) {
                frameOffset = offset;
                return true;
            }
            // User unchecked — loop to re-enter offset
        }
    }

    /** Step 5: Sampling and aggregation method selection. */
    private boolean step5_methods() {
        String[] samplingLabels = {
            ZSampler.Method.RADIUS.label,
            ZSampler.Method.FOUR_NEIGHBOR.label,
            ZSampler.Method.SINGLE_PIXEL.label
        };
        String[] aggregationLabels = {
            ZAggregator.Method.MEDIAN.label,
            ZAggregator.Method.MEAN.label,
            ZAggregator.Method.MODE.label
        };

        GenericDialog gd = new GenericDialog("ZTracker — Step 5: Extraction Methods");
        gd.addMessage("How pixels are collected around each detection:");
        gd.addChoice("Sampling method:", samplingLabels, samplingLabels[0]);
        gd.addMessage("\nHow sampled Z values are combined into one:");
        gd.addChoice("Aggregation method:", aggregationLabels, aggregationLabels[0]);
        gd.showDialog();

        if (gd.wasCanceled()) return false;

        samplingMethod    = labelToEnum(ZSampler.Method.values(),    gd.getNextChoice(), ZSampler.Method.RADIUS);
        aggregationMethod = labelToEnum(ZAggregator.Method.values(), gd.getNextChoice(), ZAggregator.Method.MEDIAN);
        return true;
    }

    /** Step 6: Output directory, quality filters, and export format selection. */
    private boolean step6_export() {
        GenericDialog gd = new GenericDialog("ZTracker — Step 6: Output & Export");
        gd.addDirectoryField("Output directory:", "", 40);
        gd.addMessage("── Track quality filters ────────────────────");
        gd.addNumericField("Minimum track length (frames):", 3, 0);
        gd.addNumericField("Maximum Z std dev (0 = no filter):", 0.0, 2);
        gd.addMessage("── Export formats ──────────────────────────");
        gd.addCheckbox("Export .npy files  (Python/downstream pipeline)", true);
        gd.addCheckbox("Export Results Table CSV  (Fiji: Analyze > Import > Results)", true);
        gd.addCheckbox("Export ROI point set .zip  (Fiji ROI Manager)", false);
        gd.showDialog();

        if (gd.wasCanceled()) return false;

        outputDir        = new File(gd.getNextString().trim());
        int    minLen    = (int) gd.getNextNumber();
        double maxStdRaw = gd.getNextNumber();
        boolean npy      = gd.getNextBoolean();
        boolean csv      = gd.getNextBoolean();
        boolean roi      = gd.getNextBoolean();

        Double maxZStd = (maxStdRaw <= 0.0) ? null : maxStdRaw;
        exportConfig   = new ExportConfig(minLen, maxZStd, npy, csv, roi);

        outputDir.mkdirs();
        return true;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String orEmpty(String s)    { return s != null ? s : ""; }
    private static String blankToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }

    /**
     * Finds the enum constant whose {@code label} field matches (case-insensitive).
     * Falls back to {@code fallback} if no match is found.
     */
    private static <E extends Enum<E>> E labelToEnum(E[] values, String label, E fallback) {
        for (E v : values) {
            try {
                String vLabel = (String) v.getClass().getField("label").get(v);
                if (vLabel.equalsIgnoreCase(label)) return v;
            } catch (ReflectiveOperationException ignored) {}
        }
        return fallback;
    }
}
