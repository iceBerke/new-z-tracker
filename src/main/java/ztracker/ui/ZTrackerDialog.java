package ztracker.ui;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
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
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.concurrent.CountDownLatch;

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
    public ZSampler.Method    samplingMethod;      // ignored when sampleAllMethods is true
    public ZAggregator.Method aggregationMethod;   // ignored when aggregateAllMethods is true
    public boolean            sampleAllMethods;
    public boolean            aggregateAllMethods;

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
        // Modeless (not modal) so other ImageJ windows (e.g. the Log) stay interactive
        // while this box is open; the plugin thread is blocked below with a latch.
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "ZTracker — Step 1: Input Files", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        // Header
        Panel headerPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        headerPanel.add(new Label("Provide paths to the three required input files."));

        // Path holders and display labels
        final String[] jsonPath = {""};
        final String[] tiffPath = {""};
        final String[] csvPath  = {""};

        final Label jsonPathLbl = new Label("No file selected.");
        final Label tiffPathLbl = new Label("No folder selected.");
        final Label csvPathLbl  = new Label("No file selected.");
        jsonPathLbl.setForeground(Color.GRAY);
        tiffPathLbl.setForeground(Color.GRAY);
        csvPathLbl.setForeground(Color.GRAY);

        // Folder buttons
        Button jsonBtn = new Button("...");
        Button tiffBtn = new Button("...");
        Button csvBtn  = new Button("...");

        Panel grid = new Panel(new GridBagLayout());
        int row = 0;
        row = addInputGroup(grid, row, true,
                "Z-mapping JSON",
                "JSON mapping pixel indices to Z depth in µm  (e.g. {\"0\": 0.0, \"1\": 0.25, ...})",
                jsonBtn, jsonPathLbl);
        row = addInputGroup(grid, row, false,
                "Z-origin TIFF projection folder",
                "Folder with one 16-bit or 32-bit indexed TIFF per timepoint, sorted by the trailing frame number in filename (e.g. 0001.tif)",
                tiffBtn, tiffPathLbl);
        row = addInputGroup(grid, row, false,
                "Tracking CSV",
                "CSV with 2D detections — must contain X, Y, frame, and track ID columns (TrackMate and other formats supported)",
                csvBtn, csvPathLbl);

        // Browse actions
        jsonBtn.addActionListener(e -> {
            FileDialog fd = new FileDialog(dlg, "Select Z-mapping JSON", FileDialog.LOAD);
            fd.setFile("*.json");
            fd.setVisible(true);
            if (fd.getFile() != null) {
                jsonPath[0] = fd.getDirectory() + fd.getFile();
                jsonPathLbl.setText(jsonPath[0]);
                jsonPathLbl.setForeground(Color.BLACK);
            }
            fd.dispose();
        });
        tiffBtn.addActionListener(e -> {
            DirectoryChooser dc = new DirectoryChooser("Select TIFF projection folder");
            String dir = dc.getDirectory();
            if (dir != null) {
                tiffPath[0] = dir;
                tiffPathLbl.setText(dir);
                tiffPathLbl.setForeground(Color.BLACK);
            }
        });
        csvBtn.addActionListener(e -> {
            FileDialog fd = new FileDialog(dlg, "Select tracking CSV", FileDialog.LOAD);
            fd.setFile("*.csv");
            fd.setVisible(true);
            if (fd.getFile() != null) {
                csvPath[0] = fd.getDirectory() + fd.getFile();
                csvPathLbl.setText(csvPath[0]);
                csvPathLbl.setForeground(Color.BLACK);
            }
            fd.dispose();
        });

        // OK / Cancel
        final boolean[] confirmed = {false};
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel btnPanel   = new Panel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);

        okBtn.addActionListener(e     -> { confirmed[0] = true; dlg.setVisible(false); closeLatch.countDown(); });
        cancelBtn.addActionListener(e -> { dlg.setVisible(false); closeLatch.countDown(); });
        dlg.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { dlg.setVisible(false); closeLatch.countDown(); }
        });

        dlg.add(headerPanel, BorderLayout.NORTH);
        dlg.add(grid,        BorderLayout.CENTER);
        dlg.add(btnPanel,    BorderLayout.SOUTH);
        dlg.pack();
        dlg.setMinimumSize(dlg.getSize());
        if (parent != null) dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true); // modeless — returns immediately; block the plugin thread below
        try {
            closeLatch.await(); // wait for OK / Cancel / window close
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dlg.dispose();
            return false;
        }
        dlg.dispose();

        if (!confirmed[0]) return false;

        jsonFile   = new File(jsonPath[0]);
        tiffFolder = new File(tiffPath[0]);
        csvFile    = new File(csvPath[0]);

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

    private static int addInputGroup(Panel grid, int startRow, boolean isFirst,
                                     String title, String description,
                                     Button btn, Label pathLabel) {
        GridBagConstraints c;

        // Title
        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(isFirst ? 4 : 16, 8, 2, 8);
        Label titleLabel = new Label(title);
        Font base = titleLabel.getFont();
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        titleLabel.setFont(base.deriveFont(Font.BOLD));
        grid.add(titleLabel, c);

        // Description
        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(0, 8, 6, 8);
        Label descLabel = new Label(description);
        descLabel.setForeground(Color.DARK_GRAY);
        grid.add(descLabel, c);

        // Folder button
        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 2, 8);
        grid.add(btn, c);

        // Path label
        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(2, 8, 4, 8);
        grid.add(pathLabel, c);

        return startRow;
    }

    /** Step 2: Configure CSV parsing (header row, skip rows, default radius). */
    private boolean step2_csvFormat() {
        GenericDialog gd = new NonBlockingGenericDialog("ZTracker — Step 2: CSV Format");
        gd.addMessage("Specify the CSV header format and pixel size default.");
        gd.addMessage("e.g., TrackMate default: header=0, skip 3 metadata rows.");
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

        GenericDialog gd = new NonBlockingGenericDialog("ZTracker — Step 3: CSV Columns");
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
     * Step 4: Frame offset — a single custom dialog that lets the user enter the
     * CSV-to-TIFF offset and see a live per-track verdict update as they type, then
     * confirm. Requires {@link #setLoadedData(TrackData, LoadedStack)} first.
     *
     * <p>The live preview uses non-logging {@code perTrackAlignment}/{@code buildBoxSummary};
     * the authoritative {@link FrameAligner#validate} (which logs the full per-track
     * table) runs once when the user confirms.
     */
    private boolean step4_frameOffset() {
        if (loadedTrack == null || loadedStack == null) {
            IJ.error("ZTracker", "Internal error: data not loaded before Step 4.");
            return false;
        }

        final int suggested = FrameAligner.suggestOffset(loadedTrack, loadedStack);

        Frame parent = IJ.getInstance();
        // Modeless (not modal) so the ImageJ Log window stays interactive/resizable
        // while this box is open — the per-track table is logged there for review.
        // The plugin thread is blocked below with a latch instead of by modality.
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "ZTracker — Step 4: Frame Alignment", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        // Header
        Panel header = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        header.add(new Label(
                "The offset is ADDED to each CSV frame to find the matching TIFF file."));

        // Offset input row
        Panel inputRow = new Panel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new Label("CSV-to-TIFF offset:"));
        final TextField offsetField = new TextField(String.valueOf(suggested), 6);
        inputRow.add(offsetField);
        inputRow.add(new Label("(suggested " + signed(suggested) + ")"));

        // Live verdict area (monospace so the summary lines read cleanly)
        final TextArea verdict = new TextArea("", 4, 58,
                TextArea.SCROLLBARS_VERTICAL_ONLY);
        verdict.setEditable(false);
        verdict.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Confirmation checkbox (label + default state recomputed live)
        final Checkbox confirmBox = new Checkbox("", false);
        Panel confirmPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        confirmPanel.add(confirmBox);

        // Live refresh: recompute verdict + checkbox for the entered offset, and log
        // the full per-track table to the Log so the user can verify the choice
        // BEFORE confirming. To avoid spamming the log on every keystroke, the table
        // is logged once per DISTINCT offset actually evaluated (deduped).
        // A blank/invalid field falls back to the suggested offset for the preview.
        final Integer[] lastLogged = {null};
        final Runnable refresh = () -> {
            Integer parsed = parseOffset(offsetField.getText());
            int off = parsed != null ? parsed : suggested;

            java.util.List<FrameAligner.TrackAlignment> perTrack =
                    FrameAligner.perTrackAlignment(loadedTrack, loadedStack, off);
            boolean clean = true;
            for (FrameAligner.TrackAlignment ta : perTrack) {
                if (!ta.fullyMapped()) { clean = false; break; }
            }

            String header2 = parsed == null
                    ? "(enter a whole-number offset)\n\n"
                    : "";
            verdict.setText(header2 + FrameAligner.buildBoxSummary(perTrack));
            confirmBox.setLabel(clean
                    ? "Alignment looks correct — continue"
                    : "I have reviewed the warnings above — continue anyway");
            confirmBox.setState(clean);

            if (parsed != null && !parsed.equals(lastLogged[0])) {
                FrameAligner.validate(loadedTrack, loadedStack, parsed);
                lastLogged[0] = parsed;
            }
        };
        offsetField.addTextListener(e -> refresh.run());
        refresh.run(); // initial paint — logs the suggested offset's table up front

        // OK / Cancel
        final boolean[] confirmed = {false};
        final int[]     chosen    = {suggested};
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel  btnPanel  = new Panel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);

        okBtn.addActionListener(e -> {
            Integer parsed = parseOffset(offsetField.getText());
            if (parsed == null) {
                IJ.error("ZTracker", "Enter a whole-number offset (e.g. 0, 1, -1).");
                return; // keep dialog open
            }
            if (!confirmBox.getState()) {
                IJ.error("ZTracker",
                        "Tick the confirmation box to continue, or press Cancel.");
                return; // keep dialog open
            }
            chosen[0]    = parsed;
            confirmed[0] = true;
            dlg.setVisible(false);
            closeLatch.countDown();
        });
        cancelBtn.addActionListener(e -> { dlg.setVisible(false); closeLatch.countDown(); });
        dlg.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { dlg.setVisible(false); closeLatch.countDown(); }
        });

        Panel center = new Panel(new BorderLayout(6, 6));
        center.add(inputRow,     BorderLayout.NORTH);
        center.add(verdict,      BorderLayout.CENTER);
        center.add(confirmPanel, BorderLayout.SOUTH);

        dlg.add(header,    BorderLayout.NORTH);
        dlg.add(center,    BorderLayout.CENTER);
        dlg.add(btnPanel,  BorderLayout.SOUTH);
        dlg.pack();
        dlg.setMinimumSize(dlg.getSize());
        if (parent != null) dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true); // modeless — returns immediately; block the plugin thread below
        try {
            closeLatch.await(); // wait for OK / Cancel / window close
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dlg.dispose();
            return false;
        }
        dlg.dispose();

        if (!confirmed[0]) return false;

        frameOffset = chosen[0];
        // Authoritative final record for the confirmed offset — logs the full per-track
        // table again under a clear header so the choice is unambiguous in the Log.
        IJ.log("[FrameAligner] ═══ CONFIRMED — extracting with frame offset "
                + signed(frameOffset) + " ═══");
        FrameAligner.validate(loadedTrack, loadedStack, frameOffset);
        return true;
    }

    private static String signed(int n) { return (n >= 0 ? "+" : "") + n; }

    /** Parses a whole-number offset; returns null if blank or not an integer. */
    private static Integer parseOffset(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final String ALL_LABEL = "All (run every method)";

    /** Step 5: Sampling and aggregation method selection. Either axis may be set to
     *  "All", in which case every method on that axis is run (and exported to its
     *  own subfolder) instead of a single chosen one.
     *
     *  <p>Single Pixel samples exactly one pixel per detection, so every aggregation
     *  method produces an identical Z value — the aggregation choice is disabled
     *  (and pinned to Median, which is never actually used downstream) whenever
     *  Single Pixel is chosen on its own. It stays enabled when Sampling is "All",
     *  since Radius and 4-Neighbor still need an aggregation method. */
    private boolean step5_methods() {
        String[] samplingLabels = {
            ZSampler.Method.RADIUS.label,
            ZSampler.Method.FOUR_NEIGHBOR.label,
            ZSampler.Method.SINGLE_PIXEL.label,
            ALL_LABEL
        };
        String[] aggregationLabels = {
            ZAggregator.Method.MEDIAN.label,
            ZAggregator.Method.MEAN.label,
            ALL_LABEL
        };

        Frame parent = IJ.getInstance();
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "ZTracker — Step 5: Extraction Methods", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        Panel headerPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        headerPanel.add(new Label("Choose how pixels are sampled and combined into a Z value."));

        final Choice samplingBox    = new Choice();
        final Choice aggregationBox = new Choice();
        for (String s : samplingLabels)    samplingBox.add(s);
        for (String s : aggregationLabels) aggregationBox.add(s);

        final Label aggregationNote = new Label(" ");
        aggregationNote.setForeground(Color.GRAY);

        Panel grid = new Panel(new GridBagLayout());
        int row = 0;
        GridBagConstraints c;

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(4, 8, 2, 8);
        Label samplingTitle = new Label("Sampling method — how pixels are collected around each detection");
        Font base = samplingTitle.getFont();
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        samplingTitle.setFont(base.deriveFont(Font.BOLD));
        grid.add(samplingTitle, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 8, 8);
        grid.add(samplingBox, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(8, 8, 2, 8);
        Label aggregationTitle = new Label("Aggregation method — how sampled Z values are combined into one");
        aggregationTitle.setFont(base.deriveFont(Font.BOLD));
        grid.add(aggregationTitle, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 2, 8);
        grid.add(aggregationBox, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(0, 8, 8, 8);
        grid.add(aggregationNote, c);

        Runnable updateAggregationState = () -> {
            boolean singlePixelOnly = ZSampler.Method.SINGLE_PIXEL.label.equals(samplingBox.getSelectedItem());
            aggregationBox.setEnabled(!singlePixelOnly);
            aggregationNote.setText(singlePixelOnly
                    ? "Single Pixel samples one pixel per detection — aggregation doesn't apply."
                    : " ");
        };
        samplingBox.addItemListener(e -> updateAggregationState.run());
        updateAggregationState.run();

        final boolean[] confirmed = {false};
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel btnPanel   = new Panel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);

        okBtn.addActionListener(e     -> { confirmed[0] = true; dlg.setVisible(false); closeLatch.countDown(); });
        cancelBtn.addActionListener(e -> { dlg.setVisible(false); closeLatch.countDown(); });
        dlg.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { dlg.setVisible(false); closeLatch.countDown(); }
        });

        dlg.add(headerPanel, BorderLayout.NORTH);
        dlg.add(grid,        BorderLayout.CENTER);
        dlg.add(btnPanel,    BorderLayout.SOUTH);
        dlg.pack();
        dlg.setMinimumSize(dlg.getSize());
        if (parent != null) dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
        try {
            closeLatch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dlg.dispose();
            return false;
        }
        dlg.dispose();

        if (!confirmed[0]) return false;

        String samplingChoice    = samplingBox.getSelectedItem();
        String aggregationChoice = aggregationBox.getSelectedItem();
        boolean singlePixelChosen = ZSampler.Method.SINGLE_PIXEL.label.equals(samplingChoice);

        sampleAllMethods    = ALL_LABEL.equals(samplingChoice);
        aggregateAllMethods = !singlePixelChosen && ALL_LABEL.equals(aggregationChoice);

        samplingMethod    = sampleAllMethods
                ? ZSampler.Method.RADIUS
                : labelToEnum(ZSampler.Method.values(), samplingChoice, ZSampler.Method.RADIUS);
        aggregationMethod = (singlePixelChosen || aggregateAllMethods)
                ? ZAggregator.Method.MEDIAN
                : labelToEnum(ZAggregator.Method.values(), aggregationChoice, ZAggregator.Method.MEDIAN);
        return true;
    }

    /** Step 6: Output directory and export format selection. */
    private boolean step6_export() {
        Frame parent = IJ.getInstance();
        // Modeless (not modal), same convention as Steps 1 and 4.
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "ZTracker — Step 6: Output & Export", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        // Header
        Panel headerPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        headerPanel.add(new Label("Choose the output directory and export formats."));

        // Output directory
        final String[] outPath = {""};
        final Label outPathLbl = new Label("No folder selected.");
        outPathLbl.setForeground(Color.GRAY);
        Button outBtn = new Button("...");

        Panel grid = new Panel(new GridBagLayout());
        int row = 0;
        row = addInputGroup(grid, row, true,
                "Output directory",
                "Folder where exported tracks (.npy / CSV / ROI zip) will be written",
                outBtn, outPathLbl);

        outBtn.addActionListener(e -> {
            DirectoryChooser dc = new DirectoryChooser("Select output directory");
            String dir = dc.getDirectory();
            if (dir != null) {
                outPath[0] = dir;
                outPathLbl.setText(dir);
                outPathLbl.setForeground(Color.BLACK);
            }
        });

        // Export formats
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(16, 8, 4, 8);
        Label formatsTitle = new Label("Export formats");
        Font base = formatsTitle.getFont();
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        formatsTitle.setFont(base.deriveFont(Font.BOLD));
        grid.add(formatsTitle, c);

        final Checkbox npyBox = new Checkbox("Export .npy files  (Python/downstream pipeline)", true);
        final Checkbox csvBox = new Checkbox("Export Results Table CSV  (Fiji: Analyze > Import > Results)", true);
        final Checkbox roiBox = new Checkbox("Export XY ROI point set .zip  (X px, Y px)", false);
        final Checkbox xzRoiBox = new Checkbox("Export XZ ROI point set .zip  (X px, Z µm — valid-Z points only)", false);
        final Checkbox yzRoiBox = new Checkbox("Export YZ ROI point set .zip  (Y px, Z µm — valid-Z points only)", false);
        Checkbox[] formatBoxes = { npyBox, csvBox, roiBox, xzRoiBox, yzRoiBox };
        for (int i = 0; i < formatBoxes.length; i++) {
            c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.insets = new Insets(2, 8, i == formatBoxes.length - 1 ? 8 : 2, 8);
            grid.add(formatBoxes[i], c);
        }

        // OK / Cancel
        final boolean[] confirmed = {false};
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel btnPanel   = new Panel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);

        okBtn.addActionListener(e     -> { confirmed[0] = true; dlg.setVisible(false); closeLatch.countDown(); });
        cancelBtn.addActionListener(e -> { dlg.setVisible(false); closeLatch.countDown(); });
        dlg.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { dlg.setVisible(false); closeLatch.countDown(); }
        });

        dlg.add(headerPanel, BorderLayout.NORTH);
        dlg.add(grid,        BorderLayout.CENTER);
        dlg.add(btnPanel,    BorderLayout.SOUTH);
        dlg.pack();
        dlg.setMinimumSize(dlg.getSize());
        if (parent != null) dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
        try {
            closeLatch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dlg.dispose();
            return false;
        }
        dlg.dispose();

        if (!confirmed[0]) return false;

        if (outPath[0].isEmpty()) {
            IJ.error("ZTracker", "No output directory selected.");
            return false;
        }
        outputDir = new File(outPath[0]);

        boolean npy   = npyBox.getState();
        boolean csv   = csvBox.getState();
        boolean roi   = roiBox.getState();
        boolean xzRoi = xzRoiBox.getState();
        boolean yzRoi = yzRoiBox.getState();
        exportConfig = new ExportConfig(npy, csv, roi, xzRoi, yzRoi);

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
