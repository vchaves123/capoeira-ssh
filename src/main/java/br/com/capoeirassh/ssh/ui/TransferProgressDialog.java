package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

/**
 * A two-bar progress window for a background file transfer (upload/download): one bar for the
 * file currently in flight (bytes sent/received vs. its size), one for the whole batch (bytes
 * vs. the sum of all selected files' sizes). The transfer runs on its own thread; this only
 * reflects it. {@link #update} and {@link #close} are safe to call directly from that thread —
 * they marshal onto the UI thread themselves — so the transfer loop doesn't need to know
 * anything about SWT's threading rules.
 *
 * <p>Deliberately modeless (no {@code APPLICATION_MODAL}): a transfer can take a while, and the
 * terminal underneath keeps its own SSH channel, so there is no reason to block it while a
 * separate SFTP channel moves bytes in the background.
 */
public class TransferProgressDialog {

    private final Display     display;
    private final String      baseTitle;
    private final int         totalFiles;
    private final long        totalBytes;
    private final Shell       dlg;
    private final Label       lblFile;
    private final PercentBar  fileBar;
    private final PercentBar  overallBar; // null when there's only one file — nothing distinct to show
    private volatile boolean  cancelled = false;

    /** Must be constructed on the UI thread, before the transfer thread is started.
     *  @param totalFiles number of files in the batch, shown in the window title
     *  @param totalBytes sum of the sizes of every file in the batch, for the overall bar */
    public TransferProgressDialog(Shell parent, String baseTitle, int totalFiles, long totalBytes) {
        this.display    = parent.getDisplay();
        this.baseTitle  = baseTitle;
        this.totalFiles = totalFiles;
        this.totalBytes = Math.max(totalBytes, 1);

        // No APPLICATION_MODAL — the user can keep working in the terminal while this runs.
        dlg = new Shell(parent, SWT.DIALOG_TRIM);
        dlg.setText(baseTitle);
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 16; gl.marginHeight = 14; gl.verticalSpacing = 4;
        dlg.setLayout(gl);

        lblFile = new Label(dlg, SWT.NONE);
        lblFile.setText(" ");
        lblFile.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        fileBar = new PercentBar(dlg);
        GridData gdFileBar = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdFileBar.widthHint = 380; gdFileBar.heightHint = 22;
        fileBar.setLayoutData(gdFileBar);

        // The overall bar only says something a single-file transfer's own bar doesn't already —
        // skip it there instead of showing two identical bars.
        if (totalFiles > 1) {
            Label lblOverall = new Label(dlg, SWT.NONE);
            lblOverall.setText("Overall:");
            GridData gdOverallLbl = new GridData(SWT.FILL, SWT.CENTER, true, false);
            gdOverallLbl.verticalIndent = 8;
            lblOverall.setLayoutData(gdOverallLbl);

            overallBar = new PercentBar(dlg);
            GridData gdOverallBar = new GridData(SWT.FILL, SWT.CENTER, true, false);
            gdOverallBar.heightHint = 22;
            overallBar.setLayoutData(gdOverallBar);
        } else {
            overallBar = null;
        }

        Button btnCancel = new Button(dlg, SWT.PUSH);
        btnCancel.setText("Cancel");
        GridData gdCancel = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        gdCancel.verticalIndent = 8;
        btnCancel.setLayoutData(gdCancel);
        btnCancel.addListener(SWT.Selection, e -> cancelled = true);

        // The X button behaves like Cancel — mark it and let the transfer thread close the
        // window itself once it has actually stopped, rather than tearing down mid-transfer.
        dlg.addListener(SWT.Close, e -> { cancelled = true; e.doit = false; });

        dlg.pack();
        Rectangle rp = parent.getBounds(), rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
        dlg.open();
    }

    /** Polled by the transfer loop (and returned from each byte-count callback) so Cancel takes
     *  effect right away, mid-file, not just between files. */
    public boolean isCancelled() { return cancelled; }

    /**
     * Reports transfer progress. Safe to call from the background transfer thread.
     *
     * @param fileIndex   1-based position of the current file in the batch, for the title
     * @param fileName    name of the file currently in flight
     * @param fileDone    bytes sent/received for that file so far
     * @param fileTotal   that file's total size
     * @param overallDone bytes sent/received across the whole batch so far
     */
    public void update(int fileIndex, String fileName, long fileDone, long fileTotal, long overallDone) {
        display.asyncExec(() -> {
            if (dlg.isDisposed()) return;
            dlg.setText(baseTitle + " — file " + fileIndex + " of " + totalFiles);
            lblFile.setText(fileName);
            fileBar.set(ratio(fileDone, fileTotal), humanSize(fileDone) + " / " + humanSize(fileTotal));
            if (overallBar != null)
                overallBar.set(ratio(overallDone, totalBytes), humanSize(overallDone) + " / " + humanSize(totalBytes));
        });
    }

    /** Closes the window once the transfer (or its cancellation) is done. Safe to call from the
     *  background transfer thread. */
    public void close() {
        display.asyncExec(() -> { if (!dlg.isDisposed()) dlg.dispose(); });
    }

    /** Package-private (not private) so a JUnit test in this package can drive it directly. */
    static double ratio(long done, long total) {
        return Math.max(0.0, Math.min(1.0, (double) done / (double) Math.max(total, 1)));
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /** A progress bar that draws its own "done / total" text centered inside the fill, like a
     *  download manager — the native SWT {@code ProgressBar} has no way to host a label. Drawn
     *  twice (full width in the normal foreground, then again clipped to the filled portion in
     *  the selection colors) so the text stays readable whether it lands on the filled or
     *  unfilled part of the bar. */
    private static final class PercentBar extends Canvas {
        private double ratio = 0;
        private String text  = "";

        PercentBar(Composite parent) {
            super(parent, SWT.NONE);
            addPaintListener(paintListener());
        }

        void set(double ratio, String text) {
            this.ratio = ratio;
            this.text  = text;
            if (!isDisposed()) redraw();
        }

        private PaintListener paintListener() {
            return e -> {
                GC gc = e.gc;
                Rectangle r = getClientArea();
                if (r.width <= 0 || r.height <= 0) return;
                int fillWidth = (int) Math.round(r.width * ratio);

                gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_LIGHT_SHADOW));
                gc.fillRectangle(r);

                Point ext = gc.textExtent(text);
                int tx = r.x + (r.width - ext.x) / 2;
                int ty = r.y + (r.height - ext.y) / 2;
                gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                gc.drawText(text, tx, ty, true);

                if (fillWidth > 0) {
                    gc.setClipping(r.x, r.y, fillWidth, r.height);
                    gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
                    gc.fillRectangle(r);
                    gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT));
                    gc.drawText(text, tx, ty, true);
                    gc.setClipping((Rectangle) null);
                }

                gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                gc.drawRectangle(r.x, r.y, r.width - 1, r.height - 1);
            };
        }

        @Override
        public Point computeSize(int wHint, int hHint, boolean changed) {
            return new Point(wHint == SWT.DEFAULT ? 380 : wHint, hHint == SWT.DEFAULT ? 22 : hHint);
        }
    }
}
