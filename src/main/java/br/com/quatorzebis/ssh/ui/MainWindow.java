package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.SessionInfo;
import br.com.quatorzebis.ssh.storage.CredentialStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Main application window.
 *
 * Layout:
 *   ┌──────────────────────────────────────────────────┐
 *   │  [Sessions] [user@host] [user@host] …            │
 *   ├──────────────────────────────────────────────────┤
 *   │  Tab content (session tree  OR  terminal)        │
 *   └──────────────────────────────────────────────────┘
 */
public class MainWindow {

    private final Display display;
    private Shell         shell;
    private CTabFolder    tabFolder;
    private SessionsTab   sessionsTab;

    private final List<TerminalTab> terminalTabs = new ArrayList<>();

    // Tab drag-reorder state
    private CTabItem draggedTab     = null;
    private int      dragStartX     = 0;
    private int      dropIndicatorX = -1;

    public MainWindow(Display display) {
        this.display = display;
    }

    // -----------------------------------------------------------------------
    // Open
    // -----------------------------------------------------------------------
    public void open() {
        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText("14bis SSH");
        shell.setLayout(new FillLayout());
        shell.setSize(1000, 650);
        centerOnScreen();

        AppIcon.apply(shell);

        buildContent();
        setupGlobalKeyFilter();

        shell.addListener(SWT.Close, e -> {
            long active = terminalTabs.stream().filter(t -> !t.isDisconnected()).count();
            if (active > 0 && !confirmClose(active)) {
                e.doit = false;
                return;
            }
            closeAll();
        });

        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    // -----------------------------------------------------------------------
    // Content — tab folder fills the whole shell
    // -----------------------------------------------------------------------
    private void buildContent() {
        tabFolder = new CTabFolder(shell, SWT.TOP | SWT.BORDER);
        tabFolder.setSimple(false);
        tabFolder.setTabHeight(22);

        tabFolder.setSelectionBackground(
            new Color[]{ new Color(display, 58, 58, 58), new Color(display, 30, 30, 30) },
            new int[]{ 100 }, true);
        tabFolder.setSelectionForeground(display.getSystemColor(SWT.COLOR_WHITE));

        // Clear activity dot when user switches to a terminal tab
        tabFolder.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                CTabItem sel = tabFolder.getSelection();
                terminalTabs.stream()
                    .filter(t -> t.getTabItem() == sel)
                    .findFirst()
                    .ifPresent(TerminalTab::clearActivity);
            }
        });

        tabFolder.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent event) {
                // Sessions tab is permanent — never close it
                if (sessionsTab != null && event.item == sessionsTab.getTabItem()) {
                    event.doit = false;
                    return;
                }
                terminalTabs.stream()
                    .filter(t -> t.getTabItem() == event.item)
                    .findFirst()
                    .ifPresent(t -> {
                        terminalTabs.remove(t);
                        t.dispose();
                    });
                // If all terminal tabs closed, go back to Sessions tab
                display.asyncExec(() -> {
                    if (!tabFolder.isDisposed() && tabFolder.getItemCount() <= 1) {
                        showSessionsTab();
                    }
                });
            }
        });

        setupTabContextMenu();
        setupTabDragReorder();

        // Create the permanent Sessions tab
        sessionsTab = new SessionsTab(tabFolder, shell, this::openTerminal,
                this::openCredentialManager, this::showAbout);
        tabFolder.setSelection(sessionsTab.getTabItem());
    }

    private void showSessionsTab() {
        if (sessionsTab != null && !sessionsTab.getTabItem().isDisposed())
            tabFolder.setSelection(sessionsTab.getTabItem());
    }

    // -----------------------------------------------------------------------
    // Open a terminal tab for a session
    // -----------------------------------------------------------------------
    private void openTerminal(SessionInfo info) {
        ConnectDialog dlg = new ConnectDialog(shell, info);
        char[] password = dlg.open();
        if (password == null) return;

        TerminalTab tab = new TerminalTab(tabFolder, info, password);
        tab.setOnReconnectRequest(() -> reconnectTab(tab));
        if (info.appearFontSize > 0) {
            tab.applyAppearance(info.appearFontSize,
                new org.eclipse.swt.graphics.RGB(info.appearFgR, info.appearFgG, info.appearFgB),
                new org.eclipse.swt.graphics.RGB(info.appearBgR, info.appearBgG, info.appearBgB));
        }
        terminalTabs.add(tab);
        tabFolder.setSelection(tab.getTabItem());
        tab.getCanvas().setFocus();
    }

    // -----------------------------------------------------------------------
    // Global key filter (Ctrl+N / Ctrl+W / Ctrl+Tab)
    // -----------------------------------------------------------------------
    private void setupGlobalKeyFilter() {
        display.addFilter(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.CTRL) == 0) return;
            if (e.keyCode == 'n' || e.keyCode == 'N') { newSession();      e.doit = false; }
            if (e.keyCode == 'w' || e.keyCode == 'W') { closeCurrentTab(); e.doit = false; }
            if (e.keyCode == SWT.TAB) {
                navigateTab((e.stateMask & SWT.SHIFT) != 0 ? -1 : 1);
                e.doit = false;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------
    private void newSession() {
        SessionDialog dlg = new SessionDialog(shell, "");
        SessionInfo saved = dlg.open();
        if (saved != null) {
            sessionsTab.reload();
            openTerminal(saved);
        }
    }

    private void closeCurrentTab() {
        CTabItem current = tabFolder.getSelection();
        if (current == null) return;

        // Sessions tab is permanent — never close it
        if (sessionsTab != null && current == sessionsTab.getTabItem()) return;

        terminalTabs.stream()
            .filter(t -> t.getTabItem() == current)
            .findFirst()
            .ifPresent(t -> {
                terminalTabs.remove(t);
                t.dispose();
                current.dispose();
                if (tabFolder.getItemCount() <= 1) showSessionsTab();
            });
    }

    private void navigateTab(int delta) {
        int total = tabFolder.getItemCount();
        if (total == 0) return;
        int next = (tabFolder.getSelectionIndex() + delta + total) % total;
        tabFolder.setSelection(next);
        // Focus the canvas of the selected terminal tab
        CTabItem sel = tabFolder.getSelection();
        terminalTabs.stream()
            .filter(t -> t.getTabItem() == sel)
            .findFirst()
            .ifPresent(t -> t.getCanvas().setFocus());
    }

    private void openCredentialManager() {
        new CredentialManagerDialog(shell).open();
    }

    private void showAbout() {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("About 14bis SSH");
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 24; gl.marginHeight = 18; gl.verticalSpacing = 12;
        dlg.setLayout(gl);

        Label lbl = new Label(dlg, SWT.NONE);
        lbl.setText("14bis SSH Client\nVersion 1.0.0\n\nxterm-256color terminal emulator built with SWT.");
        lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Button ok = new Button(dlg, SWT.PUSH);
        ok.setText("  OK  ");
        ok.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        ok.addListener(SWT.Selection, e -> dlg.close());

        dlg.setDefaultButton(ok);
        dlg.pack();
        Rectangle pb = shell.getBounds();
        org.eclipse.swt.graphics.Point sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);
        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    // -----------------------------------------------------------------------
    // Reconnect
    // -----------------------------------------------------------------------
    private void reconnectTab(TerminalTab tab) {
        ConnectDialog dlg = new ConnectDialog(shell, tab.getSessionInfo());
        char[] pw = dlg.open();
        if (pw != null) tab.reconnect(pw);
    }

    // -----------------------------------------------------------------------
    // Tab right-click context menu
    // -----------------------------------------------------------------------
    private void setupTabContextMenu() {
        tabFolder.addListener(SWT.MenuDetect, e -> {
            Point local = tabFolder.toControl(e.x, e.y);
            CTabItem item = tabFolder.getItem(local);
            if (item == null) return;
            if (sessionsTab != null && item == sessionsTab.getTabItem()) return;

            TerminalTab terminal = terminalTabs.stream()
                .filter(t -> t.getTabItem() == item)
                .findFirst().orElse(null);
            if (terminal == null) return;

            Menu menu = new Menu(shell, SWT.POP_UP);

            if (terminal.isDisconnected()) {
                MenuItem miReconnect = new MenuItem(menu, SWT.PUSH);
                miReconnect.setText("Reconnect");
                miReconnect.addListener(SWT.Selection, ev -> reconnectTab(terminal));
                new MenuItem(menu, SWT.SEPARATOR);
            }

            MenuItem miDuplicate = new MenuItem(menu, SWT.PUSH);
            miDuplicate.setText("Duplicate Session");
            miDuplicate.addListener(SWT.Selection, ev -> {
                br.com.quatorzebis.ssh.model.SessionInfo info = terminal.getSessionInfo();
                int[] a = terminal.getAppearance();
                info.appearFontSize = a[0];
                info.appearFgR = a[1]; info.appearFgG = a[2]; info.appearFgB = a[3];
                info.appearBgR = a[4]; info.appearBgG = a[5]; info.appearBgB = a[6];
                openTerminal(info);
            });

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem miAppearance = new MenuItem(menu, SWT.PUSH);
            miAppearance.setText("Terminal Appearance...");
            miAppearance.addListener(SWT.Selection, ev -> {
                int[] a = terminal.getAppearance();
                TerminalAppearanceDialog dlg = new TerminalAppearanceDialog(shell,
                    a[0],
                    new org.eclipse.swt.graphics.RGB(a[1], a[2], a[3]),
                    new org.eclipse.swt.graphics.RGB(a[4], a[5], a[6]));
                if (dlg.open()) {
                    terminal.applyAppearance(dlg.getChosenFontSize(),
                                             dlg.getChosenFgColor(),
                                             dlg.getChosenBgColor());
                }
            });

            new MenuItem(menu, SWT.SEPARATOR);

            if (terminal.isLogging()) {
                MenuItem miLogStop = new MenuItem(menu, SWT.PUSH);
                miLogStop.setText("Stop Logging");
                miLogStop.addListener(SWT.Selection, ev -> terminal.stopLogging());
            } else {
                MenuItem miLogStart = new MenuItem(menu, SWT.PUSH);
                miLogStart.setText("Start Logging");
                miLogStart.addListener(SWT.Selection, ev ->
                    terminal.startLogging(terminal.getLogDir(), terminal.getLogFileName()));
            }

            MenuItem miLogChange = new MenuItem(menu, SWT.PUSH);
            miLogChange.setText("Log Settings...");
            miLogChange.addListener(SWT.Selection, ev -> showLogSettingsDialog(terminal));

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem miClose = new MenuItem(menu, SWT.PUSH);
            miClose.setText("Close Session");
            miClose.addListener(SWT.Selection, ev -> {
                terminalTabs.remove(terminal);
                terminal.dispose();
                item.dispose();
                if (tabFolder.getItemCount() <= 1) showSessionsTab();
            });

            menu.setLocation(e.x, e.y);
            menu.setVisible(true);
        });
    }

    private void showLogSettingsDialog(TerminalTab terminal) {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Log Settings");
        AppIcon.apply(dlg);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 14; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label lblDir = new Label(dlg, SWT.NONE); lblDir.setText("Directory:");
        Text txtDir = new Text(dlg, SWT.BORDER | SWT.READ_ONLY);
        txtDir.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtDir.setText(terminal.getLogDir());
        Button btnBrowse = new Button(dlg, SWT.PUSH); btnBrowse.setText("…");
        btnBrowse.addListener(SWT.Selection, e -> {
            DirectoryDialog dd = new DirectoryDialog(dlg, SWT.NONE);
            dd.setText("Select log directory");
            dd.setFilterPath(txtDir.getText());
            String chosen = dd.open();
            if (chosen != null) txtDir.setText(chosen);
        });

        Label lblFile = new Label(dlg, SWT.NONE); lblFile.setText("File name:");
        Text txtFile = new Text(dlg, SWT.BORDER);
        GridData gdFile = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdFile.horizontalSpan = 2;
        txtFile.setLayoutData(gdFile);
        txtFile.setMessage("e.g. session (timestamp prepended automatically)");
        txtFile.setText(terminal.getLogFileName());

        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        GridData gdBtns = new GridData(SWT.RIGHT, SWT.CENTER, true, false);
        gdBtns.horizontalSpan = 2;
        cmpBtns.setLayoutData(gdBtns);
        org.eclipse.swt.layout.RowLayout rl = new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8; cmpBtns.setLayout(rl);

        Button btnApply  = new Button(cmpBtns, SWT.PUSH); btnApply.setText("Apply & Start");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnApply);

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnApply.addListener(SWT.Selection, e -> {
            terminal.startLogging(txtDir.getText().trim(), txtFile.getText().trim());
            dlg.dispose();
        });

        dlg.pack();
        dlg.setSize(Math.max(dlg.getSize().x, 420), dlg.getSize().y);
        Rectangle pb = shell.getBounds();
        org.eclipse.swt.graphics.Point sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);
        dlg.open();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
    }

    private void closeAll() {
        for (TerminalTab t : terminalTabs) t.dispose();
        terminalTabs.clear();
    }

    // -----------------------------------------------------------------------
    // Center window
    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    // Tab drag-and-drop reordering
    // -----------------------------------------------------------------------
    private void setupTabDragReorder() {
        final int DRAG_THRESHOLD = 5;

        tabFolder.addListener(SWT.MouseDown, e -> {
            if (e.button != 1) return;
            CTabItem item = tabFolder.getItem(new Point(e.x, e.y));
            if (item == null) return;
            // Sessions tab stays put
            if (sessionsTab != null && item == sessionsTab.getTabItem()) return;
            draggedTab  = item;
            dragStartX  = e.x;
        });

        tabFolder.addListener(SWT.MouseMove, e -> {
            if (draggedTab == null) return;
            if (Math.abs(e.x - dragStartX) < DRAG_THRESHOLD) return;
            dropIndicatorX = e.x;
            tabFolder.setCursor(display.getSystemCursor(SWT.CURSOR_SIZEWE));
            tabFolder.redraw();
        });

        tabFolder.addListener(SWT.MouseUp, e -> {
            if (draggedTab == null) return;
            CTabItem src = draggedTab;
            draggedTab     = null;
            dropIndicatorX = -1;
            tabFolder.setCursor(null);
            tabFolder.redraw();

            if (Math.abs(e.x - dragStartX) < DRAG_THRESHOLD) return;

            int insertIdx = dropIndexAt(e.x, src);
            int srcIdx    = tabFolder.indexOf(src);
            if (insertIdx == srcIdx || insertIdx == srcIdx + 1) return;

            moveTab(src, srcIdx, insertIdx);
        });

        // Paint the drop-position indicator (a bright vertical bar)
        tabFolder.addListener(SWT.Paint, e -> {
            if (dropIndicatorX < 0 || draggedTab == null) return;
            int insertIdx = dropIndexAt(dropIndicatorX, draggedTab);
            int lineX     = insertLineX(insertIdx);
            if (lineX < 0) return;
            e.gc.setForeground(new Color(display, 80, 180, 255));
            e.gc.setLineWidth(2);
            e.gc.drawLine(lineX, 0, lineX, tabFolder.getTabHeight());
        });
    }

    /** Returns the pixel X of the insert-position marker for the given index. */
    private int insertLineX(int insertIdx) {
        CTabItem[] items = tabFolder.getItems();
        if (items.length == 0) return -1;
        if (insertIdx >= items.length) {
            Rectangle b = items[items.length - 1].getBounds();
            return b.x + b.width;
        }
        return items[insertIdx].getBounds().x;
    }

    /** Given a mouse X, returns the tab index before which the dragged tab should be inserted. */
    private int dropIndexAt(int mouseX, CTabItem src) {
        CTabItem[] items = tabFolder.getItems();
        for (int i = 0; i < items.length; i++) {
            Rectangle b = items[i].getBounds();
            if (mouseX < b.x + b.width / 2) return i;
        }
        return items.length;
    }

    /**
     * Moves a CTabItem from srcIdx to insertIdx by recreating it at the new position.
     * Updates the corresponding TerminalTab reference and the welcomeTab reference if needed.
     */
    private void moveTab(CTabItem src, int srcIdx, int insertIdx) {
        // Save all properties
        String   text      = src.getText();
        Image    image     = src.getImage();
        String   tooltip   = src.getToolTipText();
        Control  control   = src.getControl();
        boolean  showClose = (src.getStyle() & SWT.CLOSE) != 0;

        // Find the TerminalTab wrapper (may be null for the welcome tab)
        TerminalTab terminal = terminalTabs.stream()
            .filter(t -> t.getTabItem() == src)
            .findFirst().orElse(null);

        // Detach control before dispose (prevents it being disposed with the item)
        src.setControl(null);
        src.dispose();

        // After dispose, indices above srcIdx shift down by one
        int idx = insertIdx > srcIdx ? insertIdx - 1 : insertIdx;
        idx = Math.max(0, Math.min(idx, tabFolder.getItemCount()));

        CTabItem newItem = new CTabItem(tabFolder, showClose ? SWT.CLOSE : SWT.NONE, idx);
        newItem.setText(text);
        if (image   != null) newItem.setImage(image);
        if (tooltip != null) newItem.setToolTipText(tooltip);
        newItem.setControl(control);

        if (terminal != null) terminal.replaceTabItem(newItem);
        tabFolder.setSelection(newItem);
    }

    // -----------------------------------------------------------------------
    private void centerOnScreen() {
        Monitor  monitor = display.getPrimaryMonitor();
        Rectangle screen = monitor.getClientArea();
        Rectangle win    = shell.getBounds();
        shell.setLocation(
            screen.x + (screen.width  - win.width)  / 2,
            screen.y + (screen.height - win.height) / 2
        );
    }

    // -----------------------------------------------------------------------
    // Application icon (drawn programmatically)
    // -----------------------------------------------------------------------
    /**
     * Draws a terminal-style icon at the requested size.
     *
     * Visual: rounded dark screen bezel with a subtle teal/green glow,
     * a bright ">" prompt and blinking-cursor underscore, and two thin
     * scan-lines for texture.  Works at both 16 px and 32 px.
     */
    private Image buildAppIcon(int sz) {
        PaletteData pal = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData   data = new ImageData(sz, sz, 24, pal);
        // Use pure magenta as the transparency key — it never appears in the drawing
        data.transparentPixel = pal.getPixel(new RGB(255, 0, 255));

        Image img = new Image(display, data);
        GC    gc  = new GC(img);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);

        // --- transparent fill ---
        gc.setBackground(new Color(display, 255, 0, 255));
        gc.fillRectangle(0, 0, sz, sz);

        int r = Math.max(2, sz / 8);   // corner radius

        // Outer bezel (dark charcoal with slight blue tint)
        gc.setBackground(new Color(display, 22, 27, 34));
        gc.fillRoundRectangle(0, 0, sz, sz, r * 2, r * 2);

        // Inner screen area (slightly lighter)
        int pad = Math.max(1, sz / 10);
        gc.setBackground(new Color(display, 13, 17, 23));
        gc.fillRoundRectangle(pad, pad, sz - pad * 2, sz - pad * 2, r, r);

        // Subtle top-edge highlight (teal glow)
        gc.setForeground(new Color(display, 0, 200, 160));
        gc.drawLine(pad + r, pad, sz - pad - r, pad);

        // Prompt ">" — bright green
        Font f = new Font(display, "Consolas", Math.max(6, sz * 5 / 16), SWT.BOLD);
        gc.setFont(f);
        gc.setForeground(new Color(display, 0, 255, 140));
        int tx = pad + Math.max(1, sz / 8);
        int ty = sz / 2 - gc.getFontMetrics().getHeight() / 2 - Math.max(1, sz / 16);
        gc.drawText(">", tx, ty, true);

        // Cursor underscore (white)
        int cx  = tx + gc.stringExtent(">").x + Math.max(1, sz / 16);
        int cy  = ty + gc.getFontMetrics().getHeight() - Math.max(1, sz / 16);
        int cw  = Math.max(2, sz / 5);
        gc.setForeground(new Color(display, 220, 220, 220));
        gc.setLineWidth(Math.max(1, sz / 16));
        gc.drawLine(cx, cy, cx + cw, cy);

        // Thin scan-line texture (one line roughly 2/3 down)
        int sl = pad + (sz - pad * 2) * 2 / 3;
        gc.setForeground(new Color(display, 255, 255, 255));
        gc.setAlpha(18);
        gc.drawLine(pad + 1, sl, sz - pad - 1, sl);
        gc.setAlpha(255);

        f.dispose();
        gc.dispose();
        return img;
    }

    private boolean confirmClose(long activeSessions) {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Close 14bis SSH");
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 20; gl.marginHeight = 16; gl.verticalSpacing = 14;
        dlg.setLayout(gl);

        Label msg = new Label(dlg, SWT.WRAP);
        msg.setText("There " + (activeSessions == 1 ? "is" : "are") + " " + activeSessions
                + " active session" + (activeSessions == 1 ? "" : "s") + ".\nClose anyway?");
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 280;
        msg.setLayoutData(gd);

        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 10;
        btns.setLayout(rl);

        boolean[] result = { false };
        Button btnYes = new Button(btns, SWT.PUSH);
        btnYes.setText("  Close  ");
        btnYes.addListener(SWT.Selection, e -> { result[0] = true;  dlg.close(); });

        Button btnNo = new Button(btns, SWT.PUSH);
        btnNo.setText("  Cancel  ");
        btnNo.addListener(SWT.Selection, e -> { result[0] = false; dlg.close(); });

        dlg.setDefaultButton(btnNo);
        dlg.pack();

        // center over main window
        Rectangle pb = shell.getBounds();
        org.eclipse.swt.graphics.Point sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return result[0];
    }
}
