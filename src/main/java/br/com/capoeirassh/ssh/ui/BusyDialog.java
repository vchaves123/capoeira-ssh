package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

/**
 * Shows a small modal "please wait" dialog (a message + an indeterminate progress bar) while a
 * background task runs, then disposes itself automatically once the task finishes. Blocks the
 * calling (UI) thread inside a nested SWT event loop until then — the same
 * background-thread-plus-{@code asyncExec} pattern already used throughout this codebase
 * (MasterPasswordDialog, KdbxImportDialog's entry listing, CredentialManagerDialog's
 * {@code runPersist}) for work that's too slow for the UI thread but not worth a real
 * percentage-based progress bar.
 */
public final class BusyDialog {

    private BusyDialog() {}

    /** However fast the task actually finishes, the dialog stays on screen at least this long —
     *  without it, a task that completes in a few ms (e.g. importing 1-2 sessions) can dispose
     *  the shell via asyncExec before the display ever gets to paint it, so the indicator never
     *  visibly appears at all. */
    private static final int MIN_VISIBLE_MS = 400;

    public interface Task<T> { T run() throws Exception; }

    /** Runs {@code task} on a background thread while showing {@code message} in a small modal
     *  dialog owned by {@code owner}. Returns the task's result, or rethrows whatever it threw. */
    public static <T> T run(Shell owner, String title, String message, Task<T> task) throws Exception {
        Shell busy = new Shell(owner, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        busy.setText(title);
        AppIcon.apply(busy);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 20; gl.marginHeight = 16; gl.verticalSpacing = 10;
        busy.setLayout(gl);

        Label lbl = new Label(busy, SWT.WRAP);
        lbl.setText(message);
        lbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ProgressBar bar = new ProgressBar(busy, SWT.HORIZONTAL | SWT.INDETERMINATE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        busy.pack();
        if (busy.getSize().x < 320) busy.setSize(320, busy.getSize().y);
        center(busy, owner);

        Object[] outcome = new Object[2]; // [0] = T result, [1] = Exception
        Display display = owner.getDisplay();
        long[] shownAtMs = new long[1];

        Thread t = new Thread(() -> {
            T result = null;
            Exception failure = null;
            try {
                result = task.run();
            } catch (Exception ex) {
                failure = ex;
            }
            T finalResult = result;
            Exception finalFailure = failure;
            display.asyncExec(() -> {
                outcome[0] = finalResult;
                outcome[1] = finalFailure;
                // shownAtMs is only set after busy.open() below, but this asyncExec can't run
                // before that — it's queued on the same display the open()+event-loop below
                // owns — so shownAtMs[0] is always populated by the time we get here.
                long remaining = MIN_VISIBLE_MS - (System.currentTimeMillis() - shownAtMs[0]);
                if (remaining > 0) {
                    display.timerExec((int) remaining, () -> { if (!busy.isDisposed()) busy.dispose(); });
                } else if (!busy.isDisposed()) {
                    busy.dispose();
                }
            });
        }, "busy-dialog-task");
        t.setDaemon(true);

        // Show the dialog and let the display actually paint it *before* the background work
        // starts — starting the thread first risks the task finishing (and queuing a dispose)
        // before the shell has been shown at all.
        busy.open();
        shownAtMs[0] = System.currentTimeMillis();
        t.start();

        while (!busy.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }

        if (outcome[1] != null) throw (Exception) outcome[1];
        @SuppressWarnings("unchecked")
        T result = (T) outcome[0];
        return result;
    }

    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
