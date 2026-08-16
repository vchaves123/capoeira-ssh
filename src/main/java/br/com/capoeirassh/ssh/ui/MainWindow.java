package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
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
                refreshWindowTitleForSelection();
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
                // Closing the current tab makes SWT auto-select another one without reliably
                // firing the selection listener (same reasoning as every other setSelection()
                // call site in this class), so the title bar needs an explicit refresh here too.
                refreshWindowTitleForSelection();
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

    /** Pushes the currently selected tab's title (its remote OSC title if it's a terminal, or the
     *  app default for the Sessions tab) onto the shell — since {@code CTabFolder.setSelection}
     *  called programmatically doesn't reliably fire the selection listener above, every call
     *  site that changes the selection in code also calls this directly. */
    private void refreshWindowTitleForSelection() {
        if (shell.isDisposed() || tabFolder.isDisposed()) return;
        CTabItem sel = tabFolder.getSelection();
        terminalTabs.stream()
            .filter(t -> t.getTabItem() == sel)
            .findFirst()
            .ifPresentOrElse(TerminalTab::applyTitleIfActive, () -> TerminalTab.applyBaseWindowTitle(shell));
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
        if (sessionsTab != null && !sessionsTab.getTabItem().isDisposed()) {
            tabFolder.setSelection(sessionsTab.getTabItem());
            refreshWindowTitleForSelection();
        }
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
        tab.setOnCloseRequest(() -> {
            if (confirmCloseTab("This tab is disconnected. Closing it will discard the "
                    + "scrollback history if you haven't saved it with \"Save History...\". "
                    + "Close anyway?"))
                closeTab(tab);
        });
        tab.setOnStateChanged(() -> { refreshSelectionColor(); reloadSessionsTab(); });
        if (info.appearFontSize > 0) {
            tab.applyAppearance(info.appearFontName, info.appearFontSize,
                new org.eclipse.swt.graphics.RGB(info.appearFgR, info.appearFgG, info.appearFgB),
                new org.eclipse.swt.graphics.RGB(info.appearBgR, info.appearBgG, info.appearBgB));
        }
        terminalTabs.add(tab);
        reloadSessionsTab();
        tabFolder.setSelection(tab.getTabItem());
        refreshWindowTitleForSelection();
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
                // Same reasoning as the close-button handler above: disposing the current item
                // makes SWT auto-select another tab without reliably firing the selection
                // listener, so the title bar needs an explicit refresh.
                refreshWindowTitleForSelection();
                if (tabFolder.getItemCount() <= 1) showSessionsTab();
            });
    }

    private void navigateTab(int delta) {
        int total = tabFolder.getItemCount();
        if (total == 0) return;
        int next = (tabFolder.getSelectionIndex() + delta + total) % total;
        tabFolder.setSelection(next);
        refreshWindowTitleForSelection();
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

        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(); rl.spacing = 8;
        btns.setLayout(rl);

        Button btnCheckUpdates = new Button(btns, SWT.PUSH);
        btnCheckUpdates.setText("  Check for Updates  ");
        Button ok = new Button(btns, SWT.PUSH);
        ok.setText("  OK  ");
        ok.addListener(SWT.Selection, e -> dlg.close());
        dlg.setDefaultButton(ok);

        btnCheckUpdates.addListener(SWT.Selection, e -> checkForUpdates(dlg, btnCheckUpdates));

        dlg.pack();
        Rectangle pb = shell.getBounds();
        org.eclipse.swt.graphics.Point sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);
        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    /** Runs an explicit update check triggered from the About dialog's button — always gives
     *  feedback (update found / already up to date / couldn't check), unlike a passive
     *  background check. There is no automatic check anywhere else in the app. */
    private void checkForUpdates(Shell aboutDlg, Button trigger) {
        String originalText = trigger.getText();
        trigger.setEnabled(false);
        trigger.setText("  Checking…  ");
        br.com.capoeirassh.ssh.UpdateChecker.checkNow(result -> display.asyncExec(() -> {
            if (trigger.isDisposed()) return;
            trigger.setEnabled(true);
            trigger.setText(originalText);
            switch (result.status()) {
                case UPDATE_AVAILABLE -> showUpdateAvailableDialog(aboutDlg, result.info());
                case UP_TO_DATE -> {
                    MessageBox mb = new MessageBox(aboutDlg, SWT.ICON_INFORMATION | SWT.OK);
                    mb.setText("No Updates Available");
                    mb.setMessage("You're on the latest version (v" + br.com.capoeirassh.ssh.BuildInfo.VERSION + ").");
                    mb.open();
                }
                case ERROR -> {
                    MessageBox mb = new MessageBox(aboutDlg, SWT.ICON_WARNING | SWT.OK);
                    mb.setText("Check for Updates");
                    mb.setMessage("Could not check for updates. Check your internet connection and try again.");
                    mb.open();
                }
            }
        }));
    }

    /** Shows the new version's release notes with an option to open the release page. */
    private void showUpdateAvailableDialog(Shell parent, br.com.capoeirassh.ssh.UpdateChecker.UpdateInfo info) {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Update Available");
        AppIcon.apply(dlg);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 10;
        dlg.setLayout(gl);

        Label lblTitle = new Label(dlg, SWT.NONE);
        lblTitle.setText("Capoeira SSH v" + info.version() + " is available "
            + "(you have v" + br.com.capoeirassh.ssh.BuildInfo.VERSION + ").");
        lblTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        GridData gdNotes = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdNotes.widthHint = 460; gdNotes.heightHint = 260;
        String notesText = info.releaseNotes().isBlank() ? "(no release notes provided)" : info.releaseNotes();
        buildNotesArea(dlg, gdNotes, notesText, () -> ReleaseNotesHtml.render(notesText));

        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(); rl.spacing = 8;
        btns.setLayout(rl);

        Button btnOpen = new Button(btns, SWT.PUSH);
        btnOpen.setText("  Open in browser  ");
        Button btnClose = new Button(btns, SWT.PUSH);
        btnClose.setText("  Close  ");
        dlg.setDefaultButton(btnOpen);

        btnClose.addListener(SWT.Selection, e -> dlg.dispose());
        btnOpen.addListener(SWT.Selection, e -> Program.launch(info.releaseUrl()));

        dlg.pack();
        Rectangle pb = parent.getBounds();
        org.eclipse.swt.graphics.Point sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);
        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    /**
     * Renders release notes into {@code parent} using an embedded {@link org.eclipse.swt.browser.Browser}
     * when possible, falling back to a plain-text {@link Text} control if either the platform has
     * no browser engine ({@link SWTError}, e.g. WebKitGTK missing on Linux) or {@code htmlSupplier}
     * itself throws. The latter matters because the HTML comes from {@code ReleaseNotesHtml.render()}
     * over a GitHub release's notes text — content from outside this program, not fully trusted —
     * so a parsing edge case there must degrade to plain text rather than propagate as an unhandled
     * exception. If the Browser was already constructed before the failure, it's disposed first so
     * an empty, half-built Browser doesn't linger alongside the fallback Text.
     *
     * Package-private (not private) so a test can inject a throwing {@code htmlSupplier} directly,
     * without needing to find a real input that makes {@code ReleaseNotesHtml.render()} fail.
     */
    void buildNotesArea(Composite parent, GridData layoutData, String notesText,
                         java.util.function.Supplier<String> htmlSupplier) {
        org.eclipse.swt.browser.Browser browser = null;
        try {
            browser = new org.eclipse.swt.browser.Browser(parent, SWT.BORDER);
            browser.setLayoutData(layoutData);
            browser.setText(htmlSupplier.get());
        } catch (SWTError | RuntimeException error) {
            if (browser != null && !browser.isDisposed()) browser.dispose();
            Text notes = new Text(parent, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
            notes.setText(notesText);
            notes.setLayoutData(layoutData);
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

            // SFTP has no meaning over a local serial connection — there's no remote
            // filesystem on the other end of an RS232 link the way there is over SSH.
            boolean isSerial = terminal.getSessionInfo().connectionType
                == br.com.capoeirassh.ssh.model.SessionInfo.ConnectionType.SERIAL;
            if (!isSerial) {
                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem miUpload = new MenuItem(menu, SWT.PUSH);
                miUpload.setText("Upload file(s)...");
                miUpload.setEnabled(!terminal.isDisconnected());
                miUpload.addListener(SWT.Selection, ev -> uploadFiles(terminal));

                MenuItem miDownload = new MenuItem(menu, SWT.PUSH);
                miDownload.setText("Download file(s)...");
                miDownload.setEnabled(!terminal.isDisconnected());
                miDownload.addListener(SWT.Selection, ev -> downloadFiles(terminal));
            }

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem miClose = new MenuItem(menu, SWT.PUSH);
            miClose.setText("Close Session");
            miClose.addListener(SWT.Selection, ev -> closeTab(terminal));

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

    // -----------------------------------------------------------------------
    // SFTP upload / download (both support selecting several files at once)
    // -----------------------------------------------------------------------

    /** Lets the user pick one or more local files and send them to a remote folder chosen
     *  by browsing the tab's live SFTP session. */
    private void uploadFiles(TerminalTab terminal) {
        FileDialog fd = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
        fd.setText("Select file(s) to upload");
        if (fd.open() == null) return; // cancelled

        String   dir   = fd.getFilterPath();
        String[] names = fd.getFileNames();
        if (names == null || names.length == 0) return;
        List<java.io.File> localFiles = new ArrayList<>();
        for (String n : names) localFiles.add(new java.io.File(dir, n));

        // Its own SSH connection, independent of the terminal's — the transfer survives the tab
        // disconnecting/reconnecting, and vice versa. Costs a fresh credential resolution (silent
        // for a saved/vaulted credential or a passphrase-less key; a prompt for a manually-typed
        // password, since that's zeroed right after the terminal's own connect() and can't be reused).
        ConnectDialog credDlg = new ConnectDialog(shell, terminal.getSessionInfo());
        char[] password = credDlg.open();
        if (password == null) return; // cancelled

        br.com.capoeirassh.ssh.ssh.SftpConnection sftpConn = new br.com.capoeirassh.ssh.ssh.SftpConnection();
        com.jcraft.jsch.ChannelSftp sftp;
        try {
            sftp = sftpConn.connect(terminal.getSessionInfo(), password, display);
        } catch (Exception ex) {
            sftpChannelError(terminal, ex);
            return;
        }
        RemoteFileBrowserDialog browser = new RemoteFileBrowserDialog(
            shell, sftp, RemoteFileBrowserDialog.Mode.PICK_FOLDER, "Select destination folder");
        if (!browser.open()) {
            sftpConn.close();
            return;
        }
        String remoteDir = browser.getSelectedFolder();

        long totalBytes = 0;
        for (java.io.File f : localFiles) totalBytes += f.length();

        // The transfer runs off the UI thread so a large/slow file doesn't freeze the window;
        // the progress window (built on the UI thread, before this starts) reflects it live.
        TransferProgressDialog progress = new TransferProgressDialog(shell, "Uploading", localFiles.size(), totalBytes);
        new Thread(() -> {
            int  okCount      = 0;
            int  skipCount    = 0;
            long[] globalDone = { 0 };
            StringBuilder errors = new StringBuilder();
            FileConflictDialog.Action appliedToAll = null;
            for (int i = 0; i < localFiles.size() && !progress.isCancelled(); i++) {
                java.io.File f = localFiles.get(i);
                long fileSize = f.length();
                int  fileIndex = i + 1;
                String remotePath = joinRemote(remoteDir, f.getName());

                if (remoteExists(sftp, remotePath)) {
                    FileConflictDialog.Action action = appliedToAll;
                    if (action == null) {
                        FileConflictDialog.Result r = askConflict(f.getName());
                        action = (r != null) ? r.action() : FileConflictDialog.Action.SKIP;
                        if (r != null && r.applyToAll()) appliedToAll = action;
                    }
                    if (action == FileConflictDialog.Action.SKIP) {
                        skipCount++;
                        progress.update(fileIndex, f.getName() + " (skipped)", fileSize, fileSize, globalDone[0]);
                        continue;
                    } else if (action == FileConflictDialog.Action.RENAME) {
                        remotePath = joinRemote(remoteDir, uniqueRemoteName(sftp, remoteDir, f.getName()));
                    }
                    // OVERWRITE: keep remotePath as-is
                }

                long[] fileDone = { 0 };
                try {
                    sftp.put(f.getAbsolutePath(), remotePath, new com.jcraft.jsch.SftpProgressMonitor() {
                        @Override public void init(int op, String src, String dest, long max) {
                            progress.update(fileIndex, f.getName(), 0, fileSize, globalDone[0]);
                        }
                        @Override public boolean count(long bytes) {
                            fileDone[0]   += bytes;
                            globalDone[0] += bytes;
                            progress.update(fileIndex, f.getName(), fileDone[0], fileSize, globalDone[0]);
                            return !progress.isCancelled();
                        }
                        @Override public void end() {}
                    });
                    okCount++;
                } catch (Exception ex) {
                    errors.append("• ").append(f.getName()).append(": ").append(ex.getMessage()).append("\n");
                }
            }
            progress.close();
            sftpConn.close();

            int    finalOkCount   = okCount;
            int    finalSkipCount = skipCount;
            String finalErrors    = errors.toString();
            display.asyncExec(() -> {
                MessageBox mb = new MessageBox(shell, (finalErrors.isEmpty() ? SWT.ICON_INFORMATION : SWT.ICON_WARNING) | SWT.OK);
                mb.setText("Upload");
                mb.setMessage(finalOkCount + " of " + localFiles.size() + " file(s) uploaded to " + remoteDir
                    + (finalSkipCount > 0 ? " (" + finalSkipCount + " skipped)" : "")
                    + (finalErrors.isEmpty() ? "" : "\n\nErrors:\n" + finalErrors));
                mb.open();
            });
        }, "sftp-upload").start();
    }

    /** Lets the user browse the tab's live SFTP session, pick one or more remote files, and save
     *  them to a local folder. */
    private void downloadFiles(TerminalTab terminal) {
        // Its own SSH connection, independent of the terminal's — see uploadFiles() for why.
        ConnectDialog credDlg = new ConnectDialog(shell, terminal.getSessionInfo());
        char[] password = credDlg.open();
        if (password == null) return; // cancelled

        br.com.capoeirassh.ssh.ssh.SftpConnection sftpConn = new br.com.capoeirassh.ssh.ssh.SftpConnection();
        com.jcraft.jsch.ChannelSftp sftp;
        try {
            sftp = sftpConn.connect(terminal.getSessionInfo(), password, display);
        } catch (Exception ex) {
            sftpChannelError(terminal, ex);
            return;
        }
        RemoteFileBrowserDialog browser = new RemoteFileBrowserDialog(
            shell, sftp, RemoteFileBrowserDialog.Mode.PICK_FILES, "Select file(s) to download");
        if (!browser.open()) {
            sftpConn.close();
            return;
        }
        List<RemoteFileBrowserDialog.PickedFile> remoteFiles = browser.getSelectedFiles();

        DirectoryDialog dd = new DirectoryDialog(shell, SWT.NONE);
        dd.setText("Select destination folder");
        String localDir = dd.open();
        if (localDir == null) { // cancelled
            sftpConn.close();
            return;
        }

        long totalBytes = 0;
        for (RemoteFileBrowserDialog.PickedFile pf : remoteFiles) totalBytes += pf.size;

        // The transfer runs off the UI thread so a large/slow file doesn't freeze the window;
        // the progress window (built on the UI thread, before this starts) reflects it live.
        TransferProgressDialog progress = new TransferProgressDialog(shell, "Downloading", remoteFiles.size(), totalBytes);
        new Thread(() -> {
            int    okCount     = 0;
            int    skipCount   = 0;
            long[] globalDone  = { 0 };
            StringBuilder errors = new StringBuilder();
            FileConflictDialog.Action appliedToAll = null;
            for (int i = 0; i < remoteFiles.size() && !progress.isCancelled(); i++) {
                RemoteFileBrowserDialog.PickedFile pf = remoteFiles.get(i);
                String name      = pf.name();
                long   fileSize  = pf.size;
                int    fileIndex = i + 1;
                String localName = name;

                if (new java.io.File(localDir, localName).exists()) {
                    FileConflictDialog.Action action = appliedToAll;
                    if (action == null) {
                        FileConflictDialog.Result r = askConflict(name);
                        action = (r != null) ? r.action() : FileConflictDialog.Action.SKIP;
                        if (r != null && r.applyToAll()) appliedToAll = action;
                    }
                    if (action == FileConflictDialog.Action.SKIP) {
                        skipCount++;
                        progress.update(fileIndex, name + " (skipped)", fileSize, fileSize, globalDone[0]);
                        continue;
                    } else if (action == FileConflictDialog.Action.RENAME) {
                        localName = uniqueLocalName(localDir, name);
                    }
                    // OVERWRITE: keep localName as-is
                }

                long[] fileDone = { 0 };
                String finalLocalName = localName;
                try {
                    sftp.get(pf.path, new java.io.File(localDir, finalLocalName).getAbsolutePath(),
                        new com.jcraft.jsch.SftpProgressMonitor() {
                            @Override public void init(int op, String src, String dest, long max) {
                                progress.update(fileIndex, name, 0, fileSize, globalDone[0]);
                            }
                            @Override public boolean count(long bytes) {
                                fileDone[0]   += bytes;
                                globalDone[0] += bytes;
                                progress.update(fileIndex, name, fileDone[0], fileSize, globalDone[0]);
                                return !progress.isCancelled();
                            }
                            @Override public void end() {}
                        });
                    okCount++;
                } catch (Exception ex) {
                    errors.append("• ").append(name).append(": ").append(ex.getMessage()).append("\n");
                }
            }
            progress.close();
            sftpConn.close();

            int    finalOkCount   = okCount;
            int    finalSkipCount = skipCount;
            String finalErrors    = errors.toString();
            display.asyncExec(() -> {
                MessageBox mb = new MessageBox(shell, (finalErrors.isEmpty() ? SWT.ICON_INFORMATION : SWT.ICON_WARNING) | SWT.OK);
                mb.setText("Download");
                mb.setMessage(finalOkCount + " of " + remoteFiles.size() + " file(s) downloaded to " + localDir
                    + (finalSkipCount > 0 ? " (" + finalSkipCount + " skipped)" : "")
                    + (finalErrors.isEmpty() ? "" : "\n\nErrors:\n" + finalErrors));
                mb.open();
            });
        }, "sftp-download").start();
    }

    /** Shown when {@link br.com.capoeirassh.ssh.ssh.SftpConnection#connect} fails. JSch reports this the same way
     *  whether the server has no SFTP subsystem enabled, briefly refused the channel, or
     *  something else went wrong opening it — there's no distinct exception type to tell those
     *  apart — so the message leads with the most likely cause and still surfaces the raw JSch
     *  text underneath for anyone who needs to diagnose further. */
    private void sftpChannelError(TerminalTab terminal, Exception ex) {
        MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        mb.setText("SFTP");
        mb.setMessage("Could not open an SFTP channel to " + terminal.getSessionInfo().host + ".\n\n"
            + "This usually means the server's SSH daemon doesn't have the SFTP subsystem enabled "
            + "(or it briefly refused the request) — the terminal connection itself is unaffected.\n\n"
            + "Underlying error: " + ex.getMessage());
        mb.open();
    }

    /** Opens {@link FileConflictDialog} on the UI thread and blocks the calling (background
     *  transfer) thread until the user answers — same pattern as any other modal prompt, just
     *  reached via {@code syncExec} since the caller isn't the UI thread. */
    private FileConflictDialog.Result askConflict(String fileName) {
        FileConflictDialog.Result[] result = new FileConflictDialog.Result[1];
        display.syncExec(() -> result[0] = new FileConflictDialog().open(shell, fileName));
        return result[0];
    }

    /** Package-private (not private) so a JUnit test in this package can drive it directly,
     *  against a fake {@code ChannelSftp} subclass overriding {@code stat}. */
    static boolean remoteExists(com.jcraft.jsch.ChannelSftp sftp, String path) {
        try {
            sftp.stat(path);
            return true;
        } catch (com.jcraft.jsch.SftpException ex) {
            return false;
        }
    }

    static String joinRemote(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** Finds a "name (1).ext", "name (2).ext", … that doesn't yet exist on the remote side. */
    static String uniqueRemoteName(com.jcraft.jsch.ChannelSftp sftp, String dir, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int n = 1;
        String candidate;
        do {
            candidate = base + " (" + n + ")" + ext;
            n++;
        } while (remoteExists(sftp, joinRemote(dir, candidate)));
        return candidate;
    }

    /** Finds a "name (1).ext", "name (2).ext", … that doesn't yet exist in the local folder.
     *  Package-private (not private) so a JUnit test in this package can drive it directly. */
    static String uniqueLocalName(String dir, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int n = 1;
        String candidate;
        do {
            candidate = base + " (" + n + ")" + ext;
            n++;
        } while (new java.io.File(dir, candidate).exists());
        return candidate;
    }

    /**
     * Applies a byte-level tracing change requested from the Configuration Settings dialog, and
     * tells the user where the file went.
     *
     * <p>The path matters enough to interrupt for: a trace is only useful if it can be found
     * afterwards, and there is no other place in the UI that reveals the file name. Turning it off
     * confirms the same path so the user leaves knowing what to go read.
     */
    private void applyTraceChange(TerminalTab terminal, boolean turningOn) {
        java.nio.file.Path file = terminal.setTracing(turningOn);

        if (turningOn && file == null) {
            MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            mb.setText("Trace");
            mb.setMessage("Could not create the trace file. Tracing was not started.");
            mb.open();
            return;
        }

        MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        mb.setText("Trace");
        mb.setMessage(turningOn
            ? "Tracing every byte sent and received to:\n\n" + file
              + "\n\nPress Ctrl+Shift+D in the terminal to record a snapshot of the screen state."
            : "Tracing stopped.\n\n" + (file != null ? file : ""));
        mb.open();
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
        current.allowColumnMode = info.allowColumnMode;

        ConfigurationSettingsDialog dlg = new ConfigurationSettingsDialog(
            shell, "Configuration Setting", current, info.host, true, terminal.isTracing());
        if (!dlg.open()) return;
        br.com.capoeirassh.ssh.model.ConfigurationSettings s = dlg.getResult();

        boolean traceRequested = dlg.getTraceEnabled();
        if (traceRequested != terminal.isTracing()) {
            applyTraceChange(terminal, traceRequested);
        }

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
        info.allowColumnMode = s.allowColumnMode;
        terminal.setAllowColumnMode(s.allowColumnMode);
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

    /** Removes a tab and its terminal outright — no confirmation of its own. Callers that can
     *  lose something the user might not expect (e.g. an unsaved scrollback history) should
     *  confirm first, via {@link #confirmCloseTab(String)}. */
    private void closeTab(TerminalTab terminal) {
        CTabItem item = terminal.getTabItem();
        terminalTabs.remove(terminal);
        terminal.dispose();
        item.dispose();
        reloadSessionsTab();
        if (tabFolder.getItemCount() <= 1) showSessionsTab();
    }

    private boolean confirmCloseTab() {
        return confirmCloseTab("Close this session?");
    }

    private boolean confirmCloseTab(String message) {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Close Session");
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 20; gl.marginHeight = 16; gl.verticalSpacing = 14;
        dlg.setLayout(gl);

        Label msg = new Label(dlg, SWT.WRAP);
        msg.setText(message);
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
