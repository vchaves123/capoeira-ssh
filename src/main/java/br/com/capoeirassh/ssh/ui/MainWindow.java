package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.program.Program;
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
    private Color         colorSelectionDisconnectedRed;

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
        shell.setText("Capoeira SSH");
        shell.setLayout(new FillLayout());

        AppIcon.apply(shell);

        buildContent();
        setupGlobalKeyFilter();

        // Size to content on first launch, respecting a minimum
        shell.pack();
        Point packed = shell.getSize();
        shell.setSize(Math.max(packed.x, 780), Math.max(packed.y, 520));
        centerOnScreen();

        shell.addListener(SWT.Close, e -> {
            long active = terminalTabs.stream().filter(t -> !t.isDisconnected()).count();
            if (active > 0 && !confirmClose(active)) {
                e.doit = false;
                return;
            }
            closeAll();
        });

        shell.open();
        br.com.capoeirassh.ssh.UpdateChecker.checkAsync(v ->
            display.asyncExec(() -> { if (sessionsTab != null) sessionsTab.notifyUpdateAvailable(v); }));
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
        colorSelectionDisconnectedRed = new Color(display, 220, 60, 60);
        tabFolder.setSelectionForeground(display.getSystemColor(SWT.COLOR_WHITE));

        // Clear activity dot when user switches to a terminal tab
        tabFolder.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                // Use e.item (the event's new selection) rather than tabFolder.getSelection(),
                // which can be stale on Windows when setSelection() is called programmatically.
                CTabItem sel = (CTabItem) e.item;
                java.util.Optional<TerminalTab> term = terminalTabs.stream()
                    .filter(t -> t.getTabItem() == sel)
                    .findFirst();
                if (term.isPresent()) {
                    // Every tab switch must hand keyboard focus to the newly active tab's own
                    // control — otherwise input can linger on (or leak to) whatever previously
                    // had focus, which is exactly what let arrow keys/Enter meant for a terminal
                    // get hijacked by the Sessions tab's global key filter.
                    term.get().clearActivity();
                    term.get().getCanvas().setFocus();
                } else if (sessionsTab != null && sel == sessionsTab.getTabItem()) {
                    sessionsTab.focusDefault();
                }
                refreshSelectionColor();
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
                TerminalTab t = terminalTabs.stream()
                    .filter(tt -> tt.getTabItem() == event.item)
                    .findFirst().orElse(null);
                if (t == null) return;

                if (!confirmCloseTab()) {
                    event.doit = false;
                    return;
                }
                terminalTabs.remove(t);
                t.dispose();
                reloadSessionsTab();
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
                this::openCredentialManager, this::showAbout,
                () -> terminalTabs.stream()
                        .filter(t -> !t.isDisconnected())
                        .map(t -> t.getSessionInfo().name)
                        .collect(java.util.stream.Collectors.toSet()));
        tabFolder.setSelection(sessionsTab.getTabItem());
    }

    /** Keeps the CTabFolder's selection text colour in sync with the currently selected tab's state. */
    private void refreshSelectionColor() {
        if (tabFolder.isDisposed()) return;
        CTabItem sel = tabFolder.getSelection();
        boolean disconnected = terminalTabs.stream()
            .anyMatch(t -> t.getTabItem() == sel && t.isDisconnected());
        tabFolder.setSelectionForeground(
            disconnected ? colorSelectionDisconnectedRed : display.getSystemColor(SWT.COLOR_WHITE));
    }

    private void showSessionsTab() {
        if (sessionsTab != null && !sessionsTab.getTabItem().isDisposed())
            tabFolder.setSelection(sessionsTab.getTabItem());
    }

    // -----------------------------------------------------------------------
    // Open a terminal tab for a session
    // -----------------------------------------------------------------------
    private void openTerminal(SessionInfo info) {
        openTerminal(info, null);
    }

    /** @param prefillPassword password just typed in the New Session dialog (manual auth,
     *                         not saved to the vault) — pre-fills the Connect dialog once. */
    private void openTerminal(SessionInfo info, char[] prefillPassword) {
        ConnectDialog dlg = prefillPassword != null
            ? new ConnectDialog(shell, info, prefillPassword)
            : new ConnectDialog(shell, info);
        char[] password = dlg.open();
        if (prefillPassword != null) java.util.Arrays.fill(prefillPassword, '\0');
        if (password == null) return;

        TerminalTab tab = new TerminalTab(tabFolder, info, password);
        tab.setOnReconnectRequest(() -> reconnectTab(tab));
        tab.setOnStateChanged(() -> { refreshSelectionColor(); reloadSessionsTab(); });
        if (info.appearFontSize > 0) {
            tab.applyAppearance(info.appearFontName, info.appearFontSize,
                new org.eclipse.swt.graphics.RGB(info.appearFgR, info.appearFgG, info.appearFgB),
                new org.eclipse.swt.graphics.RGB(info.appearBgR, info.appearBgG, info.appearBgB));
        }
        terminalTabs.add(tab);
        reloadSessionsTab();
        tabFolder.setSelection(tab.getTabItem());
        // Re-assert correct selection colour after all pending asyncExec callbacks
        // (e.g. a prior disconnected tab's onStateChanged) have had a chance to run.
        display.asyncExec(this::refreshSelectionColor);
        tab.getCanvas().setFocus();
    }

    private void reloadSessionsTab() {
        if (sessionsTab != null && !sessionsTab.getTabItem().isDisposed()) {
            display.asyncExec(sessionsTab::reload);
        }
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
            openTerminal(saved, dlg.getEnteredPassword());
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
                reloadSessionsTab();
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
        dlg.setText("About Capoeira SSH");
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 24; gl.marginHeight = 18; gl.verticalSpacing = 12;
        dlg.setLayout(gl);

        Label lbl = new Label(dlg, SWT.CENTER);
        lbl.setText("Capoeira SSH Client\nVersion " + br.com.capoeirassh.ssh.BuildInfo.VERSION
            + "  (build #" + br.com.capoeirassh.ssh.BuildInfo.BUILD + ", " + br.com.capoeirassh.ssh.BuildInfo.DATE + ")"
            + "\n\nxterm-256color terminal emulator built with Java and SWT."
            + "\n\nCopyright (C) 2026 Vicente Melo — Molho Ltda.");
        lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label lblThirdParty = new Label(dlg, SWT.NONE);
        lblThirdParty.setText("Third-party libraries:");
        lblThirdParty.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Link link = new Link(dlg, SWT.NONE);
        link.setText(
            "• <a href=\"https://adoptium.net/\">Eclipse Temurin (OpenJDK)</a> (GPLv2 + Classpath Exception)\n" +
            "• <a href=\"https://www.eclipse.org/swt/\">Eclipse SWT</a> (EPL 2.0)\n" +
            "• <a href=\"https://github.com/mwiede/jsch\">JSch — mwiede fork</a> (BSD-style)");
        link.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        link.addListener(SWT.Selection, e -> Program.launch(e.text));

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

            MenuItem miRename = new MenuItem(menu, SWT.PUSH);
            miRename.setText("Rename Tab...");
            miRename.addListener(SWT.Selection, ev -> renameTab(terminal));

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem miDuplicate = new MenuItem(menu, SWT.PUSH);
            miDuplicate.setText("Duplicate Session");
            miDuplicate.addListener(SWT.Selection, ev -> {
                br.com.capoeirassh.ssh.model.SessionInfo info = terminal.getSessionInfo();
                int[] a = terminal.getAppearance();
                info.appearFontName = terminal.getFontName();
                info.appearFontSize = a[0];
                info.appearFgR = a[1]; info.appearFgG = a[2]; info.appearFgB = a[3];
                info.appearBgR = a[4]; info.appearBgG = a[5]; info.appearBgB = a[6];
                String currentTitle = terminal.getTabTitle();
                openTerminal(info);
                // Preserve the renamed tab title on the new tab
                if (!currentTitle.equals(info.label())) {
                    TerminalTab newTab = terminalTabs.get(terminalTabs.size() - 1);
                    newTab.rename(currentTitle);
                }
            });

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem miSettings = new MenuItem(menu, SWT.PUSH);
            miSettings.setText("Settings...");
            miSettings.addListener(SWT.Selection, ev -> showConfigurationSettingsDialog(terminal));

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem miClose = new MenuItem(menu, SWT.PUSH);
            miClose.setText("Close Session");
            miClose.addListener(SWT.Selection, ev -> {
                terminalTabs.remove(terminal);
                terminal.dispose();
                item.dispose();
                reloadSessionsTab();
                if (tabFolder.getItemCount() <= 1) showSessionsTab();
            });

            menu.setLocation(e.x, e.y);
            menu.setVisible(true);
        });
    }

    private void renameTab(TerminalTab terminal) {
        InputDialog dlg = new InputDialog(shell, "Rename Tab", "Tab title:");
        dlg.setInitialValue(terminal.getTabItem().getText().trim());
        String name = dlg.open();
        if (name != null && !name.trim().isEmpty()) {
            terminal.rename(name.trim());
        }
    }

    /** Opens the shared Configuration Setting dialog seeded from this tab's live state.
     *  Effects apply only to this running terminal — nothing is persisted to the session file. */
    private void showConfigurationSettingsDialog(TerminalTab terminal) {
        br.com.capoeirassh.ssh.model.SessionInfo info = terminal.getSessionInfo();
        int[] a = terminal.getAppearance();

        br.com.capoeirassh.ssh.model.ConfigurationSettings current =
            new br.com.capoeirassh.ssh.model.ConfigurationSettings();
        current.appearFontName = terminal.getFontName();
        current.appearFontSize = a[0];
        current.appearFgR = a[1]; current.appearFgG = a[2]; current.appearFgB = a[3];
        current.appearBgR = a[4]; current.appearBgG = a[5]; current.appearBgB = a[6];
        current.logEnabled  = terminal.isLogging();
        current.logDir      = terminal.getLogDir();
        current.logFileName = terminal.getLogFileName();
        current.terminalType  = info.terminalType;
        current.backspaceCode = info.backspaceCode;
        current.sshVerbose    = info.sshVerbose;

        ConfigurationSettingsDialog dlg = new ConfigurationSettingsDialog(shell, "Configuration Setting", current, info.host);
        if (!dlg.open()) return;
        br.com.capoeirassh.ssh.model.ConfigurationSettings s = dlg.getResult();

        terminal.applyAppearance(s.appearFontName, s.appearFontSize,
            new org.eclipse.swt.graphics.RGB(s.appearFgR, s.appearFgG, s.appearFgB),
            new org.eclipse.swt.graphics.RGB(s.appearBgR, s.appearBgG, s.appearBgB));

        if (s.logEnabled) {
            terminal.startLogging(s.logDir, s.logFileName);
        } else if (terminal.isLogging()) {
            terminal.stopLogging();
        }

        info.terminalType  = s.terminalType;
        info.backspaceCode = s.backspaceCode;
        info.sshVerbose    = s.sshVerbose;
        terminal.setSshVerbose(s.sshVerbose);
    }

    private void closeAll() {
        for (TerminalTab t : terminalTabs) t.dispose();
        terminalTabs.clear();
        if (colorSelectionDisconnectedRed != null && !colorSelectionDisconnectedRed.isDisposed())
            colorSelectionDisconnectedRed.dispose();
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

            int insertIdx = Math.max(FIRST_MOVABLE_INDEX, dropIndexAt(e.x, src));
            int srcIdx    = tabFolder.indexOf(src);
            if (insertIdx == srcIdx || insertIdx == srcIdx + 1) return;

            moveTab(src, srcIdx, insertIdx);
        });

        // Paint the drop-position indicator (a bright vertical bar)
        tabFolder.addListener(SWT.Paint, e -> {
            if (dropIndicatorX < 0 || draggedTab == null) return;
            int insertIdx = Math.max(FIRST_MOVABLE_INDEX, dropIndexAt(dropIndicatorX, draggedTab));
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

    /** The Home tab is always first; no other tab may be dropped before it. */
    private static final int FIRST_MOVABLE_INDEX = 1;

    /**
     * Moves a CTabItem from srcIdx to insertIdx by recreating it at the new position.
     * Updates the corresponding TerminalTab reference and the welcomeTab reference if needed.
     */
    private void moveTab(CTabItem src, int srcIdx, int insertIdx) {
        // Save all properties
        String   text       = src.getText();
        Image    image      = src.getImage();
        String   tooltip    = src.getToolTipText();
        Control  control    = src.getControl();
        boolean  showClose  = (src.getStyle() & SWT.CLOSE) != 0;
        Font     font       = src.getFont();
        Color    foreground = src.getForeground();

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
        if (image      != null) newItem.setImage(image);
        if (tooltip    != null) newItem.setToolTipText(tooltip);
        if (font       != null) newItem.setFont(font);
        if (foreground != null) newItem.setForeground(foreground);
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

    private boolean confirmCloseTab() {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Close Session");
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 20; gl.marginHeight = 16; gl.verticalSpacing = 14;
        dlg.setLayout(gl);

        Label msg = new Label(dlg, SWT.WRAP);
        msg.setText("Close this session?");
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 240;
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

        Rectangle pb = shell.getBounds();
        org.eclipse.swt.graphics.Point sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return result[0];
    }

    private boolean confirmClose(long activeSessions) {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Close Capoeira SSH");
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
