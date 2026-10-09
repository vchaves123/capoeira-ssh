package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.model.TunnelSpec;
import br.com.capoeirassh.ssh.ssh.TunnelConnection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-modal window listing one session's tunnels with their live state, and Start / Stop controls.
 * The tunnels themselves live in the {@link TunnelConnection} owned by the main window, so closing
 * this window never stops them — only the tab closing (or "Stop") does.
 */
class TunnelManagerWindow {

    private static final int REFRESH_MS = 1000;

    private final Shell            parent;
    private final Display          display;
    private final SessionInfo      info;
    private final TunnelConnection conn;
    private final Runnable         onStateChanged;

    private Shell  shell;
    private Table  table;
    private Button btnStart, btnStop, btnStartAll, btnStopAll;
    private boolean busy;

    TunnelManagerWindow(Shell parent, SessionInfo info, TunnelConnection conn, Runnable onStateChanged) {
        this.parent         = parent;
        this.display        = parent.getDisplay();
        this.info           = info;
        this.conn           = conn;
        this.onStateChanged = onStateChanged;
    }

    boolean isDisposed() { return shell == null || shell.isDisposed(); }

    /** Opens the window, or brings it to the front if it's already open. */
    void open() {
        if (!isDisposed()) { shell.setMinimized(false); shell.forceActive(); return; }

        shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE);
        shell.setText("Tunnels — " + info.label());
        AppIcon.apply(shell);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 10; gl.verticalSpacing = 8;
        shell.setLayout(gl);

        table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gdT = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdT.widthHint = 640; gdT.heightHint = 180;
        table.setLayoutData(gdT);
        String[] cols = { "Status", "Type", "Listen on", "Forward to", "Description" };
        int[] widths  = { 170, 60, 150, 170, 120 };
        for (int i = 0; i < cols.length; i++) {
            TableColumn c = new TableColumn(table, SWT.NONE);
            c.setText(cols[i]); c.setWidth(widths[i]);
        }

        Composite btns = new Composite(shell, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout bl = new GridLayout(5, false);
        bl.marginWidth = 0; bl.marginHeight = 0; bl.horizontalSpacing = 8;
        btns.setLayout(bl);
        btnStart    = button(btns, "Start");
        btnStop     = button(btns, "Stop");
        btnStartAll = button(btns, "Start all");
        btnStopAll  = button(btns, "Stop all");
        Button btnClose = button(btns, "Close");
        ((GridData) btnClose.getLayoutData()).horizontalAlignment = SWT.RIGHT;
        ((GridData) btnClose.getLayoutData()).grabExcessHorizontalSpace = true;

        Label note = new Label(shell, SWT.WRAP);
        GridData gdN = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdN.widthHint = 640;
        note.setLayoutData(gdN);
        note.setText("Tunnels use their own SSH connection and keep running if you close this window. "
            + "They stop when the tab is closed. Edit the list from the session's \"Tunnels…\" button.");

        btnStart.addListener(SWT.Selection, e -> startSelected());
        btnStop.addListener(SWT.Selection, e -> stop(selected()));
        btnStartAll.addListener(SWT.Selection, e -> start(allSpecs()));
        btnStopAll.addListener(SWT.Selection, e -> stop(allSpecs()));
        btnClose.addListener(SWT.Selection, e -> shell.dispose());
        table.addListener(SWT.Selection, e -> updateButtons());

        shell.pack();
        Rectangle rp = parent.getBounds(); Rectangle rc = shell.getBounds();
        shell.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
        shell.open();

        refresh();
        display.timerExec(REFRESH_MS, new Runnable() {
            @Override public void run() {
                if (isDisposed()) return;
                refresh();
                display.timerExec(REFRESH_MS, this);
            }
        });
    }

    void close() { if (!isDisposed()) shell.dispose(); }

    // ── Actions ──────────────────────────────────────────────────────────────

    private List<TunnelSpec> allSpecs() { return new ArrayList<>(info.tunnels); }

    private List<TunnelSpec> selected() {
        List<TunnelSpec> out = new ArrayList<>();
        for (int i : table.getSelectionIndices())
            if (i < info.tunnels.size()) out.add(info.tunnels.get(i));
        return out;
    }

    private void startSelected() { start(selected()); }

    private void start(List<TunnelSpec> specs) {
        if (busy || specs.isEmpty()) return;
        busy = true;
        updateButtons();
        startTunnels(shell, info, conn, specs, this::afterWork);
    }

    private void stop(List<TunnelSpec> specs) {
        if (busy || specs.isEmpty()) return;
        busy = true;
        updateButtons();
        Thread.ofVirtual().name("tunnel-stop").start(() -> {
            for (TunnelSpec t : specs) conn.stop(t);
            display.asyncExec(this::afterWork);
        });
    }

    private void afterWork() {
        busy = false;
        if (!isDisposed()) refresh();
        onStateChanged.run();
    }

    /**
     * Resolves credentials and connects if needed (UI thread, modal, same flow as SFTP), then starts
     * {@code specs} on a worker and calls {@code done} on the UI thread. Safe to call with no
     * visible window ({@code owner} is just the parent for the prompts). Per-tunnel failures are
     * recorded in {@code conn} and shown by the window, never thrown.
     */
    static void startTunnels(Shell owner, SessionInfo info, TunnelConnection conn,
                             List<TunnelSpec> specs, Runnable done) {
        if (!ensureConnected(owner, info, conn)) { done.run(); return; }
        Display display = owner.getDisplay();
        Thread.ofVirtual().name("tunnel-start").start(() -> {
            for (TunnelSpec t : specs) {
                try { conn.start(t); } catch (Exception ignored) { /* recorded in conn.errorOf(t) */ }
            }
            display.asyncExec(() -> { if (!owner.isDisposed()) done.run(); });
        });
    }

    /** Prompts for credentials (silent for a saved credential) and opens the tunnel SSH session. */
    static boolean ensureConnected(Shell owner, SessionInfo info, TunnelConnection conn) {
        if (conn.isConnected()) return true;
        char[] password = new ConnectDialog(owner, info).open();
        if (password == null) return false; // cancelled
        try {
            BusyDialog.run(owner, "Tunnels", "Connecting to " + info.host + "…", () -> {
                conn.connect(info, password, owner.getDisplay());
                return null;
            });
            return true;
        } catch (Exception ex) {
            MessageBox mb = new MessageBox(owner, SWT.ICON_ERROR | SWT.OK);
            mb.setText("Tunnels");
            mb.setMessage("Could not connect to " + info.host + ":\n\n"
                + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            mb.open();
            return false;
        }
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private void refresh() {
        if (isDisposed()) return;
        int[] sel = table.getSelectionIndices();
        table.removeAll();
        for (TunnelSpec t : info.tunnels) {
            TableItem it = new TableItem(table, SWT.NONE);
            String err = conn.errorOf(t);
            String status = conn.isActive(t) ? "● Active"
                          : err != null      ? "✕ " + err
                          :                    "○ Stopped";
            it.setText(new String[] {
                status,
                t.type == TunnelSpec.Type.LOCAL ? "Local" : "Remote",
                t.bindHost + ":" + t.bindPort,
                t.destHost + ":" + t.destPort,
                t.description == null ? "" : t.description });
        }
        for (int i : sel) if (i < table.getItemCount()) table.select(i);
        updateButtons();
    }

    private void updateButtons() {
        if (isDisposed()) return;
        boolean any = !info.tunnels.isEmpty();
        boolean sel = table.getSelectionCount() > 0;
        btnStart.setEnabled(!busy && sel);
        btnStop.setEnabled(!busy && sel);
        btnStartAll.setEnabled(!busy && any);
        btnStopAll.setEnabled(!busy && any);
    }

    private static Button button(Composite p, String text) {
        Button b = new Button(p, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return b;
    }

    /** Which of {@code info}'s tunnels are flagged for auto-start. */
    static List<TunnelSpec> autoStartSpecs(SessionInfo info) {
        List<TunnelSpec> out = new ArrayList<>();
        for (TunnelSpec t : info.tunnels) if (t.autoStart) out.add(t);
        return out;
    }
}
