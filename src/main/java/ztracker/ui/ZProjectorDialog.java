package ztracker.ui;

import ij.IJ;
import ij.io.DirectoryChooser;
import ztracker.project.ZProjector;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * Parameter dialog for the Z-Projection + Origin-Map tool (the projection sibling of
 * {@link ZTrackerDialog}). It produces the indexed-TIFF + JSON-mapping outputs the
 * extractor later consumes.
 *
 * <p>Same UI idiom as {@code ZTrackerDialog}'s custom steps: a modeless AWT
 * {@link Dialog} that blocks the plugin thread with a {@link CountDownLatch} (plugins
 * run off the EDT, so blocking here doesn't freeze the UI while the Log stays
 * interactive). Folder selection uses {@link DirectoryChooser} laid out with the same
 * {@code addInputGroup} grid as the extractor's Step 1 / Step 6 — <b>intentionally
 * duplicated here</b> (not shared) so {@code ZTrackerDialog} stays byte-for-byte
 * untouched.
 */
public class ZProjectorDialog {

    /** Collected parameters, populated after {@link #showDialog()} returns true. */
    public static final class Config {
        public final File inputDir;
        public final boolean batch;
        public final ZProjector.Mode mode;
        public final File outputDir;
        public final boolean writeRaw;

        Config(File inputDir, boolean batch, ZProjector.Mode mode, File outputDir, boolean writeRaw) {
            this.inputDir  = inputDir;
            this.batch     = batch;
            this.mode      = mode;
            this.outputDir = outputDir;
            this.writeRaw  = writeRaw;
        }
    }

    private Config config;

    /** @return the collected configuration (null until {@link #showDialog()} returns true). */
    public Config getConfig() { return config; }

    // Scope / projection choice labels.
    private static final String SCOPE_SINGLE = "Single dataset  (folder holds the Z-value sub-folders directly)";
    private static final String SCOPE_BATCH  = "Batch  (folder holds many datasets, each with its own Z-value sub-folders)";
    private static final String PROJ_MAX     = "Max-Z  (brightest pixel per position)";
    private static final String PROJ_MIN     = "Min-Z  (darkest pixel per position)";

    /**
     * Shows the modeless dialog and blocks the plugin thread until OK / Cancel / close.
     *
     * @return true if the user confirmed with valid input/output folders; false otherwise
     */
    public boolean showDialog() {
        Frame parent = IJ.getInstance();
        final Dialog dlg = new Dialog(
                parent != null ? parent : new Frame(),
                "ZTracker — Z-Projection + Origin Map", false);
        dlg.setLayout(new BorderLayout(8, 8));
        final CountDownLatch closeLatch = new CountDownLatch(1);

        // Header
        Panel headerPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        headerPanel.add(new Label("Build indexed Z-origin projections that the extractor can later read."));

        // Path holders + display labels
        final String[] inPath  = {""};
        final String[] outPath = {""};
        final Label inPathLbl  = new Label("No folder selected.");
        final Label outPathLbl = new Label("No folder selected.");
        inPathLbl.setForeground(Color.GRAY);
        outPathLbl.setForeground(Color.GRAY);

        Button inBtn  = new Button("...");
        Button outBtn = new Button("...");

        Panel grid = new Panel(new GridBagLayout());
        int row = 0;
        row = addInputGroup(grid, row, true,
                "Input folder",
                "Single: the folder containing the numeric Z-value sub-folders (e.g. -300, -299, ...). "
                        + "Batch: a folder containing many such datasets.",
                inBtn, inPathLbl);
        row = addInputGroup(grid, row, false,
                "Output folder",
                "Where the max_z / min_z output tree is written (raw projection, z_origin TIFFs, JSON mappings).",
                outBtn, outPathLbl);

        inBtn.addActionListener(e -> {
            DirectoryChooser dc = new DirectoryChooser("Select input folder");
            String dir = dc.getDirectory();
            if (dir != null) {
                inPath[0] = dir;
                inPathLbl.setText(dir);
                inPathLbl.setForeground(Color.BLACK);
            }
        });
        outBtn.addActionListener(e -> {
            DirectoryChooser dc = new DirectoryChooser("Select output folder");
            String dir = dc.getDirectory();
            if (dir != null) {
                outPath[0] = dir;
                outPathLbl.setText(dir);
                outPathLbl.setForeground(Color.BLACK);
            }
        });

        // Scope (single vs batch)
        final Choice scopeChoice = new Choice();
        scopeChoice.add(SCOPE_SINGLE);
        scopeChoice.add(SCOPE_BATCH);
        row = addChoiceGroup(grid, row, "Scope", scopeChoice);

        // Projection mode (max vs min)
        final Choice modeChoice = new Choice();
        modeChoice.add(PROJ_MAX);
        modeChoice.add(PROJ_MIN);
        row = addChoiceGroup(grid, row, "Projection", modeChoice);

        // Raw projection toggle (on by default — matches the script, which always writes it)
        final Checkbox rawBox = new Checkbox(
                "Also write the 8-bit raw projection  (visualization only; extractor ignores it)", true);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(12, 8, 8, 8);
        grid.add(rawBox, c);

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
        dlg.setVisible(true); // modeless — returns immediately; block below on the latch
        try {
            closeLatch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dlg.dispose();
            return false;
        }
        dlg.dispose();

        if (!confirmed[0]) return false;

        if (inPath[0].isEmpty()) {
            IJ.error("ZTracker", "No input folder selected.");
            return false;
        }
        if (outPath[0].isEmpty()) {
            IJ.error("ZTracker", "No output folder selected.");
            return false;
        }

        File inputDir  = new File(inPath[0]);
        File outputDir = new File(outPath[0]);
        if (!inputDir.isDirectory()) {
            IJ.error("ZTracker", "Input folder not found:\n" + inputDir.getAbsolutePath());
            return false;
        }

        boolean batch = SCOPE_BATCH.equals(scopeChoice.getSelectedItem());
        ZProjector.Mode mode = PROJ_MIN.equals(modeChoice.getSelectedItem())
                ? ZProjector.Mode.MIN_Z
                : ZProjector.Mode.MAX_Z;

        outputDir.mkdirs();
        config = new Config(inputDir, batch, mode, outputDir, rawBox.getState());
        return true;
    }

    // ── Layout helpers (duplicated from ZTrackerDialog by design) ──────────────

    /**
     * Same "bold title / gray description / browse button / path label" grid group used
     * by the extractor dialog's Step 1 and Step 6. Duplicated here intentionally so the
     * existing {@code ZTrackerDialog} is not modified.
     */
    private static int addInputGroup(Panel grid, int startRow, boolean isFirst,
                                     String title, String description,
                                     Button btn, Label pathLabel) {
        GridBagConstraints c;

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

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(0, 8, 6, 8);
        Label descLabel = new Label(description);
        descLabel.setForeground(Color.DARK_GRAY);
        grid.add(descLabel, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 2, 8);
        grid.add(btn, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(2, 8, 4, 8);
        grid.add(pathLabel, c);

        return startRow;
    }

    /** A bold label above a {@link Choice}, matching the group spacing of {@code addInputGroup}. */
    private static int addChoiceGroup(Panel grid, int startRow, String title, Choice choice) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(16, 8, 2, 8);
        Label titleLabel = new Label(title);
        Font base = titleLabel.getFont();
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        titleLabel.setFont(base.deriveFont(Font.BOLD));
        grid.add(titleLabel, c);

        c = new GridBagConstraints();
        c.gridx = 0; c.gridy = startRow++;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 4, 8);
        grid.add(choice, c);

        return startRow;
    }
}
