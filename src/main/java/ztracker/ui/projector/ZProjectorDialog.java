package ztracker.ui.projector;

import ij.IJ;
import ij.io.DirectoryChooser;
import ztracker.project.ZProjector;

import java.awt.BorderLayout;
import java.awt.Button;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
        /** The projection(s) to run — one of Max-Z / Min-Z, or both (in run order). */
        public final List<ZProjector.Mode> modes;
        public final File outputDir;
        /** Which z-origin TIFF depth(s) to write; at least one is always true. */
        public final boolean write16Bit;
        public final boolean write32Bit;

        Config(File inputDir, boolean batch, List<ZProjector.Mode> modes, File outputDir,
               boolean write16Bit, boolean write32Bit) {
            this.inputDir   = inputDir;
            this.batch      = batch;
            this.modes      = modes;
            this.outputDir  = outputDir;
            this.write16Bit = write16Bit;
            this.write32Bit = write32Bit;
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
    private static final String PROJ_BOTH    = "Both  (Max-Z and Min-Z — separate max_z / min_z sub-folders)";

    private static final String DEPTH_16     = "16-bit  (smaller & faster — Z-layer indices up to 65,535)";
    private static final String DEPTH_32     = "32-bit  (larger — always safe, any index)";
    private static final String DEPTH_BOTH   = "Both  (16-bit and 32-bit)";

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

        // Scope first — it tells the user what the input folder below should point at.
        final Choice scopeChoice = new Choice();
        scopeChoice.add(SCOPE_SINGLE);
        scopeChoice.add(SCOPE_BATCH);
        row = addChoiceGroup(grid, row, true, "Scope", scopeChoice);

        // Input folder — no description; the Scope choice above already explains it.
        row = addInputGroup(grid, row, false, "Input folder", null, inBtn, inPathLbl);

        // Output folder.
        row = addInputGroup(grid, row, false,
                "Output folder",
                "Where the max_z / min_z output tree is written (raw projection, z_origin TIFFs, JSON mappings).",
                outBtn, outPathLbl);

        // Projection: Max-Z, Min-Z, or both.
        final Choice modeChoice = new Choice();
        modeChoice.add(PROJ_MAX);
        modeChoice.add(PROJ_MIN);
        modeChoice.add(PROJ_BOTH);
        row = addChoiceGroup(grid, row, false, "Projection", modeChoice);

        // Z-origin bit depth: pick one to run faster, or Both. 16-bit is the default.
        final Choice depthChoice = new Choice();
        depthChoice.add(DEPTH_16);
        depthChoice.add(DEPTH_32);
        depthChoice.add(DEPTH_BOTH);
        row = addChoiceGroup(grid, row, false, "Z-origin bit depth", depthChoice);

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

        String proj = modeChoice.getSelectedItem();
        List<ZProjector.Mode> modes;
        if (PROJ_BOTH.equals(proj)) {
            modes = Arrays.asList(ZProjector.Mode.MAX_Z, ZProjector.Mode.MIN_Z);
        } else if (PROJ_MIN.equals(proj)) {
            modes = Collections.singletonList(ZProjector.Mode.MIN_Z);
        } else {
            modes = Collections.singletonList(ZProjector.Mode.MAX_Z);
        }

        String depth = depthChoice.getSelectedItem();
        boolean write16 = DEPTH_16.equals(depth) || DEPTH_BOTH.equals(depth);
        boolean write32 = DEPTH_32.equals(depth) || DEPTH_BOTH.equals(depth);

        outputDir.mkdirs();
        config = new Config(inputDir, batch, modes, outputDir, write16, write32);
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

        // Description row is optional — skipped entirely when null/empty (no blank gap).
        if (description != null && !description.isEmpty()) {
            c = new GridBagConstraints();
            c.gridx = 0; c.gridy = startRow++;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.insets = new Insets(0, 8, 6, 8);
            Label descLabel = new Label(description);
            descLabel.setForeground(Color.DARK_GRAY);
            grid.add(descLabel, c);
        }

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
    private static int addChoiceGroup(Panel grid, int startRow, boolean isFirst, String title, Choice choice) {
        GridBagConstraints c = new GridBagConstraints();
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
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 8, 4, 8);
        grid.add(choice, c);

        return startRow;
    }
}
