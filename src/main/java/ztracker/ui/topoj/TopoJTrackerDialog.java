package ztracker.ui.topoj;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.io.DirectoryChooser;
import ztracker.core.FrameAligner;
import ztracker.core.ZAggregator;
import ztracker.core.extractor.ZSampler;
import ztracker.export.TrackExportManager.ExportConfig;
import ztracker.io.extractor.TiffStackLoader.LoadedStack;
import ztracker.io.topoj.TopoJStackLoader.LoadedFloatStack;
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
 * Tool 3 (TopoJ / direct-Z) counterpart to {@link ZTrackerDialog}.
 *
 * <p>This is a deliberate duplicate of {@link ZTrackerDialog} so Tool 2's dialog stays
 * byte-for-byte untouched (matching the plugin's isolate-per-tool philosophy — the same
 * reason Tool 1 duplicated its folder-picker layout). The only substantive difference is
 * that <b>Step 1 collects just the TIFF folder and CSV</b> — there is no JSON Z-mapping
 * picker, because a TopoJ pixel value <b>is</b> the Z. Steps 2–6 (CSV format, column
 * confirmation, frame offset, methods, export) are identical.
 *
 * <p>Step 4 reuses Tool 2's {@link FrameAligner} via {@link LoadedFloatStack#frameView()},
 * which exposes only the float stack's frame map (FrameAligner never reads pixel data).
 */
public class TopoJTrackerDialog {

    // ── Collected parameters (populated after run groups complete) ────────────

    public File tiffFolder;
    public File csvFile;

    public CsvConfig    csvConfig;
    public ColumnConfig columnConfig;   // set by plugin after auto-detection, then confirmed here

    public int                       frameOffset;
    public ZSampler.Method           samplingMethod;      // ignored when sampleAllMethods is true
    public ZAggregator.Method        aggregationMethod;   // ignored when aggregateAllMethods is true
    public boolean                   sampleAllMethods;
    public boolean                   aggregateAllMethods;
    public ZSampler.PixelConvention  pixelConvention;     // no "All" option — always exactly one

    public ExportConfig exportConfig;
    public File         outputDir;

    // ── State injected by the plugin between steps ────────────────────────────

    private TrackData        loadedTrack;
    private LoadedFloatStack loadedStack;

    public void setLoadedData(TrackData track, LoadedFloatStack stack) {
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
     *  {@link #setLoadedData(TrackData, LoadedFloatStack)}. */
    public boolean runSteps4To6() {
        return step4_frameOffset() && step5_methods() && step6_export();
    }

    // ── Step dialogs ──────────────────────────────────────────────────────────

    /** Step 1: Select the TIFF folder and CSV (no JSON mapping — pixel value is Z). */
    private boolean step1_files() {
        Frame parent = IJ.getInstance();
        // Modeless (not modal) so other ImageJ windows (e.g. the Log) stay interactive
        // while this box is open; the plugin thread is blocked below with a latch.
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "TopoJ Z-Extractor — Step 1: Input Files", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        // Header
        Panel headerPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        headerPanel.add(new Label("Provide paths to the two required input files."));

        // Path holders and display labels
        final String[] tiffPath = {""};
        final String[] csvPath  = {""};

        final Label tiffPathLbl = new Label("No folder selected.");
        final Label csvPathLbl  = new Label("No file selected.");
        tiffPathLbl.setForeground(Color.GRAY);
        csvPathLbl.setForeground(Color.GRAY);

        // Folder buttons
        Button tiffBtn = new Button("...");
        Button csvBtn  = new Button("...");

        Panel grid = new Panel(new GridBagLayout());
        int row = 0;
        row = addInputGroup(grid, row, true,
                "TopoJ Z-map TIFF folder",
                "Folder with one 32-bit float TIFF per timepoint whose pixel values are Z depth in µm (TopoJ output). Each filename must end with its frame number — any prefix, any zero-padding width (e.g. frame7.tif, topoj_0007.tif); names not ending in a number are rejected",
                tiffBtn, tiffPathLbl);
        row = addInputGroup(grid, row, false,
                "Tracking CSV",
                "CSV with 2D detections — must contain X, Y, frame, and track ID columns (TrackMate and other formats supported)",
                csvBtn, csvPathLbl);

        // Browse actions
        tiffBtn.addActionListener(e -> {
            DirectoryChooser dc = new DirectoryChooser("Select TopoJ Z-map TIFF folder");
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

        tiffFolder = new File(tiffPath[0]);
        csvFile    = new File(csvPath[0]);

        if (!tiffFolder.isDirectory()) {
            IJ.error("TopoJ Z-Extractor", "TIFF folder not found:\n" + tiffFolder.getAbsolutePath());
            return false;
        }
        if (!csvFile.exists()) {
            IJ.error("TopoJ Z-Extractor", "CSV file not found:\n" + csvFile.getAbsolutePath());
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
        GenericDialog gd = new NonBlockingGenericDialog("TopoJ Z-Extractor — Step 2: CSV Format");
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
            IJ.error("TopoJ Z-Extractor", "Internal error: column config not set before Step 3.");
            return false;
        }

        GenericDialog gd = new NonBlockingGenericDialog("TopoJ Z-Extractor — Step 3: CSV Columns");
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
            IJ.error("TopoJ Z-Extractor", "X, Y, Frame, and Track ID columns are all required.");
            return false;
        }
        return true;
    }

    /**
     * Step 4: Frame offset — a single custom dialog that lets the user enter the
     * CSV-to-TIFF offset and see a live per-track verdict update as they type, then
     * confirm. Requires {@link #setLoadedData(TrackData, LoadedFloatStack)} first.
     *
     * <p>Reuses Tool 2's {@link FrameAligner} against a {@link LoadedFloatStack#frameView()}
     * (a pixel-less frame-index view of the float stack).
     */
    private boolean step4_frameOffset() {
        if (loadedTrack == null || loadedStack == null) {
            IJ.error("TopoJ Z-Extractor", "Internal error: data not loaded before Step 4.");
            return false;
        }

        // Frame-index view of the float stack, reused across all FrameAligner calls below.
        final LoadedStack frameStack = loadedStack.frameView();
        final int suggested = FrameAligner.suggestOffset(loadedTrack, frameStack);

        Frame parent = IJ.getInstance();
        // Modeless (not modal) so the ImageJ Log window stays interactive/resizable
        // while this box is open — the per-track table is logged there for review.
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "TopoJ Z-Extractor — Step 4: Frame Alignment", false);
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

        // Live refresh: recompute verdict + checkbox for the entered offset, and log the
        // full per-track table once per DISTINCT offset actually evaluated (deduped).
        final Integer[] lastLogged = {null};
        final Runnable refresh = () -> {
            Integer parsed = parseOffset(offsetField.getText());
            int off = parsed != null ? parsed : suggested;

            java.util.List<FrameAligner.TrackAlignment> perTrack =
                    FrameAligner.perTrackAlignment(loadedTrack, frameStack, off);
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
                FrameAligner.validate(loadedTrack, frameStack, parsed);
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
                IJ.error("TopoJ Z-Extractor", "Enter a whole-number offset (e.g. 0, 1, -1).");
                return; // keep dialog open
            }
            if (!confirmBox.getState()) {
                IJ.error("TopoJ Z-Extractor",
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
        // Authoritative final record for the confirmed offset.
        IJ.log("[FrameAligner] ═══ CONFIRMED — extracting with frame offset "
                + signed(frameOffset) + " ═══");
        FrameAligner.validate(loadedTrack, frameStack, frameOffset);
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

    /** Step 5: Sampling and aggregation method selection (identical to Tool 2's Step 5). */
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
        String[] conventionLabels = {
            ZSampler.PixelConvention.PIXEL_CORNER.label,
            ZSampler.PixelConvention.PIXEL_CENTER.label
        };

        Frame parent = IJ.getInstance();
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "TopoJ Z-Extractor — Step 5: Extraction Methods", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        Panel headerPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        headerPanel.add(new Label("Choose how pixels are sampled and combined into a Z value."));

        final Choice samplingBox    = new Choice();
        final Choice aggregationBox = new Choice();
        final Choice conventionBox  = new Choice();
        for (String s : samplingLabels)    samplingBox.add(s);
        for (String s : aggregationLabels) aggregationBox.add(s);
        for (String s : conventionLabels)  conventionBox.add(s);

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

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(8, 8, 2, 8);
        Label conventionTitle = new Label("Pixel coordinate convention — where (X, Y) sits relative to the pixel grid");
        conventionTitle.setFont(base.deriveFont(Font.BOLD));
        grid.add(conventionTitle, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 2, 8);
        grid.add(conventionBox, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(0, 8, 8, 8);
        Label conventionNote = new Label(
                "Switching this can change which detections are in-bounds near frame edges (e.g. x=-0.4 is in-bounds under Center but not Corner).");
        conventionNote.setForeground(Color.GRAY);
        grid.add(conventionNote, c);

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

        pixelConvention = labelToEnum(ZSampler.PixelConvention.values(),
                conventionBox.getSelectedItem(), ZSampler.PixelConvention.PIXEL_CORNER);
        return true;
    }

    /** Step 6: Output directory and export format selection (identical to Tool 2's Step 6). */
    private boolean step6_export() {
        Frame parent = IJ.getInstance();
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "TopoJ Z-Extractor — Step 6: Output & Export", false);
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
            IJ.error("TopoJ Z-Extractor", "No output directory selected.");
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
