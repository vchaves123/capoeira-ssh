package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.util.function.Consumer;

/**
 * The permanent "Sessions" tab.
 *
 * Layout (SashForm inside the tab):
 *   ┌────────────────────┬─────────────────────────────────┐
 *   │  Session tree      │  Welcome / quick-start info     │
 *   │  (tree panel)      │                                 │
 *   └────────────────────┴─────────────────────────────────┘
 */
public class SessionsTab {

    private final CTabItem        tabItem;
    private final SessionTreePanel treePanel;

    public SessionsTab(CTabFolder folder, Shell shell,
                       Consumer<SessionInfo> onConnect, Runnable onNewSession,
                       Runnable onCredentials, Runnable onAbout) {

        tabItem = new CTabItem(folder, SWT.NONE);   // no close button
        tabItem.setText("Sessions");

        Display display = folder.getDisplay();

        SashForm sash = new SashForm(folder, SWT.HORIZONTAL);

        // ── Left: session tree ───────────────────────────────────────────
        treePanel = new SessionTreePanel(sash, shell, onConnect);

        // ── Right: welcome / info pane ───────────────────────────────────
        Composite info = new Composite(sash, SWT.NONE);
        info.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 40; gl.marginHeight = 40;
        info.setLayout(gl);

        spacer(info, 20);

        Label title = new Label(info, SWT.CENTER);
        title.setText("14bis SSH Client");
        title.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        title.setForeground(new Color(display, 102, 204, 255));
        title.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font titleFont = new Font(display, "Consolas", 22, SWT.BOLD);
        title.setFont(titleFont);
        title.addDisposeListener(e -> titleFont.dispose());

        spacer(info, 10);

        Label sub = new Label(info, SWT.CENTER);
        sub.setText("xterm-256color terminal emulator");
        sub.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        sub.setForeground(new Color(display, 140, 140, 140));
        sub.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font subFont = new Font(display, "Consolas", 11, SWT.NORMAL);
        sub.setFont(subFont);
        sub.addDisposeListener(e -> subFont.dispose());

        spacer(info, 30);

        String[] lines = {
            "Getting started",
            "",
            "  1.  Use the session tree on the left to manage connections.",
            "  2.  Right-click the tree to create a New Session or a New Group.",
            "  3.  Double-click a saved session (or press Enter) to connect.",
            "  4.  Each connection opens a new terminal tab.",
            "",
            "  Keyboard shortcuts",
            "  ──────────────────────────────────────────",
            "  Ctrl + N   New session",
            "  Ctrl + W   Close current tab",
            "  Ctrl + Tab / Ctrl + Shift + Tab   Switch tabs",
        };

        Font monoFont = new Font(display, "Consolas", 11, SWT.NORMAL);
        for (String line : lines) {
            Label lbl = new Label(info, SWT.NONE);
            lbl.setText(line);
            lbl.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
            boolean isHeading = !line.isBlank() && !line.startsWith(" ") && !line.startsWith("─");
            lbl.setForeground(isHeading
                ? new Color(display, 102, 204, 255)
                : new Color(display, 204, 204, 204));
            lbl.setFont(monoFont);
            lbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }
        info.addDisposeListener(e -> monoFont.dispose());

        spacer(info, 24);

        Composite btnRow = new Composite(info, SWT.NONE);
        btnRow.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        btnRow.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 12; rl.marginWidth = 0; rl.marginHeight = 0;
        btnRow.setLayout(rl);

        Button btnNew      = new Button(btnRow, SWT.PUSH); btnNew.setText("  + New Session  ");
        Button btnCreds    = new Button(btnRow, SWT.PUSH); btnCreds.setText("  Credential Manager  ");
        Button btnAppear   = new Button(btnRow, SWT.PUSH); btnAppear.setText("  Default Appearance  ");
        Button btnAbout    = new Button(btnRow, SWT.PUSH); btnAbout.setText("  About  ");

        btnNew.addListener(SWT.Selection,    e -> onNewSession.run());
        btnCreds.addListener(SWT.Selection,  e -> onCredentials.run());
        btnAbout.addListener(SWT.Selection,  e -> onAbout.run());
        btnAppear.addListener(SWT.Selection, e -> {
            TerminalAppearanceDialog dlg = new TerminalAppearanceDialog(shell,
                br.com.quatorzebis.ssh.storage.AppearanceSettings.getFontSize(),
                br.com.quatorzebis.ssh.storage.AppearanceSettings.getFgColor(),
                br.com.quatorzebis.ssh.storage.AppearanceSettings.getBgColor());
            if (dlg.open()) {
                br.com.quatorzebis.ssh.storage.AppearanceSettings.set(
                    dlg.getChosenFontSize(), dlg.getChosenFgColor(), dlg.getChosenBgColor());
            }
        });

        sash.setWeights(new int[]{ 28, 72 });
        tabItem.setControl(sash);
    }

    public CTabItem        getTabItem()   { return tabItem;   }
    public SessionTreePanel getTreePanel() { return treePanel; }
    public void            reload()       { treePanel.reload(); }

    private static void spacer(Composite parent, int height) {
        Label gap = new Label(parent, SWT.NONE);
        gap.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_BLACK));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.heightHint = height;
        gap.setLayoutData(gd);
    }
}
